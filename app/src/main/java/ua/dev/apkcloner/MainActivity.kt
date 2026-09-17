package ua.dev.apkcloner

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.dev.apkcloner.clone.CloneService
import ua.dev.apkcloner.databinding.ActivityMainBinding
import ua.dev.apkcloner.model.InstalledAppInfo
import ua.dev.apkcloner.ui.AppListAdapter
import ua.dev.apkcloner.util.Logger
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var allApps: List<InstalledAppInfo> = emptyList()

    private val packageNameRegex = Regex("^[a-zA-Z]\\w*(\\.[a-zA-Z]\\w*)+$")

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
        binding.btnViewLog.setOnClickListener { showLogDialog() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
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
        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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

        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            val q = query.trim().lowercase()
            allApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        binding.appList.adapter = AppListAdapter(filtered) { app -> onAppSelected(app) }
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
            val iconPath = stageIconForBadge(app.icon)
            startClone(staged.absolutePath, app.packageName, newPackage, app.label, iconPath)
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
        val icon = try {
            info.applicationInfo?.let { packageManager.getApplicationIcon(it) }
        } catch (_: Throwable) {
            null
        }

        askNewPackageAndClone(
            dialogTitle = "Клонувати $label",
            oldPackage = oldPackage,
            label = label
        ) { newPackage ->
            val iconPath = icon?.let { stageIconForBadge(it) }
            startClone(staged.absolutePath, oldPackage, newPackage, label, iconPath)
        }
    }

    /** Renders a Drawable to a PNG file in cache so it can be passed to CloneService by path. */
    private fun stageIconForBadge(icon: Drawable?): String? {
        if (icon == null) return null
        return try {
            val bitmap = if (icon is BitmapDrawable && icon.bitmap != null) {
                icon.bitmap
            } else {
                val w = icon.intrinsicWidth.coerceAtLeast(1)
                val h = icon.intrinsicHeight.coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    icon.setBounds(0, 0, canvas.width, canvas.height)
                    icon.draw(canvas)
                }
            }
            val file = File(cacheDir, "icon_${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (t: Throwable) {
            Logger.log("MainActivity", "Icon staging failed: ${t.message}")
            null
        }
    }

    private fun askNewPackageAndClone(
        dialogTitle: String,
        oldPackage: String,
        label: String,
        onConfirmed: (newPackage: String) -> Unit
    ) {
        val input = EditText(this).apply { setText(suggestCloneSuffix(oldPackage)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle)
            .setMessage("Новий applicationId:")
            .setView(input)
            .setPositiveButton("Клонувати", null)
            .setNegativeButton("Скасувати", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newPackage = input.text.toString().trim()
                val error = validatePackageName(newPackage, oldPackage)
                if (error != null) {
                    input.error = error
                } else {
                    dialog.dismiss()
                    onConfirmed(newPackage)
                }
            }
        }
        dialog.show()
    }

    private fun validatePackageName(newPackage: String, oldPackage: String): String? = when {
        newPackage.isEmpty() -> "Вкажіть package name"
        newPackage == oldPackage -> "Має відрізнятись від оригінального"
        !packageNameRegex.matches(newPackage) -> "Некоректний формат (напр. com.example.app)"
        else -> null
    }

    private fun suggestCloneSuffix(pkg: String): String = "$pkg.clone1"

    private fun showLogDialog() {
        val logText = Logger.readAll().ifBlank { "Лог порожній" }
        val textView = TextView(this).apply {
            text = logText
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(32, 24, 32, 24)
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Лог")
            .setView(scrollView)
            .setPositiveButton("Копіювати") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("apkcloner_log", logText))
                Toast.makeText(this, "Скопійовано в буфер обміну", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрити", null)
            .setNeutralButton("Очистити") { _, _ -> Logger.clear() }
            .show()
    }

    private fun startClone(
        sourceApkPath: String,
        oldPackage: String,
        newPackage: String,
        label: String,
        iconPath: String?
    ) {
        val intent = Intent(this, CloneService::class.java).apply {
            putExtra(CloneService.EXTRA_SOURCE_APK_PATH, sourceApkPath)
            putExtra(CloneService.EXTRA_OLD_PACKAGE, oldPackage)
            putExtra(CloneService.EXTRA_NEW_PACKAGE, newPackage)
            putExtra(CloneService.EXTRA_LABEL, label)
            iconPath?.let { putExtra(CloneService.EXTRA_ICON_PATH, it) }
        }
        startForegroundService(intent)
    }
}
