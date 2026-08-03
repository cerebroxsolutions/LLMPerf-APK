package com.llmperf.llmperf_apk.data.model

data class RegisterDeviceRequest(
    val name: String,
    val model: String
)

data class AndroidDevice(
    val id: String,
    val name: String,
    val model: String,
    val createdAt: String,
    val updatedAt: String
)

data class TokenRequest(
    val role: String,
    val deviceId: String
)

data class TokenResponse(
    val token: String,
    val expiresAt: String
)
