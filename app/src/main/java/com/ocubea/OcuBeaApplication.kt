package com.ocubea

import android.app.Application
import com.ocubea.service.StreamService

/**
 * Global application class. All streaming state lives in StreamService;
 * the Application is intentionally stateless.
 */
class OcuBeaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }

    val streamService: StreamService? get() = StreamService.instance
}
