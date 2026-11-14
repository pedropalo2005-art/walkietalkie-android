package com.pedropalo.walkietalkie

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import io.agora.media.RtcTokenBuilder
import io.agora.media.RtcTokenBuilder2
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.ChannelMediaOptions
import kotlin.random.Random

class AgoraManager(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        var instance: AgoraManager? = null
            private set
    }

    interface Listener {
        fun onJoinedChannel(channel: String, uid: Int)
        fun onRemoteUserJoined(uid: Int)
        fun onRemoteUserOffline(uid: Int)
        fun onRemoteUserTalking(isTalking: Boolean, uid: Int)
        fun onConnectionChanged(state: Int, reason: Int)
        fun onEngineError(errCode: Int, message: String)
    }

    private var rtcEngine: RtcEngine? = null
    var currentAppId: String = ""
        private set
    var currentAppCert: String = ""
        private set
    var currentChannel: String? = null
        private set
    var isJoined: Boolean = false
        private set
    var isTransmitting: Boolean = false
        private set
    var onTransmissionStateChangedListener: ((Boolean) -> Unit)? = null

    private var toneGen: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Unique random UID for this device
    val myUid: Int = Random.nextInt(100000, 999999)

    init {
        instance = this
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            isJoined = true
            mainHandler.post {
                ensureLoudspeaker()
                channel?.let { listener.onJoinedChannel(it, uid) }
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            mainHandler.post {
                ensureLoudspeaker()
                listener.onRemoteUserJoined(uid)
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            mainHandler.post {
                listener.onRemoteUserOffline(uid)
            }
        }

        override fun onUserMuteAudio(uid: Int, muted: Boolean) {
            mainHandler.post {
                listener.onRemoteUserTalking(!muted, uid)
            }
        }

        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            mainHandler.post {
                val isSpeaking = (state == Constants.REMOTE_AUDIO_STATE_DECODING || state == Constants.REMOTE_AUDIO_STATE_STARTING)
                listener.onRemoteUserTalking(isSpeaking, uid)
            }
        }

        override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
            mainHandler.post {
                speakers?.forEach { speaker ->
                    if (speaker.uid != 0 && speaker.uid != myUid && speaker.volume > 5) {
                        listener.onRemoteUserTalking(true, speaker.uid)
                    }
                }
            }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            mainHandler.post {
                if (state == Constants.CONNECTION_STATE_CONNECTED) {
                    isJoined = true
                    ensureLoudspeaker()
                } else if (state == Constants.CONNECTION_STATE_DISCONNECTED || state == Constants.CONNECTION_STATE_FAILED) {
                    isJoined = false
                }
                listener.onConnectionChanged(state, reason)
            }
        }

        override fun onError(err: Int) {
            val desc = try { RtcEngine.getErrorDescription(Math.abs(err)) } catch (e: Exception) { "" }
            mainHandler.post {
                listener.onEngineError(err, "Agora onError $err: $desc")
            }
        }
    }

    fun initEngine(appId: String, appCert: String): Boolean {
        currentAppId = appId.trim()
        currentAppCert = appCert.trim()

        if (rtcEngine != null) {
            return true
        }

        // Attempt 1: Config builder
        try {
            val config = RtcEngineConfig().apply {
                mContext = context
                mAppId = currentAppId
                mEventHandler = rtcEventHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            }
            rtcEngine = RtcEngine.create(config)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        // Attempt 2: Direct static factory
        if (rtcEngine == null) {
            try {
                rtcEngine = RtcEngine.create(context, currentAppId, rtcEventHandler)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        // Attempt 3: Application context
        if (rtcEngine == null) {
            try {
                rtcEngine = RtcEngine.create(context.applicationContext, currentAppId, rtcEventHandler)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        if (rtcEngine == null) {
            val msg = "Fallo RtcEngine.create (AppID='$currentAppId')"
            mainHandler.post {
                listener.onEngineError(-1001, msg)
            }
            return false
        }

        try {
            rtcEngine?.setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_DEFAULT
            )
            rtcEngine?.enableAudio()
            rtcEngine?.enableLocalAudio(true)
            rtcEngine?.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            rtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)
            rtcEngine?.setEnableSpeakerphone(true)
            rtcEngine?.adjustPlaybackSignalVolume(150)
            rtcEngine?.adjustRecordingSignalVolume(120)
            rtcEngine?.enableAudioVolumeIndication(200, 3, true)

            // Start with microphone muted until PTT is pressed
            rtcEngine?.muteLocalAudioStream(true)
            ensureLoudspeaker()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }

    fun joinChannel(channelName: String) {
        currentChannel = channelName

        if (rtcEngine == null) {
            val ok = initEngine(currentAppId, currentAppCert)
            if (!ok || rtcEngine == null) {
                mainHandler.post {
                    listener.onEngineError(-1002, "rtcEngine es null (AppID='$currentAppId')")
                }
                return
            }
        }

        // Generate dynamic token
        var token = ""
        var tokenErr = ""
        if (currentAppCert.isNotBlank()) {
            try {
                token = RtcTokenBuilder2().buildTokenWithUid(
                    currentAppId,
                    currentAppCert,
                    channelName,
                    myUid,
                    RtcTokenBuilder2.Role.ROLE_PUBLISHER,
                    86400,
                    86400
                )
            } catch (e: Throwable) {
                tokenErr = "T007: ${e.message}"
            }

            if (token.isBlank()) {
                try {
                    val expireTs = (System.currentTimeMillis() / 1000 + 86400).toInt()
                    token = RtcTokenBuilder().buildTokenWithUid(
                        currentAppId,
                        currentAppCert,
                        channelName,
                        myUid,
                        RtcTokenBuilder.Role.Role_Publisher,
                        expireTs
                    )
                } catch (e: Throwable) {
                    tokenErr += " | T006: ${e.message}"
                }
            }
        }

        if (token.isBlank() && currentAppCert.isNotBlank()) {
            mainHandler.post {
                listener.onEngineError(-1003, "Token vacio ($tokenErr)")
            }
            return
        }

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            autoSubscribeAudio = true
            publishMicrophoneTrack = false
        }

        val res = rtcEngine?.joinChannel(token, channelName, myUid, options) ?: -1004
        if (res != 0) {
            val desc = try { RtcEngine.getErrorDescription(Math.abs(res)) } catch(e: Exception) { "" }
            val err = "ERR JOIN $res: $desc (tkLen=${token.length})"
            mainHandler.post {
                listener.onEngineError(res, err)
            }
        }
    }

    fun switchChannel(newChannelName: String) {
        if (currentChannel == newChannelName && isJoined) {
            return
        }
        if (isJoined) {
            isJoined = false
            rtcEngine?.leaveChannel()
        }
        joinChannel(newChannelName)
    }

    fun startTransmitting() {
        if (isTransmitting) return
        isTransmitting = true
        val options = ChannelMediaOptions().apply {
            publishMicrophoneTrack = true
        }
        rtcEngine?.updateChannelMediaOptions(options)
        rtcEngine?.muteLocalAudioStream(false)
        playMicBeep()
        mainHandler.post {
            onTransmissionStateChangedListener?.invoke(true)
        }
    }

    fun stopTransmitting() {
        if (!isTransmitting) return
        isTransmitting = false
        val options = ChannelMediaOptions().apply {
            publishMicrophoneTrack = false
        }
        rtcEngine?.updateChannelMediaOptions(options)
        rtcEngine?.muteLocalAudioStream(true)
        playRogerBeep()
        mainHandler.post {
            onTransmissionStateChangedListener?.invoke(false)
        }
    }

    fun ensureLoudspeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true

            // Restore voice call & media volume so user always hears incoming voice loudly
            val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)

            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

            rtcEngine?.setEnableSpeakerphone(true)
            rtcEngine?.adjustPlaybackSignalVolume(150)
            rtcEngine?.adjustRecordingSignalVolume(120)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playMicBeep() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playRogerBeep() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_SUP_RADIO_ACK, 120)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun destroyEngine() {
        if (instance == this) {
            instance = null
        }
        isJoined = false
        currentChannel = null
        try {
            rtcEngine?.leaveChannel()
            RtcEngine.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        rtcEngine = null
    }
}
