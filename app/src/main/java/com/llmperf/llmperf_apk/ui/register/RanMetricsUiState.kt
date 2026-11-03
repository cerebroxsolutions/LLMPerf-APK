package com.llmperf.llmperf_apk.ui.register

import com.llmperf.llmperf_apk.data.model.RanMetrics

sealed class RanMetricsUiState {
    object Idle : RanMetricsUiState()
    object PermissionRequired : RanMetricsUiState()
    object Loading : RanMetricsUiState()
    data class Success(val metrics: RanMetrics) : RanMetricsUiState()
    data class Error(val message: String) : RanMetricsUiState()
}
