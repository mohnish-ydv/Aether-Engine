package com.mohnishraj.aether

import android.app.Application
import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.platform.android.AndroidEngineBootstrap

class AetherApplication : Application() {
    lateinit var runtime: EngineRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AndroidEngineBootstrap.create(this)
    }
}
