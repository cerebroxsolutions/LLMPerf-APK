
package com.llmperf.llmperf_apk.utils

import android.util.Log
import com.llmperf.llmperf_apk.data.model.ServerLocation
import com.llmperf.llmperf_apk.data.remote.GeolocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class LocationHelper {

    fun buildGeoLocationClient(): OkHttpClient {

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getGeolocationData(
        ip: String,
        apiKey: String
    ): ServerLocation? {

        if (ip.isBlank()) {
            Log.e("IPGeolocation", "Server IP is empty")
            return null
        }

        if (apiKey.isBlank()) {
            Log.e("IPGeolocation", "Geolocation API key is missing")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val retrofit = Retrofit.Builder()
                    .client(buildGeoLocationClient())
                    .baseUrl("https://api.ipgeolocation.io/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val service = retrofit.create(
                    GeolocationService::class.java
                )

                val response = service
                    .getGeolocation(apiKey, ip)
                    .execute()

                if (!response.isSuccessful) {
                    throw IOException(
                        "Geolocation API HTTP ${response.code()}"
                    )
                }

                val geolocation = response.body()
                    ?: throw IOException("Empty geolocation response")

                ServerLocation(
                    serverCity = geolocation.city,
                    serverState = geolocation.stateProv,
                    serverCountry = geolocation.countryName,
                    serverLatitude = geolocation.latitude?.toDoubleOrNull(),
                    serverLongitude = geolocation.longitude?.toDoubleOrNull()
                )

            } catch (e: Exception) {
                Log.e(
                    "IPGeolocation",
                    "Geolocation request failed: ${e.message}",
                    e
                )
                null
            }
        }
    }
}
