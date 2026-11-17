package com.llmperf.llmperf_apk.data.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.llmperf.llmperf_apk.data.model.DeviceMetrics
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceMetricsCollector(
    private val context: Context
) {

    fun collect(): DeviceMetrics {
        return DeviceMetrics(
            apkVersion = getApkVersion(),
            clientIp = getClientIp(),
//            clientFqdn = getClientFqdn(),
            batteryTempC = getBatteryTemperature(),
            cpuTempC = getCpuTemperature()
        )
    }

    private fun getApkVersion(): String? {
        return try {
            val packageInfo = context.packageManager
                .getPackageInfo(context.packageName, 0)

            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }


    private fun getClientIp(): String? {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

            val network = connectivityManager.activeNetwork

            if (network != null) {
                val linkProperties =
                    connectivityManager.getLinkProperties(network)

                val ipv4 = linkProperties?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull {
                        !it.isLoopbackAddress &&
                                !it.isLinkLocalAddress
                    }

                if (ipv4 != null) {
                    return ipv4.hostAddress
                }
            }

            // Fallback: inspect available network interfaces.
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return null

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp ||
                    networkInterface.isLoopback
                ) continue

                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }

            null

        } catch (e: Exception) {
            Log.e("NetworkInfo", "Failed to get client IP", e)
            null
        }
    }


    private fun getClientFqdn(): String? {
        return try {
            val connectivityManager = context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

            val network = connectivityManager.activeNetwork
            val lp = network?.let { connectivityManager.getLinkProperties(it) }

            Log.d("FQDN_DEBUG", "Network: $network")
            Log.d("FQDN_DEBUG", "DNS domains: ${lp?.domains}")
            Log.d("FQDN_DEBUG", "DNS servers: ${lp?.dnsServers}")

            val linkProperties =
                connectivityManager.getLinkProperties(network)
                    ?: return null

            val domain = linkProperties.domains?.trim()

            if (!domain.isNullOrBlank()) {
                domain
            } else {
                null
            }

        } catch (e: Exception) {
            Log.e("NetworkInfo", "Failed to get client FQDN", e)
            null
        }
    }

    private fun getBatteryTemperature(): Double? {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val temperature = batteryIntent?.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                -1
            ) ?: -1

            if (temperature > 0) {
                temperature / 10.0
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }


    private fun getCpuTemperature(): Double? {
        return try {
            val thermalDir = File("/sys/class/thermal")

            if (!thermalDir.exists() || !thermalDir.isDirectory) {
                return null
            }

            val zones = thermalDir.listFiles { file ->
                file.name.matches(Regex("thermal_zone\\d+"))
            } ?: return null

            for (zone in zones) {
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")

                if (!typeFile.exists() || !tempFile.exists()) continue

                val type = typeFile.readText().trim().lowercase()

                // Look for CPU-related thermal sensors.
                if (
                    type.contains("cpu") ||
                    type.contains("soc") ||
                    type.contains("ap") ||
                    type.contains("processor")
                ) {
                    val rawTemp = tempFile.readText().trim().toDoubleOrNull()
                        ?: continue

                    val temperature = when {
                        rawTemp > 1000 -> rawTemp / 1000.0
                        rawTemp > 0 -> rawTemp
                        else -> continue
                    }

                    if (temperature in 0.0..150.0) {
                        return temperature
                    }
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

}