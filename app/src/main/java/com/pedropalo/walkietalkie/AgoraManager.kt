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

    interface Listener {
        fun onJoinedChannel(channel: String, uid: Int)
        fun onRemoteUserJoined(uid: Int)
        fun onRemoteUserOffline(uid: Int)
        fun onRemoteUserTalking(isTalking: Boolean, uid: Int)
        fun onConnectionChanged(state: Int, reason: Int)
        fun onEngineError(errCode: Int)
    }

    private var rtcEngine: RtcEngine? = null
    private var currentAppId: String = ""
    private var currentAppCert: String = ""
    var currentChannel: String? = null
        private set
    var isJoined: Boolean = false
        private set

    private var toneGen: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Unique random UID for this device
    val myUid: Int = Random.nextInt(100000, 999999)

    init {
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
            mainHandler.post {
                listener.onEngineError(err)
            }
        }
    }

    fun initEngine(appId: String, appCert: String) {
        // Destroy existing instance only if one exists
        if (rtcEngine != null) {
            destroyEngine()
        }

        currentAppId = appId.trim()
        currentAppCert = appCert.trim()

        try {
            val config = RtcEngineConfig().apply {
                mContext = context.applicationContext
                mAppId = currentAppId
                mEventHandler = rtcEventHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            }
            rtcEngine = RtcEngine.create(config)

            rtcEngine?.setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_DEFAULT
            )
            rtcEngine?.enableAudio()
            rtcEngine?.enableLocalAudio(true)
            rtcEngine?.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            rtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)
            rtcEngine?.setEnableSpeakerphone(true)
            rtcEngine?.enableAudioVolumeIndication(200, 3, true)

            // Start with microphone muted until PTT is pressed
            rtcEngine?.muteLocalAudioStream(true)
            ensureLoudspeaker()
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post {
                listener.onEngineError(-1001) // Engine creation failure
            }
        }
    }

    fun joinChannel(channelName: String) {
        if (currentChannel == channelName && isJoined) {
            return
        }
        currentChannel = channelName

        if (rtcEngine == null) {
            initEngine(currentAppId, currentAppCert)
        }

        // Generate dynamic token
        var token = ""
        if (currentAppCert.isNotBlank()) {
            try {
                // Try Token 007 first
                token = RtcTokenBuilder2().buildTokenWithUid(
                    currentAppId,
                    currentAppCert,
                    channelName,
                    myUid,
                    RtcTokenBuilder2.Role.ROLE_PUBLISHER,
                    86400,
                    86400
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (token.isBlank()) {
                try {
                    // Fallback to Token 006
                    val expireTs = (System.currentTimeMillis() / 1000 + 86400).toInt()
                    token = RtcTokenBuilder().buildTokenWithUid(
                        currentAppId,
                        currentAppCert,
                        channelName,
                        myUid,
                        RtcTokenBuilder.Role.Role_Publisher,
                        expireTs
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            autoSubscribeAudio = true
            publishMicrophoneTrack = true
        }

        val res = rtcEngine?.joinChannel(token, channelName, myUid, options) ?: -1002
        if (res != 0) {
            mainHandler.post {
                listener.onEngineError(res)
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
        rtcEngine?.muteLocalAudioStream(false)
        playMicBeep()
    }

    fun stopTransmitting() {
        rtcEngine?.muteLocalAudioStream(true)
        playRogerBeep()
    }

    fun ensureLoudspeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            rtcEngine?.setEnableSpeakerphone(true)
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
