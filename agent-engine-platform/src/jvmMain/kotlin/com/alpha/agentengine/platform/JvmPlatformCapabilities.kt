package com.alpha.agentengine.platform

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.NetworkInterface
import java.net.URI
import java.util.Locale
import java.util.TimeZone

/**
 * JVM Desktop 平台能力实现。
 */
class JvmPlatformCapabilities : PlatformCapabilities {

    override val platformName: String = "jvm"

    override suspend fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        model = System.getProperty("os.name", "Unknown"),
        manufacturer = detectManufacturer(),
        osVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        platform = platformName,
        cpuArchitecture = System.getProperty("os.arch", "unknown")
    )

    override suspend fun getScreenInfo(): ScreenInfo {
        return try {
            if (GraphicsEnvironment.isHeadless()) {
                ScreenInfo(0, 0, 1f, 0, 0)
            } else {
                val toolkit = Toolkit.getDefaultToolkit()
                val screenSize = toolkit.screenSize
                val dpi = toolkit.screenResolution
                val density = dpi / 96f // 96 DPI = 1x
                ScreenInfo(
                    widthPx = screenSize.width,
                    heightPx = screenSize.height,
                    density = density,
                    widthDp = (screenSize.width / density).toInt(),
                    heightDp = (screenSize.height / density).toInt()
                )
            }
        } catch (_: Exception) {
            ScreenInfo(0, 0, 1f, 0, 0)
        }
    }

    override suspend fun getAppInfo(): AppInfo {
        // JVM 没有统一的"应用"概念，返回 JVM 运行时信息
        val javaVersion = System.getProperty("java.version", "unknown")
        val vmName = System.getProperty("java.vm.name", "unknown")
        return AppInfo(
            packageName = vmName,
            versionName = javaVersion,
            versionCode = Runtime.version().feature().toLong()
        )
    }

    override suspend fun getBatteryLevel(): Int = -1 // Desktop 无通用电池 API

    override suspend fun isCharging(): Boolean? = null // Desktop 无通用电池 API

    override suspend fun getNetworkStatus(): NetworkStatus {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return NetworkStatus.UNKNOWN
            val active = interfaces.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            when {
                active.isEmpty() -> NetworkStatus.DISCONNECTED
                active.any { it.name.startsWith("eth") || it.name.startsWith("en") } -> NetworkStatus.ETHERNET
                active.any { it.name.startsWith("wlan") || it.name.startsWith("wi") } -> NetworkStatus.WIFI
                else -> NetworkStatus.ETHERNET // 默认有线
            }
        } catch (_: Exception) {
            NetworkStatus.UNKNOWN
        }
    }

    override suspend fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.maxMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return MemoryInfo(
            usedMb = usedMb,
            totalMb = totalMb,
            freeMb = totalMb - usedMb
        )
    }

    override suspend fun getStorageInfo(): StorageInfo {
        return try {
            val roots = File.listRoots()
            val root = roots.firstOrNull() ?: return StorageInfo(0, 0, 0)
            val totalMb = root.totalSpace / (1024 * 1024)
            val freeMb = root.usableSpace / (1024 * 1024)
            StorageInfo(
                usedMb = totalMb - freeMb,
                totalMb = totalMb,
                freeMb = freeMb
            )
        } catch (_: Exception) {
            StorageInfo(0, 0, 0)
        }
    }

    override suspend fun getClipboardText(): String? = try {
        if (GraphicsEnvironment.isHeadless()) null
        else {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.getData(DataFlavor.stringFlavor) as? String
        }
    } catch (_: Exception) {
        null
    }

    override suspend fun setClipboardText(text: String) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(text), null)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    override suspend fun openUrl(url: String): Boolean = try {
        Desktop.getDesktop().browse(URI(url))
        true
    } catch (_: Exception) {
        false
    }

    override suspend fun getLocale(): LocaleInfo {
        val locale = Locale.getDefault()
        return LocaleInfo(
            language = locale.language,
            country = locale.country,
            displayName = locale.displayName
        )
    }

    override suspend fun getTimeZone(): String {
        return TimeZone.getDefault().id
    }

    override suspend fun hasTouchScreen(): Boolean = false

    private fun detectManufacturer(): String {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("mac") -> "Apple"
            osName.contains("win") -> "Microsoft"
            else -> "Unknown"
        }
    }
}
