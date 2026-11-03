package com.llmperf.llmperf_apk.data.model

data class RanMetrics(
    val carrierName: String?,
    val connectionLabel: String?,
    val networkType: String,
    val signalLevel: Int,
    // LTE anchor cell signal (present whenever an LTE cell is serving, incl. NSA anchor).
    val rsrp: Double?,
    val rsrq: Double?,
    val snr: Double?,
    val enb: Int?,
    // NR secondary/standalone cell signal (present in NSA once the NR leg attaches, or SA).
    val ssRsrp: Double?,
    val ssRsrq: Double?,
    val ssSinr: Double?,
    // Device's GPS fix at collection time, reverse-geocoded — null when location
    // permission isn't granted, GPS is off, or no fix/address was available yet.
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val state: String?,
    val country: String?
)
