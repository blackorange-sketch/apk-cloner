package ua.dev.apkcloner.model

import android.graphics.drawable.Drawable

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val sourceApkPath: String
)
