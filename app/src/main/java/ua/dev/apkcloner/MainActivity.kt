package ua.dev.apkcloner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.dev.apkcloner.clone.CloneService
import ua.dev.apkcloner.databinding.ActivityMainBinding
import ua.dev.apkcloner.model.InstalledAppInfo
import ua.dev.apkcloner.ui.AppListAdapter
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CloneService.ACTION_PROGRESS -> {
                    binding.statusText.text = intent.getStringExtra(CloneService.EXTRA_MESSAGE)
                }
                CloneService.ACTION_DONE -> {
                    binding.statusText.text = "Готово, встановлюємо…"
                }
                CloneService.ACTION_ERROR -> {
                    binding.statusText.text = "Помилка: ${intent.getStringExtra(CloneService.EXTRA_MESSAGE)}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appList.layoutManager = LinearLayoutManager(this)
        ensureInstallPermission()
        loadInstalledApps()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(CloneService.ACTION_PROGRESS)
            addAction(CloneService.ACTION_DONE)
            addAction(CloneService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(progressReceiver)
    }

    private fun ensureInstallPermission() {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { it.sourceDir != null }
            .map {
                InstalledAppInfo(
                    packageName = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    icon = pm.getApplicationIcon(it),
                    sourceApkPath = it.sourceDir
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()

        binding.appList.adapter = AppListAdapter(apps) { app -> onAppSelected(app) }
    }

    private fun onAppSelected(app: InstalledAppInfo) {
        val input = EditText(this).apply {
            setText(suggestCloneSuffix(app.packageName))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Клонувати ${app.label}")
            .setMessage("Новий applicationId:")
            .setView(input)
            .setPositiveButton("Клонувати") { _, _ ->
                val newPackage = input.text.toString().trim()
                if (newPackage.isEmpty() || newPackage == app.packageName) {
                    Toast.makeText(this, "Вкажіть інший package name", Toast.LENGTH_SHORT).show()
                } else {
                    startClone(app, newPackage)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun suggestCloneSuffix(pkg: String): String = "$pkg.clone1"

    private fun startClone(app: InstalledAppInfo, newPackage: String) {
        // The service needs a readable copy of the source APK (sourceDir may not be
        // directly accessible in all cases), so stage it in our own cache dir first.
        val staged = File(cacheDir, "source_${System.currentTimeMillis()}.apk")
        File(app.sourceApkPath).copyTo(staged, overwrite = true)

        val intent = Intent(this, CloneService::class.java).apply {
            putExtra(CloneService.EXTRA_SOURCE_APK_PATH, staged.absolutePath)
            putExtra(CloneService.EXTRA_OLD_PACKAGE, app.packageName)
            putExtra(CloneService.EXTRA_NEW_PACKAGE, newPackage)
            putExtra(CloneService.EXTRA_LABEL, app.label)
        }
        startForegroundService(intent)
    }
}
