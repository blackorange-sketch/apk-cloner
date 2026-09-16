package ua.dev.apkcloner.clone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.dev.apkcloner.R
import ua.dev.apkcloner.install.ApkInstaller
import ua.dev.apkcloner.util.Logger
import java.io.File

class CloneService : Service() {

    companion object {
        const val EXTRA_SOURCE_APK_PATH = "source_apk_path"
        const val EXTRA_OLD_PACKAGE = "old_package"
        const val EXTRA_NEW_PACKAGE = "new_package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_ICON_PATH = "icon_path"
        const val CHANNEL_ID = "clone_progress"
        const val NOTIFICATION_ID = 42

        const val ACTION_PROGRESS = "ua.dev.apkcloner.CLONE_PROGRESS"
        const val ACTION_DONE = "ua.dev.apkcloner.CLONE_DONE"
        const val ACTION_ERROR = "ua.dev.apkcloner.CLONE_ERROR"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RESULT_APK_PATH = "result_apk_path"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourcePath = intent?.getStringExtra(EXTRA_SOURCE_APK_PATH)
        val oldPackage = intent?.getStringExtra(EXTRA_OLD_PACKAGE)
        val newPackage = intent?.getStringExtra(EXTRA_NEW_PACKAGE)
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: newPackage.orEmpty()
        val iconPath = intent?.getStringExtra(EXTRA_ICON_PATH)

        if (sourcePath == null || oldPackage == null || newPackage == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Готуємо $label…"))
        Logger.log("CloneService", "Start: label=$label oldPackage=$oldPackage newPackage=$newPackage sourcePath=$sourcePath")

        scope.launch {
            try {
                updateNotification("Патчимо AndroidManifest.xml…")
                val sourceApk = File(sourcePath)
                Logger.log("CloneService", "Source APK size=${sourceApk.length()} bytes")
                val workDir = File(cacheDir, "clone_work").apply { mkdirs() }
                val unsignedApk = File(workDir, "unsigned_${System.currentTimeMillis()}.apk")
                val signedApk = File(workDir, "signed_${System.currentTimeMillis()}.apk")

                val iconBitmap = iconPath?.let { BitmapFactory.decodeFile(it) }
                val badgeLabel = newPackage.substringAfterLast('.').take(3).ifBlank { "C" }
                CloneEngine.createClone(
                    sourceApk, unsignedApk, oldPackage, newPackage,
                    badgeIcon = iconBitmap, badgeLabel = badgeLabel
                )
                Logger.log("CloneService", "Manifest patched + repackaged, unsigned size=${unsignedApk.length()}")

                updateNotification("Підписуємо APK…")
                ApkSignerHelper.signApk(unsignedApk, signedApk, minSdk = 26)
                Logger.log("CloneService", "Signed OK, size=${signedApk.length()}")
                unsignedApk.delete()

                updateNotification("Встановлюємо $label…")
                broadcast(ACTION_DONE) { putExtra(EXTRA_RESULT_APK_PATH, signedApk.absolutePath) }
                Logger.log("CloneService", "Starting PackageInstaller session…")
                ApkInstaller.install(applicationContext, signedApk)
            } catch (t: Throwable) {
                Logger.logException("CloneService", t)
                broadcast(ACTION_ERROR) { putExtra(EXTRA_MESSAGE, t.message ?: t.toString()) }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun updateNotification(text: String) {
        broadcast(ACTION_PROGRESS) { putExtra(EXTRA_MESSAGE, text) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcast(action: String, extras: Intent.() -> Unit) {
        val intent = Intent(action).apply(extras).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Клонування застосунків", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
