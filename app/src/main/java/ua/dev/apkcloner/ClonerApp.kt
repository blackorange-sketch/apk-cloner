package ua.dev.apkcloner

import android.app.Application
import ua.dev.apkcloner.clone.ApkSignerHelper
import java.util.concurrent.Executors

class ClonerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Generate the AndroidKeyStore signing key up front (first run only) so the first
        // clone operation isn't slowed down by key generation.
        Executors.newSingleThreadExecutor().execute {
            ApkSignerHelper.warmUp(this)
        }
    }
}
