package me.sithiramunasinghe.flutter.flutter_radio_player.core

import android.content.BroadcastReceiver
import android.content.Context
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.Nullable
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
//import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.broadcastActionName
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.broadcastChangedMetaDataName
import me.sithiramunasinghe.flutter.flutter_radio_player.R
import me.sithiramunasinghe.flutter.flutter_radio_player.core.enums.PlaybackStatus
import java.util.logging.Logger

class StreamingCore : Service(), AudioManager.OnAudioFocusChangeListener, MetadataOutput {

    private var logger = Logger.getLogger(StreamingCore::javaClass.name)

    private val iBinder = LocalBinder()
    private lateinit var playbackStatus: PlaybackStatus
    private lateinit var dataSourceFactory: DefaultDataSourceFactory
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var playerEvents: Player.EventListener

    // context
    private val context = this
    private val broadcastIntent = Intent(broadcastActionName)
    private val broadcastMetaDataIntent = Intent(broadcastChangedMetaDataName)

    // class instances
    private var player: SimpleExoPlayer? = null
    private var telephonyManager: TelephonyManager? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentSong = ""

    inner class LocalBinder : Binder() {
        internal val service: StreamingCore
            get() = this@StreamingCore
    }

    /*===========================
     *        Player APIS
     *===========================
     */

    fun play() {
        logger.info("playing audio $player ...")
        requestAudioFocus()
        player?.playWhenReady = true
    }

    fun pause() {
        logger.info("pausing audio...")
        removeAudioFocus()
        player?.playWhenReady = false
    }

    fun isPlaying(): Boolean {
        val isPlaying = this.playbackStatus == PlaybackStatus.PLAYING
        logger?.info("is playing status: $isPlaying")
        return isPlaying
    }

    fun stop() {
        logger.info("stopping audio $player ...")
        removeAudioFocus()
        player?.stop()
        stopSelf()
    }

    fun setVolume(volume: Double) {
        logger.info("Changing volume to : $volume")
        player?.volume = volume.toFloat()
    }

    fun setUrl(streamUrl: String, playWhenReady: Boolean) {
        logger.info("ReadyPlay status: $playWhenReady")
        logger.info("Set stream URL: $streamUrl")
        player?.prepare(buildMediaSource(dataSourceFactory, streamUrl))
        player?.playWhenReady = playWhenReady
    }

    fun currentSongTitle(): String {
        return currentSong
    }

    override fun onCreate() {
        super.onCreate()

        player = SimpleExoPlayer.Builder(context).build()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                            AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(context).build()
        }

        localBroadcastManager = LocalBroadcastManager.getInstance(context)
        logger.info("LocalBroadCastManager Received...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        logger.info("Firing up service. (onStartCommand)...")

        // get details
        val initialTitle = intent!!.getStringExtra("initialTitle")
        val subTitle = intent.getStringExtra("subTitle")
        val streamUrl = intent.getStringExtra("streamUrl")
        val playWhenReady = intent.getStringExtra("playWhenReady") == "true"

        dataSourceFactory = DefaultDataSourceFactory(context, Util.getUserAgent(context, initialTitle))

        val audioSource = buildMediaSource(dataSourceFactory, streamUrl)

        val playerEvents = object : Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {

                playbackStatus = when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_LOADING)
                        PlaybackStatus.LOADING
                    }
                    Player.STATE_IDLE -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_STOPPED)
                        PlaybackStatus.STOPPED
                    }
                    Player.STATE_READY -> {
                        setPlayWhenReady(playWhenReady)
                    }
                    else -> setPlayWhenReady(playWhenReady)
                }

                logger.info("onPlayerStateChanged: $playbackStatus")
            }

            override fun onPlayerError(error: ExoPlaybackException) {
                pushEvent(FLUTTER_RADIO_PLAYER_ERROR)
                playbackStatus = PlaybackStatus.ERROR
                error.printStackTrace()
            }
        }

        // set exo player configs
        player?.let {
            it.addListener(playerEvents)
            it.addMetadataOutput(this)
            it.playWhenReady = playWhenReady
            it.prepare(audioSource)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return iBinder
    }

    override fun onDestroy() {
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        stop()
        player?.removeListener(playerEvents)
        player?.release()
        unregisterReceiver(becomingNoisyReceiver)

        super.onDestroy()
    }

    private val becomingNoisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pause()
        }
    }

    private val phoneStateListener: PhoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK
                    || state == TelephonyManager.CALL_STATE_RINGING) {
                pause()
            }
        }
    }

    override fun onAudioFocusChange(audioFocus: Int) {
        when (audioFocus) {

            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 0.8f
                play()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                stop()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    stop()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying()) {
                    player?.volume = 0.1f
                }
            }
        }
    }

    override fun onMetadata(metadata: Metadata) {
        logger.info("onMetadata: " + metadata.toString())

        currentSong = ""
        for (n in 0 until metadata.length()) {
            when (val md = metadata[n]) {
                is com.google.android.exoplayer2.metadata.icy.IcyInfo -> {
                    if (currentSong.isEmpty()) {
                        currentSong = md.title?.trim() ?: ""
                    }
                }
            }
        }
        localBroadcastManager.sendBroadcast(broadcastMetaDataIntent.putExtra("meta_data", currentSong))
    }

    private fun requestAudioFocus() {

        val mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                mAudioManager.requestAudioFocus(it)
            }
        } else {
            mAudioManager.requestAudioFocus(context,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun removeAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusRequest?.let {
                mAudioManager.abandonAudioFocusRequest(it)
            }
        }
    }

    /**
     * Push events to local broadcaster service.
     */
    private fun pushEvent(eventName: String) {
        logger.info("Pushing Event: $eventName")
        localBroadcastManager.sendBroadcast(broadcastIntent.putExtra("status", eventName))
    }

    /**
     * Build the media source depending of the URL content type.
     */
    private fun buildMediaSource(dataSourceFactory: DefaultDataSourceFactory, streamUrl: String): MediaSource {

        val uri = Uri.parse(streamUrl)

        return when (val type = Util.inferContentType(uri)) {
            C.TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            C.TYPE_SS -> SsMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            C.TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun setPlayWhenReady(playWhenReady: Boolean): PlaybackStatus {
        return if (playWhenReady) {
            pushEvent(FLUTTER_RADIO_PLAYER_PLAYING)
            PlaybackStatus.PLAYING
        } else {
            pushEvent(FLUTTER_RADIO_PLAYER_PAUSED)
            PlaybackStatus.PAUSED
        }
    }

    private fun getSongArtist(songInfo: String): String {
        // get song artist
        val prefixIndex = songInfo.indexOf(" - ")
        if (prefixIndex == -1)
            return ""
        return songInfo.substring(0, prefixIndex).trim()
    }

    private fun getSongTitle(songInfo: String): String {
        // get song title
        val prefixIndex = songInfo.indexOf(" - ")
        if (prefixIndex == -1)
            return songInfo
        return songInfo.substring(prefixIndex + 2, songInfo.length).trim()
    }

}
