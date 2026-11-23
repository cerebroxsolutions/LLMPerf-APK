package com.llmperf.llmperf_apk.ui.register

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.llmperf.llmperf_apk.R
import com.llmperf.llmperf_apk.data.local.DevicePrefs
import com.llmperf.llmperf_apk.data.metrics.DeviceMetricsCollector
import com.llmperf.llmperf_apk.data.metrics.HlsPlaybackTest
import com.llmperf.llmperf_apk.data.metrics.IcmpPingTest
import com.llmperf.llmperf_apk.data.metrics.RanMetricsCollector
import com.llmperf.llmperf_apk.data.model.RanMetrics
import com.llmperf.llmperf_apk.data.remote.RetrofitClient
import com.llmperf.llmperf_apk.data.repository.DeviceRepository
import com.llmperf.llmperf_apk.databinding.ActivityRegisterBinding
import com.llmperf.llmperf_apk.ws.WebSocketManager
import com.llmperf.llmperf_apk.ws.WsConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
            icmpPingTest = IcmpPingTest(),
            hlsPlaybackTest = HlsPlaybackTest(applicationContext)
        )
    }

    /** Player currently rendering, and the surface it renders into, so it can be detached on destroy. */
    private var boundHlsPlayer: ExoPlayer? = null
    private var boundHlsSurface: TextureView? = null

    /** Whether landscape fullscreen is currently applied to the window. */
    private var landscapeApplied = false

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

        // Collected for the activity's whole life (not just STARTED) so the
        // player stays bound while briefly backgrounded; ExoPlayer itself
        // tracks the TextureView's surface coming and going.
        lifecycleScope.launch {
            combine(viewModel.hlsPlayer, viewModel.hlsFullscreen) { player, fullscreen -> player to fullscreen }
                .collect { (player, fullscreen) -> renderHlsPlayback(player, fullscreen) }
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

    /**
     * Puts the live HLS player on the card's small surface, or — while the
     * test's fullscreen timer is on — the full-window landscape surface.
     * ExoPlayer switches surfaces mid-playback without restarting.
     */
    private fun renderHlsPlayback(player: ExoPlayer?, fullscreen: Boolean) {
        val surface = if (fullscreen) binding.hlsFullscreenVideoView else binding.hlsVideoView
        if (player != null) {
            // A released player has already dropped its surface callbacks,
            // so only a still-live, different player needs detaching.
            boundHlsPlayer?.takeIf { it !== player }?.let { old -> boundHlsSurface?.let(old::clearVideoTextureView) }
            if (player !== boundHlsPlayer || surface !== boundHlsSurface) player.setVideoTextureView(surface)
        }
        boundHlsPlayer = player
        boundHlsSurface = if (player != null) surface else null

        val showFullscreen = player != null && fullscreen
        binding.hlsVideoView.visibility = if (player != null && !fullscreen) View.VISIBLE else View.GONE
        binding.hlsFullscreenContainer.visibility = if (showFullscreen) View.VISIBLE else View.GONE
        // Screen-off playback would skew FPS/dropped-frame metrics.
        binding.root.keepScreenOn = player != null
        applyLandscape(showFullscreen)
    }

    /** Landscape + hidden system bars on; restores the app's normal orientation off. */
    private fun applyLandscape(on: Boolean) {
        if (on == landscapeApplied) return
        landscapeApplied = on
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        // The player lives in the ViewModel and may outlive this activity —
        // detach so it doesn't hold this view hierarchy.
        boundHlsSurface?.let { surface -> boundHlsPlayer?.clearVideoTextureView(surface) }
        boundHlsPlayer = null
        boundHlsSurface = null
        super.onDestroy()
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
        "hls_streaming" -> getString(R.string.test_type_hls_streaming)
        "device_info_ran" -> getString(R.string.test_type_device_info_ran)
        else -> testType
    }

    private fun applyPillColors(view: android.widget.TextView, bgColorRes: Int, textColorRes: Int) {
        view.setTextColor(ContextCompat.getColor(this, textColorRes))
        (view.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(this, bgColorRes))
    }
}
