package app.xvi.web

import android.app.Application
import com.v2ray.ang.api.ProxyCore

class WebApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyCore.initialize(this)
    }
}
