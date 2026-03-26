package com.alpha.agentengine.platform

/**
 * 平台能力抽象接口。
 * 各平台（Android / iOS / Desktop）提供具体实现。
 */
interface PlatformCapabilities {

    /** 平台名称（android / ios / jvm） */
    val platformName: String

    // ===== 设备信息 =====

    /** 获取设备信息 */
    suspend fun getDeviceInfo(): DeviceInfo

    /** 获取屏幕信息 */
    suspend fun getScreenInfo(): ScreenInfo

    /** 获取应用/运行环境信息 */
    suspend fun getAppInfo(): AppInfo

    // ===== 系统状态 =====

    /** 获取电量百分比（0-100），不支持返回 -1 */
    suspend fun getBatteryLevel(): Int

    /** 是否正在充电，不支持返回 null */
    suspend fun isCharging(): Boolean?

    /** 获取网络状态 */
    suspend fun getNetworkStatus(): NetworkStatus

    /** 获取内存信息 */
    suspend fun getMemoryInfo(): MemoryInfo

    /** 获取存储信息 */
    suspend fun getStorageInfo(): StorageInfo

    // ===== 系统交互 =====

    /** 获取剪贴板文本 */
    suspend fun getClipboardText(): String?

    /** 设置剪贴板文本 */
    suspend fun setClipboardText(text: String)

    /** 打开 URL（浏览器或 deep link） */
    suspend fun openUrl(url: String): Boolean

    /** 获取当前系统语言/区域 */
    suspend fun getLocale(): LocaleInfo

    /** 获取当前时区 */
    suspend fun getTimeZone(): String

    /** 设备是否支持触控/触屏 */
    suspend fun hasTouchScreen(): Boolean
}

// ===== 数据模型 =====

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val platform: String,
    val cpuArchitecture: String
)

data class ScreenInfo(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val widthDp: Int,
    val heightDp: Int
)

data class AppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long
)

data class MemoryInfo(
    /** 已用内存（MB） */
    val usedMb: Long,
    /** 总可用内存（MB） */
    val totalMb: Long,
    /** 空闲内存（MB） */
    val freeMb: Long
)

data class StorageInfo(
    /** 已用存储（MB） */
    val usedMb: Long,
    /** 总存储（MB） */
    val totalMb: Long,
    /** 可用存储（MB） */
    val freeMb: Long
)

data class LocaleInfo(
    val language: String,
    val country: String,
    val displayName: String
)

enum class NetworkStatus {
    WIFI,
    CELLULAR,
    ETHERNET,
    DISCONNECTED,
    UNKNOWN
}
