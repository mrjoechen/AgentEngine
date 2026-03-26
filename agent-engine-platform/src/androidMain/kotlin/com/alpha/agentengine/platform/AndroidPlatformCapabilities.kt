package com.alpha.agentengine.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics

/**
 * Android 平台能力实现。
 *
 * @param context 应用 Context，建议传入 Application Context 以避免内存泄漏。
 */
class AndroidPlatformCapabilities(
    private val context: Context
) : PlatformCapabilities {

    override val platformName: String = "android"

    override suspend fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        platform = platformName,
        cpuArchitecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    )

    override suspend fun getScreenInfo(): ScreenInfo {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        return ScreenInfo(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            density = density,
            widthDp = (metrics.widthPixels / density).toInt(),
            heightDp = (metrics.heightPixels / density).toInt()
        )
    }

    override suspend fun getAppInfo(): AppInfo {
        val packageName = context.packageName
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            AppInfo(
                packageName = packageName,
                versionName = pInfo.versionName ?: "unknown",
                versionCode = versionCode
            )
        } catch (_: Exception) {
            AppInfo(packageName = packageName, versionName = "unknown", versionCode = 0)
        }
    }

    override suspend fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override suspend fun isCharging(): Boolean? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        return bm.isCharging
    }

    override suspend fun getNetworkStatus(): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus.UNKNOWN
        val network = cm.activeNetwork ?: return NetworkStatus.DISCONNECTED
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkStatus.DISCONNECTED
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus.ETHERNET
            else -> NetworkStatus.UNKNOWN
        }
    }

    override suspend fun getMemoryInfo(): MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val freeMb = mi.availMem / (1024 * 1024)
        return MemoryInfo(
            usedMb = totalMb - freeMb,
            totalMb = totalMb,
            freeMb = freeMb
        )
    }

    override suspend fun getStorageInfo(): StorageInfo {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalMb = stat.totalBytes / (1024 * 1024)
            val freeMb = stat.availableBytes / (1024 * 1024)
            StorageInfo(
                usedMb = totalMb - freeMb,
                totalMb = totalMb,
                freeMb = freeMb
            )
        } catch (_: Exception) {
            StorageInfo(usedMb = 0, totalMb = 0, freeMb = 0)
        }
    }

    override suspend fun getClipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    override suspend fun setClipboardText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        cm.setPrimaryClip(ClipData.newPlainText("AgentEngine", text))
    }

    override suspend fun openUrl(url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    override suspend fun getLocale(): LocaleInfo {
        val locale = context.resources.configuration.locales[0]
        return LocaleInfo(
            language = locale.language,
            country = locale.country,
            displayName = locale.displayName
        )
    }

    override suspend fun getTimeZone(): String {
        return java.util.TimeZone.getDefault().id
    }

    override suspend fun hasTouchScreen(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
