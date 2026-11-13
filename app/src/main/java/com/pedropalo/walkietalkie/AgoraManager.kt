package com.pedropalo.walkietalkie

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.ChannelMediaOptions

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
    private var currentChannel: String? = null
    private var toneGen: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun initEngine(appId: String) {
        destroyEngine()
        try {
            val config = RtcEngineConfig().apply {
                mContext = context
                mAppId = appId
                mEventHandler = rtcEventHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            }
            rtcEngine = RtcEngine.create(config)

            // Configure audio profile optimized for walkie-talkie / voice
            rtcEngine?.setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_DEFAULT
            )
            rtcEngine?.enableAudio()
            rtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)
            rtcEngine?.enableAudioVolumeIndication(200, 3, true)
            // Start muted by default until PTT button is pressed
            rtcEngine?.muteLocalAudioStream(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun joinChannel(channelName: String) {
        currentChannel = channelName
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            autoSubscribeAudio = true
            publishMicrophoneTrack = true
        }
        // Using null token or empty string for testing mode / dynamic token
        rtcEngine?.joinChannel("", channelName, 0, options)
    }

    fun switchChannel(newChannelName: String) {
        rtcEngine?.leaveChannel()
        joinChannel(newChannelName)
    }

    fun startTransmitting() {
        // Unmute microphone to broadcast voice instantly
        rtcEngine?.muteLocalAudioStream(false)
        playMicBeep()
    }

    fun stopTransmitting() {
        // Mute microphone
        rtcEngine?.muteLocalAudioStream(true)
        playRogerBeep()
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
            // Tactical Roger Beep (high chirp then low chirp)
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
