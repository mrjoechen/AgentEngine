@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alpha.agentengine.platform

import kotlinx.cinterop.useContents
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.countryCode
import platform.Foundation.languageCode
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIScreen
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

/**
 * iOS 平台能力实现。
 */
class IosPlatformCapabilities : PlatformCapabilities {

    override val platformName: String = "ios"

    override suspend fun getDeviceInfo(): DeviceInfo {
        val device = UIDevice.currentDevice
        return DeviceInfo(
            model = device.model,
            manufacturer = "Apple",
            osVersion = "iOS ${device.systemVersion}",
            platform = platformName,
            cpuArchitecture = NSProcessInfo.processInfo.activeProcessorCount.toString() + "-core"
        )
    }

    override suspend fun getScreenInfo(): ScreenInfo {
        val screen = UIScreen.mainScreen
        val scale = screen.scale.toFloat()
        var widthPt = 0
        var heightPt = 0
        screen.bounds.useContents {
            widthPt = size.width.toInt()
            heightPt = size.height.toInt()
        }
        return ScreenInfo(
            widthPx = (widthPt * scale).toInt(),
            heightPx = (heightPt * scale).toInt(),
            density = scale,
            widthDp = widthPt,
            heightDp = heightPt
        )
    }

    override suspend fun getAppInfo(): AppInfo {
        val bundle = NSBundle.mainBundle
        val bundleId = bundle.bundleIdentifier ?: "unknown"
        val version = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
        val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "0"
        return AppInfo(
            packageName = bundleId,
            versionName = version,
            versionCode = build.toLongOrNull() ?: 0
        )
    }

    override suspend fun getBatteryLevel(): Int {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        return if (level < 0) -1 else (level * 100).toInt()
    }

    override suspend fun isCharging(): Boolean? {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        return when (device.batteryState) {
            UIDeviceBatteryState.UIDeviceBatteryStateCharging,
            UIDeviceBatteryState.UIDeviceBatteryStateFull -> true
            UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> false
            else -> null
        }
    }

    override suspend fun getNetworkStatus(): NetworkStatus {
        // NWPathMonitor 需要异步回调，简化返回
        // 完整实现可由 App 层子类化并使用 NWPathMonitor
        return NetworkStatus.UNKNOWN
    }

    override suspend fun getMemoryInfo(): MemoryInfo {
        val totalMb = NSProcessInfo.processInfo.physicalMemory.toLong() / (1024 * 1024)
        return MemoryInfo(
            usedMb = 0, // mach_task_basic_info 需要 posix 调用，暂不实现
            totalMb = totalMb,
            freeMb = 0
        )
    }

    override suspend fun getStorageInfo(): StorageInfo {
        // 简化实现：返回空值，完整实现需要 NSFileManager + URL resource values
        return StorageInfo(usedMb = 0, totalMb = 0, freeMb = 0)
    }

    override suspend fun getClipboardText(): String? {
        return UIPasteboard.generalPasteboard.string
    }

    override suspend fun setClipboardText(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override suspend fun openUrl(url: String): Boolean {
        val nsUrl = NSURL.URLWithString(url) ?: return false
        return UIApplication.sharedApplication.canOpenURL(nsUrl)
            .also { if (it) UIApplication.sharedApplication.openURL(nsUrl) }
    }

    override suspend fun getLocale(): LocaleInfo {
        val locale = NSLocale.currentLocale
        val langCode = locale.languageCode
        val country = locale.countryCode ?: ""
        return LocaleInfo(
            language = langCode,
            country = country,
            displayName = "$langCode-$country"
        )
    }

    override suspend fun getTimeZone(): String {
        return NSTimeZone.localTimeZone.name
    }

    override suspend fun hasTouchScreen(): Boolean = true
}
