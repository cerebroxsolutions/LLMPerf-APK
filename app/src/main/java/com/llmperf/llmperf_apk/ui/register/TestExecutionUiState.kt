package com.llmperf.llmperf_apk.ui.register

/** Lifecycle of the most recent run_test command, for the "Current Test" card. */
sealed class TestExecutionUiState {
    object Idle : TestExecutionUiState()
    data class Received(val testType: String) : TestExecutionUiState()
    data class Running(val testType: String) : TestExecutionUiState()
    data class Sent(val testType: String, val status: String) : TestExecutionUiState()
    data class Failed(val testType: String, val message: String) : TestExecutionUiState()
}
