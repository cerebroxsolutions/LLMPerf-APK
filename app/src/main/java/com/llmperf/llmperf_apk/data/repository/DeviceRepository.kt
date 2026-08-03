package com.llmperf.llmperf_apk.data.repository

import com.llmperf.llmperf_apk.data.model.AndroidDevice
import com.llmperf.llmperf_apk.data.model.RegisterDeviceRequest
import com.llmperf.llmperf_apk.data.model.TokenRequest
import com.llmperf.llmperf_apk.data.model.TokenResponse
import com.llmperf.llmperf_apk.data.remote.ApiService
import retrofit2.Response

class DeviceRepository(private val apiService: ApiService) {

    suspend fun registerDevice(name: String, model: String): Result<AndroidDevice> =
        toResult(apiService.registerDevice(RegisterDeviceRequest(name, model)))

    suspend fun issueToken(deviceId: String): Result<TokenResponse> =
        toResult(apiService.issueToken(TokenRequest(role = "android", deviceId = deviceId)))

    private fun <T> toResult(response: Response<T>): Result<T> {
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            Result.success(body)
        } else {
            Result.failure(Exception("HTTP ${response.code()}: ${response.errorBody()?.string() ?: "unknown error"}"))
        }
    }
}
