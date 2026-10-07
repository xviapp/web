package com.v2ray.ang.ui.base

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.compose.AppTheme

abstract class BaseComponentActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.v2ray.ang.embedded.EmbeddedAppearance.localizedContext(newBase))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ScreenContent()
            }
        }
    }

    @Composable
    protected abstract fun ScreenContent()
}