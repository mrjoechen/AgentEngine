package com.alpha.agentengine

import android.app.Application
import com.alpha.agentengine.platform.AgentPlatform

class AndroidApp: Application() {
  override fun onCreate() {
    super.onCreate()
    AgentPlatform.init(this)
  }
}