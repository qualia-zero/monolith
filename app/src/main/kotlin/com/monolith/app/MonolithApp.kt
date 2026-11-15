package com.monolith.app

import android.app.Application
import com.monolith.core.common.MonolithDiagnostics

class MonolithApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MonolithDiagnostics.init(this)
    }
}
