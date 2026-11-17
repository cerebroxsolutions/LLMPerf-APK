package com.llmperf.llmperf_apk.data.repository

import android.util.Log
import com.llmperf.llmperf_apk.data.model.AndroidDevice
import com.llmperf.llmperf_apk.data.model.DeviceMetrics
import com.llmperf.llmperf_apk.data.model.IcmpMetrics
import com.llmperf.llmperf_apk.data.model.RanMetrics
import com.llmperf.llmperf_apk.data.model.RegisterDeviceRequest
import com.llmperf.llmperf_apk.data.model.ServerLocation
import com.llmperf.llmperf_apk.data.model.TestResultRequest
import com.llmperf.llmperf_apk.data.model.TestResultResponse
import com.llmperf.llmperf_apk.data.model.TokenRequest
import com.llmperf.llmperf_apk.data.model.TokenResponse
import com.llmperf.llmperf_apk.data.remote.ApiService
import java.io.IOException
import retrofit2.Response

class DeviceRepository(private val apiService: ApiService) {

    suspend fun registerDevice(name: String, model: String): Result<AndroidDevice> =
        safeCall { apiService.registerDevice(RegisterDeviceRequest(name, model)) }

    suspend fun issueToken(deviceId: String): Result<TokenResponse> =
        safeCall { apiService.issueToken(TokenRequest(role = "android", deviceId = deviceId)) }

    suspend fun postTestResult(
        testId: String,
        deviceId: String,
        status: String,
        testType: String,
        triggerType: String,
        targetHost: String? = null,
        errorMessage: String? = null,
        deviceMetrics: DeviceMetrics? = null,
        ranMetrics: RanMetrics? = null,
        icmpMetrics: IcmpMetrics? = null,
        serverLocation: ServerLocation? = null
    ): Result<TestResultResponse> =
        safeCall {
            apiService.postTestResult(
                TestResultRequest(testId, deviceId, status, testType, triggerType, targetHost, errorMessage, deviceMetrics, ranMetrics, icmpMetrics, serverLocation)
            )
        }

    /**
     * Retrofit's suspend calls throw (rather than return an error Response) on
     * transport-level failures such as ConnectException/SocketTimeoutException
     * when the server is unreachable — those must be caught here or they
     * escape to the ViewModel's coroutine and crash the app.
     */
    private suspend fun <T> safeCall(call: suspend () -> Response<T>): Result<T> =
        try {
            toResult(call())
        } catch (e: IOException) {
            Result.failure(Exception("Unable to reach server: ${e.message ?: "connection failed"}", e))
        }

    private fun <T> toResult(response: Response<T>): Result<T> {
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            Result.success(body)
        } else {
            Result.failure(Exception("HTTP ${response.code()}: ${response.errorBody()?.string() ?: "unknown error"}"))
        }
    }
}
