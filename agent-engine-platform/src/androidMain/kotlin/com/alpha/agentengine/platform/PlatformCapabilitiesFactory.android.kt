package com.alpha.agentengine.platform

import android.annotation.SuppressLint
import android.content.Context

/**
 * Android 平台初始化器。
 *
 * 在 Application.onCreate() 中调用：
 * ```kotlin
 * AgentPlatform.init(applicationContext)
 * ```
 */
object AgentPlatform {
    @SuppressLint("StaticFieldLeak")
    internal var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

actual fun createPlatformCapabilities(): PlatformCapabilities {
    val ctx = AgentPlatform.appContext
        ?: throw IllegalStateException(
            "AgentPlatform 未初始化。" +
            "请在 Application.onCreate() 中调用 AgentPlatform.init(applicationContext)"
        )
    return AndroidPlatformCapabilities(ctx)
}
