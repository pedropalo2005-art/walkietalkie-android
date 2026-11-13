package com.pedropalo.walkietalkie

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import io.agora.media.RtcTokenBuilder
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
        fun onRemoteUserTalking(isTalking: Boolean, uid: Int)
        fun onEngineError(errCode: Int)
    }

    private var rtcEngine: RtcEngine? = null
    private var currentAppId: String = ""
    private var currentAppCert: String = ""
    private var currentChannel: String? = null
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
            mainHandler.post {
                ensureLoudspeaker()
                channel?.let { listener.onJoinedChannel(it, uid) }
            }
        }

        override fun onUserMuteAudio(uid: Int, muted: Boolean) {
            mainHandler.post {
                listener.onRemoteUserTalking(!muted, uid)
            }
        }

        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            mainHandler.post {
                val isSpeaking = (state == Constants.REMOTE_AUDIO_STATE_DECODING)
                listener.onRemoteUserTalking(isSpeaking, uid)
            }
        }

        override fun onError(err: Int) {
            mainHandler.post {
                listener.onEngineError(err)
            }
        }
    }

    fun initEngine(appId: String, appCert: String) {
        destroyEngine()
        currentAppId = appId
        currentAppCert = appCert
        try {
            val config = RtcEngineConfig().apply {
                mContext = context
                mAppId = appId
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
        }
    }

    fun joinChannel(channelName: String) {
        currentChannel = channelName

        // Generate dynamic token if certificate is provided
        val token = if (currentAppCert.isNotBlank()) {
            val expireTimestamp = (System.currentTimeMillis() / 1000 + 86400).toInt()
            RtcTokenBuilder().buildTokenWithUid(
                currentAppId,
                currentAppCert,
                channelName,
                myUid,
                RtcTokenBuilder.Role.Role_Publisher,
                expireTimestamp
            )
        } else {
            ""
        }

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            autoSubscribeAudio = true
            publishMicrophoneTrack = false // idle
        }

        rtcEngine?.joinChannel(token, channelName, myUid, options)
    }

    fun switchChannel(newChannelName: String) {
        rtcEngine?.leaveChannel()
        joinChannel(newChannelName)
    }

    fun startTransmitting() {
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
        }
        rtcEngine?.updateChannelMediaOptions(options)
        rtcEngine?.muteLocalAudioStream(false)
        playMicBeep()
    }

    fun stopTransmitting() {
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = false
            autoSubscribeAudio = true
        }
        rtcEngine?.updateChannelMediaOptions(options)
        rtcEngine?.muteLocalAudioStream(true)
        playRogerBeep()
    }

    private fun ensureLoudspeaker() {
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
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null
    }
}
