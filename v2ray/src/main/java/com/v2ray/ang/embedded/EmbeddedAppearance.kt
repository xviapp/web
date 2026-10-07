package com.v2ray.ang.embedded

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Note currently uses Simplified Chinese; no module-specific language preference. */
object EmbeddedAppearance {
    fun localizedContext(context: Context): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
    )
}
