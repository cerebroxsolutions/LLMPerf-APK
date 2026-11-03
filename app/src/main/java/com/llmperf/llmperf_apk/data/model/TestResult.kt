package com.llmperf.llmperf_apk.data.model

data class TestResultRequest(
    val testId: String,
    val deviceId: String,
    val status: String,
    val testType: String,
    val triggerType: String,
    // Operator-chosen icmp target, echoed back from the run_test command — null for test types that don't need one.
    val targetHost: String?,
    // The device decides success/failure and, on failure, why — api-service just stores and returns it.
    val errorMessage: String?,
    val ranMetrics: RanMetrics?,
    val icmpMetrics: IcmpMetrics?
)

data class TestResultResponse(
    val id: String,
    val testId: String,
    val deviceId: String,
    val status: String,
    val testType: String,
    val triggerType: String,
    val targetHost: String?,
    val errorMessage: String?,
    val createdAt: String
)
