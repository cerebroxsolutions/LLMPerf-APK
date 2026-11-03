package com.llmperf.llmperf_apk.data.remote

import com.llmperf.llmperf_apk.data.model.AndroidDevice
import com.llmperf.llmperf_apk.data.model.RegisterDeviceRequest
import com.llmperf.llmperf_apk.data.model.TestResultRequest
import com.llmperf.llmperf_apk.data.model.TestResultResponse
import com.llmperf.llmperf_apk.data.model.TokenRequest
import com.llmperf.llmperf_apk.data.model.TokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("android-devices")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<AndroidDevice>

    @POST("auth/token")
    suspend fun issueToken(@Body request: TokenRequest): Response<TokenResponse>

    @POST("test-results")
    suspend fun postTestResult(@Body request: TestResultRequest): Response<TestResultResponse>
}
