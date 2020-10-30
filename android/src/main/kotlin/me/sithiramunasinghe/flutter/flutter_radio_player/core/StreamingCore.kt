package me.sithiramunasinghe.flutter.flutter_radio_player.core

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.broadcastActionName
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.broadcastChangedMetaDataName
import me.sithiramunasinghe.flutter.flutter_radio_player.core.enums.PlaybackStatus
import java.util.logging.Logger

class StreamingCore : Service(), AudioManager.OnAudioFocusChangeListener, MetadataOutput {

    private var logger = Logger.getLogger(StreamingCore::javaClass.name)

    private val iBinder = LocalBinder()
    private lateinit var initialTitle: String
    private lateinit var subTitle: String
    private lateinit var streamUrl: String
    private lateinit var playbackStatus: PlaybackStatus
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var playerEvents: Player.EventListener
    private lateinit var player: SimpleExoPlayer

    // context
    private val context = this
    private val broadcastIntent = Intent(broadcastActionName)
    private val broadcastMetaDataIntent = Intent(broadcastChangedMetaDataName)

    // class instances
    private var telephonyManager: TelephonyManager? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var notification: Notification? = null

    private var packageIntentName = ""
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

        val dataSourceFactory = DefaultDataSourceFactory(context, Util.getUserAgent(context, initialTitle))
        val audioSource = buildMediaSource(dataSourceFactory, streamUrl)

        player.stop()
        player.prepare(audioSource)
        player.playWhenReady = true
    }

    fun pause() {
        logger.info("pausing audio...")
        removeAudioFocus()
        player.playWhenReady = false
    }

    fun isPlaying(): Boolean {
        val isPlaying = this.playbackStatus == PlaybackStatus.PLAYING
        logger.info("is playing status: $isPlaying")
        return isPlaying
    }

    fun stop() {
        logger.info("stopping audio $player ...")
        removeAudioFocus()
        player.stop()
        stopSelf()
    }

    fun setVolume(volume: Double) {
        logger.info("Changing volume to : $volume")
        player.volume = volume.toFloat()
    }

    fun setUrl(url: String) {
        logger.info("Set stream URL: $url")
        streamUrl = url
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

        playbackStatus = PlaybackStatus.IDLE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        logger.info("Firing up service. (onStartCommand)...")

        // get details
        initialTitle = intent!!.getStringExtra("initialTitle")
        subTitle = intent!!.getStringExtra("subTitle")
        streamUrl = intent!!.getStringExtra("streamUrl")
        packageIntentName = intent!!.getStringExtra("packageName")

        playerEvents = object : Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {

                playbackStatus = when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_LOADING)
                        PlaybackStatus.LOADING
                    }
                    Player.STATE_ENDED -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_STOPPED)
                        PlaybackStatus.STOPPED
                    }
                    Player.STATE_READY -> {
                        setPlayWhenReady(playWhenReady)
                    }
                    else -> PlaybackStatus.IDLE
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
        player.addListener(playerEvents)
        player.addMetadataOutput(this)

        createNotificationChannel()
        notification = buildNotification(
            initialTitle, subTitle, packageIntentName
        )
        startForeground(Companion.NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return iBinder
    }

    override fun onDestroy() {
        kill()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        kill()
        super.onTaskRemoved(rootIntent)
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
                player.volume = 0.8f
                play()
                logger.info("AudioManager::AUDIO_FOCUS_GAIN")
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                stop()
                logger.info("AudioManager::AUDIO_FOCUS_LOSS")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    pause()
                }
                logger.info("AudioManager::AUDIO_FOCUS_LOSS_TRANSIENT")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying()) {
                    player.volume = 0.1f
                }
                logger.info("AudioManager::AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK")
            }
        }
    }

    override fun onMetadata(metadata: Metadata) {
        logger.info("onMetadata: $metadata")

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

        notification = buildNotification(
            getSongArtist(currentSong), getSongTitle(currentSong), packageIntentName
        )?.apply {
            notify(this)
        }

        localBroadcastManager.sendBroadcast(broadcastMetaDataIntent.putExtra("meta_data", currentSong))
    }

    /**
     * Stop service
     */
    private fun kill() {
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        player.stop()
        player.removeListener(playerEvents)
        player.release()
        removeAudioFocus()
        unregisterReceiver(becomingNoisyReceiver)
        stopForeground(true)
    }

    /**
     * Handle audio focus
     */
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

    /**
     * Build notification
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    Companion.CHANNEL_ID,
                    Companion.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(
            title: String,
            description: String,
            packageIntentName: String): Notification?
    {
        var notificationIntent: Intent? = null

        if (packageIntentName.isNotEmpty() && packageName.isNotEmpty()) {
            notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val notificationBuilder = NotificationCompat.Builder(this, Companion.CHANNEL_ID)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setWhen(System.currentTimeMillis())
                .setColorized(true)
                .setContentText(description)
                .setContentTitle(title)
                .setAutoCancel(true)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentIntent(pendingIntent)
        return notificationBuilder.build()
    }

    private fun notify(notification: Notification) {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(Companion.NOTIFICATION_ID, notification)
    }

    /**
     * Split metadata
     */
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

    companion object {
        const val CHANNEL_ID = "PLAYER_CHANNEL_ID"
        const val CHANNEL_NAME = "RADIO_STREAMING_SERVICE"
        const val NOTIFICATION_ID = 1602246405
    }

}
