package com.v2ray.ang.embedded

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

/** Read-only license details opened from note's About page. */
class LicenseActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        val colors = MaterialTheme.colorScheme
        val background = colors.surface.toArgb()
        val text = colors.onSurface.toArgb()
        val link = colors.primary.toArgb()
        Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
            AppTopBar(title = stringResource(R.string.title_oss_license), onBackClick = { finish() })
        }) { padding ->
            AndroidView(modifier = Modifier.fillMaxSize().padding(padding), factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(background)
                    val style = "<style>body,pre{background:#%06x!important;color:#%06x!important}a{color:#%06x!important}</style>"
                        .format(background and 0xffffff, text and 0xffffff, link and 0xffffff)
                    val content = context.assets.open("open_source_licenses.html").bufferedReader().use { it.readText() }
                    loadDataWithBaseURL(null, content.replace("</head>", "$style</head>"), "text/html", "UTF-8", null)
                }
            })
        }
    }
}
