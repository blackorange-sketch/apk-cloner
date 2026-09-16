package ua.dev.apkcloner.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Installs (or updates) an APK using the public PackageInstaller session API.
 * Requires REQUEST_INSTALL_PACKAGES permission and, on Android 8+, the user granting
 * "install unknown apps" for this app once (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).
 */
object ApkInstaller {

    fun install(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.openWrite("clone_apk", 0, apkFile.length()).use { out ->
            apkFile.inputStream().use { input -> input.copyTo(out) }
            session.fsync(out)
        }

        val intent = Intent(context, InstallResultReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)

        session.commit(pendingIntent.intentSender)
        session.close()
    }
}
