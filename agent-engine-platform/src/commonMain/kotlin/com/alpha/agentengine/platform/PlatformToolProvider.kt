package com.alpha.agentengine.platform

import com.alpha.agentengine.core.tool.*
import com.alpha.agentengine.core.tool.ParamType.StringType

/**
 * 将 PlatformCapabilities 自动转为 Tool 注册。
 *
 * 两种使用方式：
 * - **自动**：`PlatformToolProvider()` — 自动使用当前平台默认实现
 * - **自定义**：`PlatformToolProvider(myCapabilities)` — 传入自定义实现
 *
 * Android 平台自动模式需要先初始化：
 * ```kotlin
 * // Application.onCreate()
 * AndroidPlatformCapabilities.init(applicationContext)
 * ```
 */
class PlatformToolProvider(
    private val capabilities: PlatformCapabilities = createPlatformCapabilities()
) : ToolProvider {

    override fun provideTools(): List<ToolDefinition> = listOf(
        // ===== 设备信息 =====
        tool("get_device_info") {
            description = "获取设备信息（型号、制造商、系统版本、平台、CPU 架构）"
            execute {
                val info = capabilities.getDeviceInfo()
                ToolResult.text(buildString {
                    appendLine("型号: ${info.model}")
                    appendLine("制造商: ${info.manufacturer}")
                    appendLine("系统: ${info.osVersion}")
                    appendLine("平台: ${info.platform}")
                    append("CPU 架构: ${info.cpuArchitecture}")
                })
            }
        },
        tool("get_screen_info") {
            description = "获取屏幕尺寸和分辨率信息"
            execute {
                val screen = capabilities.getScreenInfo()
                ToolResult.text(buildString {
                    appendLine("分辨率: ${screen.widthPx} x ${screen.heightPx} px")
                    appendLine("密度: ${screen.density}")
                    append("逻辑尺寸: ${screen.widthDp} x ${screen.heightDp} dp")
                })
            }
        },
        tool("get_app_info") {
            description = "获取当前应用信息（包名、版本号）"
            execute {
                val app = capabilities.getAppInfo()
                ToolResult.text(buildString {
                    appendLine("包名: ${app.packageName}")
                    appendLine("版本名: ${app.versionName}")
                    append("版本号: ${app.versionCode}")
                })
            }
        },

        // ===== 系统状态 =====
        tool("get_battery_level") {
            description = "获取当前设备电量百分比和充电状态"
            execute {
                val level = capabilities.getBatteryLevel()
                val charging = capabilities.isCharging()
                if (level < 0) {
                    ToolResult.text("无法获取电量信息")
                } else {
                    val chargingStr = when (charging) {
                        true -> "（充电中）"
                        false -> "（未充电）"
                        null -> ""
                    }
                    ToolResult.text("当前电量: ${level}%$chargingStr")
                }
            }
        },
        tool("get_network_status") {
            description = "获取当前网络连接状态（WiFi/蜂窝/以太网/断开）"
            execute {
                val status = capabilities.getNetworkStatus()
                val statusName = when (status) {
                    NetworkStatus.WIFI -> "WiFi"
                    NetworkStatus.CELLULAR -> "蜂窝数据"
                    NetworkStatus.ETHERNET -> "以太网"
                    NetworkStatus.DISCONNECTED -> "已断开"
                    NetworkStatus.UNKNOWN -> "未知"
                }
                ToolResult.text("网络状态: $statusName")
            }
        },
        tool("get_memory_info") {
            description = "获取设备内存使用情况（已用/空闲/总计）"
            execute {
                val mem = capabilities.getMemoryInfo()
                ToolResult.text(buildString {
                    appendLine("已用: ${mem.usedMb} MB")
                    appendLine("空闲: ${mem.freeMb} MB")
                    append("总计: ${mem.totalMb} MB")
                })
            }
        },
        tool("get_storage_info") {
            description = "获取设备存储空间使用情况（已用/可用/总计）"
            execute {
                val storage = capabilities.getStorageInfo()
                ToolResult.text(buildString {
                    appendLine("已用: ${storage.usedMb} MB")
                    appendLine("可用: ${storage.freeMb} MB")
                    append("总计: ${storage.totalMb} MB")
                })
            }
        },

        // ===== 系统交互 =====
        tool("get_clipboard_text") {
            description = "获取剪贴板中的文本内容"
            execute {
                val text = capabilities.getClipboardText()
                if (text != null) {
                    ToolResult.text(text)
                } else {
                    ToolResult.text("剪贴板为空")
                }
            }
        },
        tool("set_clipboard_text") {
            description = "将文本复制到剪贴板"
            parameter("text", StringType, "要复制的文本内容", required = true)
            execute { params ->
                capabilities.setClipboardText(params.getString("text"))
                ToolResult.text("已复制到剪贴板")
            }
        },
        tool("open_url") {
            description = "打开 URL（浏览器或 deep link）"
            parameter("url", StringType, "要打开的 URL", required = true)
            execute { params ->
                val success = capabilities.openUrl(params.getString("url"))
                if (success) ToolResult.text("已打开") else ToolResult.error("打开失败")
            }
        },
        tool("get_locale") {
            description = "获取设备当前语言和地区设置"
            execute {
                val locale = capabilities.getLocale()
                ToolResult.text(buildString {
                    appendLine("语言: ${locale.language}")
                    appendLine("地区: ${locale.country}")
                    append("显示名称: ${locale.displayName}")
                })
            }
        },
        tool("get_timezone") {
            description = "获取设备当前时区"
            execute {
                ToolResult.text("时区: ${capabilities.getTimeZone()}")
            }
        }
    )
}
