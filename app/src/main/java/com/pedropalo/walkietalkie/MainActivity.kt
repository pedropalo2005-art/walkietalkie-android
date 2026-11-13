package com.pedropalo.walkietalkie

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pedropalo.walkietalkie.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AgoraManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var agoraManager: AgoraManager
    private var vibrator: Vibrator? = null

    private var channelList = listOf<RadioChannel>()
    private var currentChannelIndex = 0
    private var activeAgoraAccount: AgoraAccount? = null
    private var isTransmitting = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            setupWalkieTalkie()
        } else {
            Toast.makeText(this, "Se requiere permiso de micrófono para transmitir.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initVibrator()
        agoraManager = AgoraManager(this, this)

        checkAndRequestPermissions()
        setupListeners()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            setupWalkieTalkie()
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupWalkieTalkie() {
        lifecycleScope.launch {
            binding.tvChannelDesc.text = "Consultando canales en Supabase..."
            channelList = SupabaseClient.getChannels()
            activeAgoraAccount = SupabaseClient.getActiveAgoraAccount()

            if (activeAgoraAccount != null) {
                binding.tvServerInfo.text = "POOL: ${activeAgoraAccount!!.name}"
                agoraManager.initEngine(activeAgoraAccount!!.appId, activeAgoraAccount!!.appCertificate)

                if (channelList.isNotEmpty()) {
                    updateChannelUi(0)
                } else {
                    binding.tvChannelTitle.text = "CANAL 1 - GENERAL"
                    agoraManager.joinChannel("canal-1")
                }
            } else {
                binding.tvServerInfo.text = "ERROR: Sin cuentas de Agora"
                Toast.makeText(this@MainActivity, "No hay cuentas activas en Supabase agora_pool", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateChannelUi(index: Int) {
        if (channelList.isEmpty()) return
        currentChannelIndex = index
        val ch = channelList[index]
        binding.tvChannelTitle.text = ch.name.uppercase()
        binding.tvChannelDesc.text = ch.description
        agoraManager.switchChannel(ch.id)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        // Prev and Next Channel buttons
        binding.btnPrevChannel.setOnClickListener {
            if (channelList.isNotEmpty()) {
                val newIndex = if (currentChannelIndex > 0) currentChannelIndex - 1 else channelList.size - 1
                updateChannelUi(newIndex)
            }
        }

        binding.btnNextChannel.setOnClickListener {
            if (channelList.isNotEmpty()) {
                val newIndex = (currentChannelIndex + 1) % channelList.size
                updateChannelUi(newIndex)
            }
        }

        // PTT Touch Listener: Hold to speak, release to mute
        binding.btnPttArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onPttPressed()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onPttReleased()
                    true
                }
                else -> false
            }
        }
    }

    private fun onPttPressed() {
        if (isTransmitting) return
        isTransmitting = true

        vibrateFeedback(50)
        agoraManager.startTransmitting()

        // Visual feedback
        binding.cardPtt.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ptt_active_base))
        binding.tvPttText.text = "EN VIVO"
        binding.tvInstruction.text = getString(R.string.ptt_transmitting)
        binding.tvInstruction.setTextColor(ContextCompat.getColor(this, R.color.ptt_active_base))

        // Red TX LED ON
        binding.ledTx.setBackgroundColor(ContextCompat.getColor(this, R.color.led_tx_active))
    }

    private fun onPttReleased() {
        if (!isTransmitting) return
        isTransmitting = false

        vibrateFeedback(25)
        agoraManager.stopTransmitting()

        // Reset visual feedback
        binding.cardPtt.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ptt_idle_base))
        binding.tvPttText.text = "PTT"
        binding.tvInstruction.text = getString(R.string.ptt_idle)
        binding.tvInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        // Red TX LED OFF
        binding.ledTx.setBackgroundColor(ContextCompat.getColor(this, R.color.led_off))
    }

    private fun vibrateFeedback(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    // Agora Callbacks
    override fun onJoinedChannel(channel: String, uid: Int) {
        binding.tvFrequency.text = "ONLINE [UID:$uid]"
        binding.tvFrequency.setTextColor(ContextCompat.getColor(this, R.color.radio_lcd_text))
    }

    override fun onRemoteUserTalking(isTalking: Boolean, uid: Int) {
        if (isTalking) {
            // RX Green LED ON
            binding.ledRx.setBackgroundColor(ContextCompat.getColor(this, R.color.led_rx_active))
            binding.tvChannelDesc.text = "Usuario $uid transmitiendo..."
        } else {
            // RX Green LED OFF
            binding.ledRx.setBackgroundColor(ContextCompat.getColor(this, R.color.led_off))
            if (channelList.isNotEmpty()) {
                binding.tvChannelDesc.text = channelList[currentChannelIndex].description
            }
        }
    }

    override fun onEngineError(errCode: Int) {
        // Automatic failover when Agora quota error or suspension occurs
        if (errCode == 110 || errCode == 109 || errCode == 101 || errCode == 102) {
            binding.tvServerInfo.text = "ROTANDO CUENTA AGORA..."
            lifecycleScope.launch {
                val currentId = activeAgoraAccount?.id ?: 1
                val nextAccount = SupabaseClient.rotateToNextAccount(currentId)
                if (nextAccount != null) {
                    activeAgoraAccount = nextAccount
                    binding.tvServerInfo.text = "POOL: ${nextAccount.name}"
                    agoraManager.initEngine(nextAccount.appId, nextAccount.appCertificate)
                    if (channelList.isNotEmpty()) {
                        agoraManager.joinChannel(channelList[currentChannelIndex].id)
                    }
                    Toast.makeText(this@MainActivity, "Cuenta rotada automáticamente: ${nextAccount.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        agoraManager.destroyEngine()
    }
}
