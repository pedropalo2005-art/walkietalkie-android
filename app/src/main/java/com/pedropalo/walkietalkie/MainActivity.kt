package com.pedropalo.walkietalkie

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pedropalo.walkietalkie.databinding.ActivityMainBinding
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AgoraManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var agoraManager: AgoraManager
    private var vibrator: Vibrator? = null

    private var channelList = listOf<RadioChannel>()
    private var currentChannelIndex = 0
    private var activeAgoraAccount: AgoraAccount? = null
    private var isTransmitting = false
    private val unlockedChannels = mutableSetOf<String>()

    private fun loadUnlockedChannels() {
        val prefs = getSharedPreferences("walkie_unlocked_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("unlocked_ids", emptySet()) ?: emptySet()
        unlockedChannels.addAll(saved)
    }

    private fun saveUnlockedChannel(channelId: String) {
        unlockedChannels.add(channelId)
        val prefs = getSharedPreferences("walkie_unlocked_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("unlocked_ids", unlockedChannels).apply()
    }

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

        // Keep screen awake while app is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Show over lockscreen if desired
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        initVibrator()
        loadUnlockedChannels()
        agoraManager = AgoraManager(this, this)
        agoraManager.initEngine(SupabaseClient.DEFAULT_ACCOUNT.appId, SupabaseClient.DEFAULT_ACCOUNT.appCertificate)

        checkAndRequestPermissions()
        checkBatteryOptimization()
        setupListeners()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Move task to back instead of destroying Activity so radio keeps listening in background!
        moveTaskToBack(true)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
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
            binding.tvChannelDesc.text = "Sincronizando canales..."
            channelList = SupabaseClient.getChannels()
            val account = SupabaseClient.getActiveAgoraAccount()
            activeAgoraAccount = account

            // Anonymized UI: only shows CUENTA 1, CUENTA 2, etc.
            binding.tvServerInfo.text = "POOL: CUENTA ${account.id}"
            agoraManager.initEngine(account.appId, account.appCertificate)

            if (channelList.isNotEmpty()) {
                trySelectChannel(0)
            } else {
                binding.tvChannelTitle.text = "CANAL 1 - GENERAL"
                binding.tvChannelType.text = "🌐 PÚBLICO"
                binding.tvChannelType.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_public))
                agoraManager.joinChannel("canal-1")
                WalkieTalkieService.startService(this@MainActivity, "Canal 1 - General")
            }
        }
    }

    private fun trySelectChannel(index: Int) {
        if (channelList.isEmpty()) return
        val ch = channelList[index]
        if (ch.isPrivate && !unlockedChannels.contains(ch.id)) {
            showEnterPinDialog(ch, index)
        } else {
            updateChannelUi(index)
        }
    }

    private fun updateChannelUi(index: Int) {
        if (channelList.isEmpty()) return
        currentChannelIndex = index
        val ch = channelList[index]
        binding.tvChannelTitle.text = ch.name.uppercase()
        binding.tvChannelDesc.text = ch.description

        if (ch.isPrivate) {
            binding.tvChannelType.text = "🔒 PRIVADO"
            binding.tvChannelType.setTextColor(ContextCompat.getColor(this, R.color.badge_private))
        } else {
            binding.tvChannelType.text = "🌐 PÚBLICO"
            binding.tvChannelType.setTextColor(ContextCompat.getColor(this, R.color.badge_public))
        }

        agoraManager.switchChannel(ch.id)
        WalkieTalkieService.startService(this, ch.name)
    }

    private fun showEnterPinDialog(channel: RadioChannel, targetIndex: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_enter_pin, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPinDialogTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvPinDialogSubtitle)
        val etPin = dialogView.findViewById<EditText>(R.id.etPinEntry)
        val tvError = dialogView.findViewById<TextView>(R.id.tvPinError)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPin)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmPin)

        tvTitle.text = "🔒 ${channel.name.uppercase()}"
        tvSubtitle.text = "Introduce el PIN de 4 dígitos para ingresar a este canal:"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val inputPin = etPin.text.toString().trim()
            if (inputPin == channel.pin) {
                saveUnlockedChannel(channel.id)
                dialog.dismiss()
                updateChannelUi(targetIndex)
                Toast.makeText(this, "Canal ${channel.name} desbloqueado", Toast.LENGTH_SHORT).show()
            } else {
                tvError.visibility = View.VISIBLE
                etPin.text.clear()
                vibrateFeedback(100)
            }
        }

        dialog.show()
    }

    private fun showCreateChannelDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_channel, null)
        val etName = dialogView.findViewById<EditText>(R.id.etChannelName)
        val rgPrivacy = dialogView.findViewById<RadioGroup>(R.id.rgPrivacy)
        val rbPrivate = dialogView.findViewById<RadioButton>(R.id.rbPrivate)
        val llPinContainer = dialogView.findViewById<LinearLayout>(R.id.llPinContainer)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelCreate)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmCreate)

        rgPrivacy.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPrivate) {
                llPinContainer.visibility = View.VISIBLE
            } else {
                llPinContainer.visibility = View.GONE
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "Escribe un nombre para el canal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isPrivate = rbPrivate.isChecked
            val pin = etPin.text.toString().trim()

            if (isPrivate && (pin.length != 4 || !pin.all { it.isDigit() })) {
                Toast.makeText(this, "El PIN debe tener exactamente 4 números", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnConfirm.isEnabled = false
            btnConfirm.text = "Creando..."

            lifecycleScope.launch {
                val result = SupabaseClient.createChannel(name, isPrivate, if (isPrivate) pin else null)
                result.onSuccess { created ->
                    saveUnlockedChannel(created.id)
                    channelList = SupabaseClient.getChannels()
                    val newIndex = channelList.indexOfFirst { it.id == created.id }.coerceAtLeast(0)
                    updateChannelUi(newIndex)
                    Toast.makeText(this@MainActivity, "Canal '${created.name}' creado exitosamente", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }.onFailure { err ->
                    btnConfirm.isEnabled = true
                    btnConfirm.text = "Crear Canal"
                    Toast.makeText(this@MainActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun showChannelListDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_channel_list, null)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvChannelCount)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchChannel)
        val llItems = dialogView.findViewById<LinearLayout>(R.id.llChannelItems)
        val btnCreate = dialogView.findViewById<Button>(R.id.btnModalCreateChannel)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseChannelList)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun populate(query: String = "") {
            llItems.removeAllViews()
            val filtered = if (query.isBlank()) {
                channelList
            } else {
                channelList.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
                }
            }
            tvCount.text = "${filtered.size} canales"

            for (ch in filtered) {
                val rowView = layoutInflater.inflate(R.layout.item_channel_row, llItems, false)
                val tvRowName = rowView.findViewById<TextView>(R.id.tvRowChannelName)
                val tvRowDesc = rowView.findViewById<TextView>(R.id.tvRowChannelDesc)
                val tvRowBadge = rowView.findViewById<TextView>(R.id.tvRowBadge)
                val tvRowStatus = rowView.findViewById<TextView>(R.id.tvRowStatus)
                val rowLayout = rowView.findViewById<LinearLayout>(R.id.llChannelRow)

                tvRowName.text = ch.name
                tvRowDesc.text = ch.description

                if (ch.isPrivate) {
                    val isUnlocked = unlockedChannels.contains(ch.id)
                    tvRowBadge.text = if (isUnlocked) "🔓 PRIVADO" else "🔒 PRIVADO (PIN)"
                    tvRowBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_private))
                } else {
                    tvRowBadge.text = "🌐 PÚBLICO"
                    tvRowBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_public))
                }

                val isCurrent = channelList.isNotEmpty() && channelList[currentChannelIndex].id == ch.id
                if (isCurrent) {
                    tvRowStatus.visibility = View.VISIBLE
                    tvRowStatus.text = "En Sintonía ●"
                } else {
                    tvRowStatus.visibility = View.GONE
                }

                rowLayout.setOnClickListener {
                    dialog.dismiss()
                    val targetIndex = channelList.indexOfFirst { it.id == ch.id }
                    if (targetIndex >= 0) {
                        trySelectChannel(targetIndex)
                    }
                }

                llItems.addView(rowView)
            }
        }

        populate()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                populate(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnCreate.setOnClickListener {
            dialog.dismiss()
            showCreateChannelDialog()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        // Prev and Next Channel buttons
        binding.btnPrevChannel.setOnClickListener {
            if (channelList.isNotEmpty()) {
                val newIndex = if (currentChannelIndex > 0) currentChannelIndex - 1 else channelList.size - 1
                trySelectChannel(newIndex)
            }
        }

        binding.btnNextChannel.setOnClickListener {
            if (channelList.isNotEmpty()) {
                val newIndex = (currentChannelIndex + 1) % channelList.size
                trySelectChannel(newIndex)
            }
        }

        // Channel list modal & create channel shortcuts
        binding.btnChannelList.setOnClickListener {
            showChannelListDialog()
        }

        binding.btnCreateChannel.setOnClickListener {
            showCreateChannelDialog()
        }

        binding.cardLcd.setOnClickListener {
            showChannelListDialog()
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

    override fun onResume() {
        super.onResume()
        agoraManager.ensureLoudspeaker()
    }

    // Physical Button PTT: Hold Volume Down / Headset / PTT button to speak
    // Intercepted via dispatchKeyEvent so Android OS NEVER reduces or mutes the volume
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == 286 /* KEYCODE_PTT */) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // repeatCount == 0 ensures only the first press triggers, avoiding OS auto-repeat
                    if (event.repeatCount == 0 && !isTransmitting) {
                        onPttPressed()
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (isTransmitting) {
                        onPttReleased()
                    }
                }
            }
            return true // Consume event COMPLETELY: Android will never reduce the volume!
        }
        return super.dispatchKeyEvent(event)
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

    override fun onRemoteUserJoined(uid: Int) {
        Toast.makeText(this, "Usuario $uid se unió al canal", Toast.LENGTH_SHORT).show()
        binding.tvChannelDesc.text = "Usuario $uid conectado en canal"
    }

    override fun onRemoteUserOffline(uid: Int) {
        binding.tvChannelDesc.text = "Usuario $uid desconectado"
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

    override fun onConnectionChanged(state: Int, reason: Int) {
        when (state) {
            Constants.CONNECTION_STATE_CONNECTING -> {
                binding.tvFrequency.text = "CONECTANDO..."
                binding.tvFrequency.setTextColor(ContextCompat.getColor(this, R.color.radio_lcd_dim))
            }
            Constants.CONNECTION_STATE_CONNECTED -> {
                binding.tvFrequency.text = "ONLINE [UID:${agoraManager.myUid}]"
                binding.tvFrequency.setTextColor(ContextCompat.getColor(this, R.color.radio_lcd_text))
            }
            Constants.CONNECTION_STATE_FAILED -> {
                binding.tvFrequency.text = "ERR CONEX ($reason)"
                binding.tvFrequency.setTextColor(Color.RED)
            }
            Constants.CONNECTION_STATE_RECONNECTING -> {
                binding.tvFrequency.text = "RECONECTANDO..."
            }
        }
    }

    override fun onEngineError(errCode: Int, message: String) {
        binding.tvFrequency.text = message
        binding.tvFrequency.setTextColor(Color.RED)

        // Automatic failover when Agora quota error or suspension occurs
        if (errCode == 110 || errCode == 109 || errCode == 101 || errCode == 102) {
            binding.tvServerInfo.text = "ROTANDO CUENTA AGORA..."
            lifecycleScope.launch {
                val currentId = activeAgoraAccount?.id ?: 1
                val nextAccount = SupabaseClient.rotateToNextAccount(currentId)
                if (nextAccount != null) {
                    activeAgoraAccount = nextAccount
                    binding.tvServerInfo.text = "POOL: CUENTA ${nextAccount.id}"
                    agoraManager.initEngine(nextAccount.appId, nextAccount.appCertificate)
                    if (channelList.isNotEmpty()) {
                        agoraManager.joinChannel(channelList[currentChannelIndex].id)
                    }
                    Toast.makeText(this@MainActivity, "Cuenta rotada automáticamente: CUENTA ${nextAccount.id}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Keep WalkieTalkieService alive in background so incoming radio audio continues playing
        // when Android reclaims Activity memory while running other apps (WhatsApp, etc.).
        // To turn off the radio, use the "Apagar Radio" action in the persistent notification.
    }
}
