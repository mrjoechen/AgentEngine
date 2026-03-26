package com.alpha.agentengine.platform

/**
 * 创建当前平台的默认 PlatformCapabilities 实例。
 *
 * - **Android**: 使用前必须先调用 [AndroidPlatformCapabilities.init] 传入 Context。
 * - **iOS / JVM**: 直接可用，无需额外初始化。
 *
 * ```kotlin
 * // Android — Application.onCreate() 中初始化
 * AndroidPlatformCapabilities.init(applicationContext)
 *
 * // 所有平台 — 零配置使用
 * val provider = PlatformToolProvider()
 * ```
 */
expect fun createPlatformCapabilities(): PlatformCapabilities
