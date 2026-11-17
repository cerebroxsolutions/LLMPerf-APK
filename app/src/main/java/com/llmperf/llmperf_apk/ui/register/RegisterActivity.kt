package com.llmperf.llmperf_apk.ui.register

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.llmperf.llmperf_apk.R
import com.llmperf.llmperf_apk.data.local.DevicePrefs
import com.llmperf.llmperf_apk.data.metrics.DeviceMetricsCollector
import com.llmperf.llmperf_apk.data.metrics.IcmpPingTest
import com.llmperf.llmperf_apk.data.metrics.RanMetricsCollector
import com.llmperf.llmperf_apk.data.model.RanMetrics
import com.llmperf.llmperf_apk.data.remote.RetrofitClient
import com.llmperf.llmperf_apk.data.repository.DeviceRepository
import com.llmperf.llmperf_apk.databinding.ActivityRegisterBinding
import com.llmperf.llmperf_apk.ws.WebSocketManager
import com.llmperf.llmperf_apk.ws.WsConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val RAN_METRICS_REFRESH_INTERVAL_MS = 5000L

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var devicePrefs: DevicePrefs

    /** True when a previously-registered device id was found in prefs on startup. */
    private var isReturningDevice = false

    private val viewModel: RegisterViewModel by viewModels {
        RegisterViewModel.Factory(
            repository = DeviceRepository(RetrofitClient.apiService),
            webSocketManager = WebSocketManager(RetrofitClient.okHttpClient),
            ranMetricsCollector = RanMetricsCollector(applicationContext),
            deviceMetricsCollector = DeviceMetricsCollector(applicationContext),
            icmpPingTest = IcmpPingTest()
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.refreshRanMetrics()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        devicePrefs = DevicePrefs(this)

        bindMetricLabels()

        binding.btnRegister.setOnClickListener {
            viewModel.register(binding.etName.text.toString().trim(), Build.MODEL)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wsState.collect { wsState -> renderWs(wsState) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ranMetricsState.collect { ranState -> renderRanMetrics(ranState) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.testExecutionState.collect { testState -> renderTestExecution(testState) }
            }
        }

        val allGranted = RanMetricsCollector.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            permissionLauncher.launch(RanMetricsCollector.REQUIRED_PERMISSIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    viewModel.refreshRanMetrics()
                    delay(RAN_METRICS_REFRESH_INTERVAL_MS)
                }
            }
        }

        val savedName = devicePrefs.deviceName
        val savedId = devicePrefs.deviceId
        if (savedName != null && savedId != null) {
            isReturningDevice = true
            binding.etName.visibility = View.GONE
            binding.btnRegister.visibility = View.GONE
            viewModel.connectExisting(savedId, savedName)
        }
    }

    private fun bindMetricLabels() {
        binding.rowConnection.tvLabel.setText(R.string.label_connection)
        binding.rowCarrier.tvLabel.setText(R.string.label_carrier)
        binding.rowNetworkType.tvLabel.setText(R.string.label_network_type)
        binding.rowSignalLevel.tvLabel.setText(R.string.label_signal_level)
        binding.rowRsrp.tvLabel.setText(R.string.label_rsrp)
        binding.rowRsrq.tvLabel.setText(R.string.label_rsrq)
        binding.rowSnr.tvLabel.setText(R.string.label_snr)
        binding.rowEnb.tvLabel.setText(R.string.label_enb)
        binding.rowSsRsrp.tvLabel.setText(R.string.label_ss_rsrp)
        binding.rowSsRsrq.tvLabel.setText(R.string.label_ss_rsrq)
        binding.rowSsSinr.tvLabel.setText(R.string.label_ss_sinr)
    }

    private fun render(state: RegisterUiState) {
        binding.tvStatus.text = when (state) {
            is RegisterUiState.Idle -> getString(R.string.status_idle)
            is RegisterUiState.Registering -> getString(R.string.status_registering)
            is RegisterUiState.FetchingToken -> getString(R.string.status_fetching_token)
            is RegisterUiState.Connected -> getString(R.string.status_connected, state.deviceId)
            is RegisterUiState.Error -> getString(R.string.status_error, state.message)
        }

        if (!isReturningDevice) {
            val inputEnabled = state !is RegisterUiState.Registering && state !is RegisterUiState.FetchingToken
            binding.etName.isEnabled = inputEnabled
            binding.btnRegister.isEnabled = inputEnabled
        }

        if (state is RegisterUiState.Connected) {
            devicePrefs.deviceName = state.name
            devicePrefs.deviceId = state.deviceId
            binding.etName.visibility = View.GONE
            binding.btnRegister.visibility = View.GONE
            binding.tvDeviceInfo.visibility = View.VISIBLE
            binding.tvDeviceInfo.text = getString(R.string.device_info_format, state.name, state.deviceId)
        }

        if (state is RegisterUiState.Error) {
            Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderRanMetrics(state: RanMetricsUiState) {
        val isSuccess = state is RanMetricsUiState.Success
        binding.layoutRanMetricsGrid.visibility = if (isSuccess) View.VISIBLE else View.GONE
        binding.tvRanMetricsStatus.visibility = if (isSuccess) View.GONE else View.VISIBLE

        binding.tvRanMetricsStatus.text = when (state) {
            is RanMetricsUiState.Idle -> getString(R.string.ran_metrics_idle)
            is RanMetricsUiState.Loading -> getString(R.string.ran_metrics_loading)
            is RanMetricsUiState.PermissionRequired -> getString(R.string.ran_metrics_permission_required)
            is RanMetricsUiState.Error -> getString(R.string.ran_metrics_error, state.message)
            is RanMetricsUiState.Success -> ""
        }

        if (state is RanMetricsUiState.Success) {
            renderRanMetricsGrid(state.metrics)
        }
    }

    private fun renderRanMetricsGrid(metrics: RanMetrics) {
        fun fmt(value: Double?) = value?.let { "%.1f".format(it) } ?: getString(R.string.na)

        binding.rowConnection.tvValue.text = metrics.connectionLabel ?: getString(R.string.na)
        binding.rowCarrier.tvValue.text = metrics.carrierName ?: getString(R.string.na)
        binding.rowNetworkType.tvValue.text = metrics.networkType
        binding.rowSignalLevel.tvValue.text = "${metrics.signalLevel}/4"
        binding.rowRsrp.tvValue.text = fmt(metrics.rsrp)
        binding.rowRsrq.tvValue.text = fmt(metrics.rsrq)
        binding.rowSnr.tvValue.text = fmt(metrics.snr)
        binding.rowEnb.tvValue.text = metrics.enb?.toString() ?: getString(R.string.na)
        binding.rowSsRsrp.tvValue.text = fmt(metrics.ssRsrp)
        binding.rowSsRsrq.tvValue.text = fmt(metrics.ssRsrq)
        binding.rowSsSinr.tvValue.text = fmt(metrics.ssSinr)
    }

    private fun renderWs(state: WsConnectionState) {
        val (textRes, bgColorRes, textColorRes) = when (state) {
            is WsConnectionState.Idle -> Triple(R.string.ws_pill_idle, R.color.divider, R.color.text_secondary)
            is WsConnectionState.Connecting -> Triple(R.string.ws_pill_connecting, R.color.warning_bg, R.color.warning)
            is WsConnectionState.Connected -> Triple(R.string.ws_pill_connected, R.color.success_bg, R.color.success)
            is WsConnectionState.Closed, is WsConnectionState.Failure ->
                Triple(R.string.ws_pill_reconnecting, R.color.danger_bg, R.color.danger)
        }
        binding.tvWsStatusPill.setText(textRes)
        applyPillColors(binding.tvWsStatusPill, bgColorRes, textColorRes)
    }

    private fun renderTestExecution(state: TestExecutionUiState) {
        val (text, colorRes, showProgress) = when (state) {
            is TestExecutionUiState.Idle ->
                Triple(getString(R.string.test_status_idle), R.color.text_secondary, false)
            is TestExecutionUiState.Received ->
                Triple(getString(R.string.test_status_received, testTypeLabel(state.testType)), R.color.info, true)
            is TestExecutionUiState.Running ->
                Triple(getString(R.string.test_status_running, testTypeLabel(state.testType)), R.color.info, true)
            is TestExecutionUiState.Sent -> {
                val successful = state.status == "success"
                val textRes = if (successful) R.string.test_status_sent_success else R.string.test_status_sent_failure
                val color = if (successful) R.color.success else R.color.warning
                Triple(getString(textRes, testTypeLabel(state.testType)), color, false)
            }
            is TestExecutionUiState.Failed ->
                Triple(getString(R.string.test_status_failed, testTypeLabel(state.testType), state.message), R.color.danger, false)
        }

        binding.tvTestStatus.text = text
        val color = ContextCompat.getColor(this, colorRes)
        binding.tvTestStatus.setTextColor(color)
        (binding.dotTestStatus.background.mutate() as GradientDrawable).setColor(color)
        binding.progressTestRunning.visibility = if (showProgress) View.VISIBLE else View.GONE
    }

    private fun testTypeLabel(testType: String): String = when (testType) {
        "icmp" -> getString(R.string.test_type_icmp)
        "device_info_ran" -> getString(R.string.test_type_device_info_ran)
        else -> testType
    }

    private fun applyPillColors(view: android.widget.TextView, bgColorRes: Int, textColorRes: Int) {
        view.setTextColor(ContextCompat.getColor(this, textColorRes))
        (view.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(this, bgColorRes))
    }
}
