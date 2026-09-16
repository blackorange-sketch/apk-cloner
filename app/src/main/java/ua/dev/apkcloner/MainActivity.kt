package ua.dev.apkcloner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    // Lets the user pick any .apk file from device storage via the system file picker (SAF) —
    // no storage permission needed, the returned Uri is readable regardless of source.
    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onApkFilePicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.btnPickFile.setOnClickListener {
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
        }
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
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
        askNewPackageAndClone(
            dialogTitle = "Клонувати ${app.label}",
            oldPackage = app.packageName,
            label = app.label
        ) { newPackage ->
            // sourceDir may not be directly readable by the service, so stage a local copy first.
            val staged = File(cacheDir, "source_${System.currentTimeMillis()}.apk")
            File(app.sourceApkPath).copyTo(staged, overwrite = true)
            startClone(staged.absolutePath, app.packageName, newPackage, app.label)
        }
    }

    /** Handles a .apk picked from arbitrary device storage (Downloads, SD card, etc.). */
    private fun onApkFilePicked(uri: Uri) {
        val staged = File(cacheDir, "picked_${System.currentTimeMillis()}.apk")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Toast.makeText(this, "Не вдалося прочитати файл", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Помилка читання файлу: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }

        val info = packageManager.getPackageArchiveInfo(staged.absolutePath, 0)
        val oldPackage = info?.packageName
        if (oldPackage == null) {
            Toast.makeText(this, "Це не схоже на коректний APK-файл", Toast.LENGTH_LONG).show()
            return
        }
        info.applicationInfo?.let {
            it.sourceDir = staged.absolutePath
            it.publicSourceDir = staged.absolutePath
        }
        val label = info.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: oldPackage

        askNewPackageAndClone(
            dialogTitle = "Клонувати $label",
            oldPackage = oldPackage,
            label = label
        ) { newPackage ->
            startClone(staged.absolutePath, oldPackage, newPackage, label)
        }
    }

    private fun askNewPackageAndClone(
        dialogTitle: String,
        oldPackage: String,
        label: String,
        onConfirmed: (newPackage: String) -> Unit
    ) {
        val input = EditText(this).apply { setText(suggestCloneSuffix(oldPackage)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle)
            .setMessage("Новий applicationId:")
            .setView(input)
            .setPositiveButton("Клонувати") { _, _ ->
                val newPackage = input.text.toString().trim()
                if (newPackage.isEmpty() || newPackage == oldPackage) {
                    Toast.makeText(this, "Вкажіть інший package name", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirmed(newPackage)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun suggestCloneSuffix(pkg: String): String = "$pkg.clone1"

    private fun startClone(sourceApkPath: String, oldPackage: String, newPackage: String, label: String) {
        val intent = Intent(this, CloneService::class.java).apply {
            putExtra(CloneService.EXTRA_SOURCE_APK_PATH, sourceApkPath)
            putExtra(CloneService.EXTRA_OLD_PACKAGE, oldPackage)
            putExtra(CloneService.EXTRA_NEW_PACKAGE, newPackage)
            putExtra(CloneService.EXTRA_LABEL, label)
        }
        startForegroundService(intent)
    }
}

