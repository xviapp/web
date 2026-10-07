package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(com.v2ray.ang.embedded.EmbeddedAppearance::localizedContext))
        application = this
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MmkvManager.initialize(this)


        // Subscription updates run in the app process; no separate WorkManager process.
        WorkManager.initialize(this, Configuration.Builder().build())

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
    }
}
