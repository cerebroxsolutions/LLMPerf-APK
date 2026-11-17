package com.llmperf.llmperf_apk.data.remote

import com.llmperf.llmperf_apk.data.model.GeolocationResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface GeolocationService {

    @GET("ipgeo")
    fun getGeolocation(
        @Query("apiKey") apiKey: String,
        @Query("ip") ip: String
    ): Call<GeolocationResponse>
}