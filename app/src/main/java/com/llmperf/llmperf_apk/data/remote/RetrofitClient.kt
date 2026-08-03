package com.llmperf.llmperf_apk.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // Single OkHttpClient shared with WebSocketManager so both the REST
    // calls and the WebSocket connection reuse the same connection pool.
    val okHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConfig.API_SERVICE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
