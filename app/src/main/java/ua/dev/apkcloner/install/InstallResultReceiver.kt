package ua.dev.apkcloner.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import ua.dev.apkcloner.clone.CloneService
import ua.dev.apkcloner.util.Logger

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        // The numeric legacy PackageManager error code (e.g. -7 = INSTALL_FAILED_CONFLICTING_PROVIDER)
        // is far more precise than the human-readable message alone.
        val legacyStatus = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, Int.MIN_VALUE)
        val otherPackage = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)

        Logger.log(
            "Install",
            "status=$status legacyStatus=$legacyStatus otherPackage=$otherPackage message=$message"
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Клон встановлено успішно", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(
                    context,
                    "Помилка встановлення (докладніше — «Переглянути лог»)",
                    Toast.LENGTH_LONG
                ).show()
                val errorIntent = Intent(CloneService.ACTION_ERROR).apply {
                    putExtra(CloneService.EXTRA_MESSAGE, "Install failed (legacy=$legacyStatus): $message")
                    setPackage(context.packageName)
                }
                context.sendBroadcast(errorIntent)
            }
        }
    }
}

