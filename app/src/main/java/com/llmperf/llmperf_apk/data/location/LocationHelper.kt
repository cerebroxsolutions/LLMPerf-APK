package com.llmperf.llmperf_apk.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "LocationHelper"

/**
 * Ported from Exfo-GenAI's utils.LocationHelper: last-known GPS fix via
 * FusedLocationProviderClient (falling back to a fresh single-update
 * request), reverse-geocoded to city/state/country via Geocoder. Requires
 * ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION — already declared in the
 * manifest and requested alongside RanMetricsCollector.REQUIRED_PERMISSIONS.
 */
class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun promptEnableGPS() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(promptEnableGPS: Boolean = false): Location? {
        if (hasLocationPermission()) {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (isGpsEnabled) {
                val last = fusedLocationClient.lastLocation.await()
                if (last != null) return last

                // Fallback: request a fresh fix when there's no cached last location yet.
                return try {
                    withContext(Dispatchers.IO) {
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            null
                        ).await()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting location: ${e.message}")
                    null
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Please enable GPS", Toast.LENGTH_SHORT).show()
                    if (promptEnableGPS) {
                        promptEnableGPS()
                    }
                }
            }
        } else {
            throw SecurityException("Location permissions are not granted.")
        }
        return null
    }

    fun getCityAndState(latitude: Double, longitude: Double): GpsCityState? {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            return if (addresses?.isNotEmpty() == true) {
                val address = addresses[0]
                GpsCityState(
                    latitude = latitude,
                    longitude = longitude,
                    city = address.locality,
                    state = address.adminArea,
                    country = address.countryName
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCityAndState: ${e.message}")
            return null
        }
    }

    suspend fun getCityAndState(promptEnableGPS: Boolean = false): GpsCityState? {
        val location = getLastKnownLocation(promptEnableGPS)
        return location?.let { getCityAndState(it.latitude, it.longitude) }
    }
}
