package ua.dev.apkcloner.clone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Overlays a small colored circle + label on a copy of the app's icon, so a cloned app is
 * visually distinguishable from the original on the launcher/app-drawer.
 *
 * Limitation: this only replaces zip entries whose file name matches the conventional
 * "res/mipmap*/ic_launcher*.png" / "res/drawable*/ic_launcher*.png" pattern used by Android
 * Studio's default project templates. Apps that rename their launcher icon resource, or whose
 * build obfuscates/hashes resource file paths (resource shrinking with path shortening), won't
 * get a badge — the clone still installs and works fine, it just keeps the original icon.
 */
object IconBadger {

    val LAUNCHER_ICON_PATTERN = Regex(
        "res/(mipmap|drawable)[^/]*/ic_launcher[^/]*\\.(png|webp)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param source original app icon bitmap
     * @param label  short text drawn inside the badge (e.g. "C", "C2")
     * @return PNG-encoded bytes of the badged icon, sized 192x192
     */
    fun badge(source: Bitmap, label: String): ByteArray {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val srcRect = Rect(0, 0, source.width, source.height)
        val dstRect = Rect(0, 0, size, size)
        canvas.drawBitmap(source, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))

        val badgeRadius = size * 0.24f
        val cx = size - badgeRadius * 0.85f
        val cy = size - badgeRadius * 0.85f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C4DFF.toInt() }
        canvas.drawCircle(cx, cy, badgeRadius, fillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = size * 0.02f
        }
        canvas.drawCircle(cx, cy, badgeRadius, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = badgeRadius * (if (label.length > 1) 0.95f else 1.2f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, cx, textY, textPaint)

        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
