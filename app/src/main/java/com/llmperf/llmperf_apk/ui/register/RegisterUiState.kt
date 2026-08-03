package com.llmperf.llmperf_apk.ui.register

sealed class RegisterUiState {
    object Idle : RegisterUiState()
    object Registering : RegisterUiState()
    object FetchingToken : RegisterUiState()
    data class Connected(val deviceId: String, val name: String) : RegisterUiState()
    data class Error(val message: String) : RegisterUiState()
}
