package ua.dev.apkcloner.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal persistent logger so clone/install failures can be inspected after the fact —
 * Toasts truncate/disappear before you can read the full PackageInstaller error message.
 */
object Logger {
    private const val FILE_NAME = "apkcloner_log.txt"
    private const val MAX_SIZE = 512 * 1024 // trim oldest content once the log passes this size

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val file = logFile ?: return
        val line = "${dateFormat.format(Date())} [$tag] $message\n"
        file.appendText(line)
        trimIfNeeded(file)
    }

    @Synchronized
    fun logException(tag: String, t: Throwable) {
        log(tag, "ERROR: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString()}")
    }

    fun readAll(): String = logFile?.takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear() {
        logFile?.writeText("")
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() > MAX_SIZE) {
            val text = file.readText()
            file.writeText(text.takeLast(MAX_SIZE / 2))
        }
    }
}
