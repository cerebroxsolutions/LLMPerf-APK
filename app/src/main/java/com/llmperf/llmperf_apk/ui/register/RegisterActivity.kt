package com.llmperf.llmperf_apk.ui.register

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.llmperf.llmperf_apk.R
import com.llmperf.llmperf_apk.data.local.DevicePrefs
import com.llmperf.llmperf_apk.data.remote.RetrofitClient
import com.llmperf.llmperf_apk.data.repository.DeviceRepository
import com.llmperf.llmperf_apk.databinding.ActivityRegisterBinding
import com.llmperf.llmperf_apk.ws.WebSocketManager
import com.llmperf.llmperf_apk.ws.WsConnectionState
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var devicePrefs: DevicePrefs

    /** True when a previously-registered device id was found in prefs on startup. */
    private var isReturningDevice = false

    private val viewModel: RegisterViewModel by viewModels {
        RegisterViewModel.Factory(
            repository = DeviceRepository(RetrofitClient.apiService),
            webSocketManager = WebSocketManager(RetrofitClient.okHttpClient)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        devicePrefs = DevicePrefs(this)

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

        val savedName = devicePrefs.deviceName
        val savedId = devicePrefs.deviceId
        if (savedName != null && savedId != null) {
            isReturningDevice = true
            binding.etName.visibility = View.GONE
            binding.btnRegister.visibility = View.GONE
            viewModel.connectExisting(savedId, savedName)
        }
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
        }
    }

    private fun renderWs(state: WsConnectionState) {
        binding.tvWebSocketStatus.text = when (state) {
            is WsConnectionState.Idle -> ""
            is WsConnectionState.Connecting -> getString(R.string.ws_status_connecting)
            is WsConnectionState.Connected -> getString(R.string.ws_status_connected, state.serverResponse)
            is WsConnectionState.Closed -> getString(R.string.ws_status_closed, state.code, state.reason)
            is WsConnectionState.Failure -> getString(R.string.ws_status_failure, state.message)
        }
    }
}
