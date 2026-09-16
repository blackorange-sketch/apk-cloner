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
        // EXTRA_STATUS_MESSAGE is the most useful field here: for session-based installs the
        // system embeds the underlying reason in this text (e.g. "...INSTALL_FAILED_CONFLICTING_PROVIDER...").
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val otherPackage = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)

        Logger.log(
            "Install",
            "status=${statusName(status)}($status) otherPackage=$otherPackage message=$message"
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
                    putExtra(CloneService.EXTRA_MESSAGE, "Install failed (${statusName(status)}): $message")
                    setPackage(context.packageName)
                }
                context.sendBroadcast(errorIntent)
            }
        }
    }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
        PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
        PackageInstaller.STATUS_FAILURE -> "FAILURE"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
        else -> "UNKNOWN"
    }
}
