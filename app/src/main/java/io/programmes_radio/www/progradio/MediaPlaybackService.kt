package io.programmes_radio.www.progradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.exoplayer2.*
import com.google.android.gms.analytics.HitBuilders.EventBuilder
import com.google.android.gms.analytics.Tracker
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import javax.net.ssl.*

// private const val MY_MEDIA_ROOT_ID = "media_root_id"
private const val MY_EMPTY_MEDIA_ROOT_ID = "empty_root_id"

private const val CHANNEL_ID = "com.android.progradio.channel_1"
private const val NOTIFICATION_ID = 666

private const val LISTENING_SOURCE = "android"

class MediaPlaybackService : MediaBrowserServiceCompat() {

    private var mTracker: Tracker? = null

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var stateBuilder: PlaybackStateCompat.Builder

    private val intentFilter = IntentFilter(ACTION_AUDIO_BECOMING_NOISY)

    private lateinit var afChangeListener: OnAudioFocusChangeListener
    private lateinit var playbackStateListener: Player.Listener
    private val myNoisyAudioStreamReceiver = BecomingNoisyReceiver()
//    private lateinit var myPlayerNotification: MediaStyleNotification
    private var player: ExoPlayer? = null

    private lateinit var audioFocusRequest: AudioFocusRequest

    private var listeningSessionStart: ZonedDateTime? = null

    private var radioCollection: List<Radio>? = null

    private var playerTimer: CountDownTimer? = null

    // ----------------------------------------------------------------------------------------------------

    inner class BecomingNoisyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_AUDIO_BECOMING_NOISY) {
                this@MediaPlaybackService.callback.onPause()
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()

        val application: ProgRadioApplication = applicationContext as ProgRadioApplication
        mTracker = application.getDefaultTracker()

        createNotificationChannel()

        // Create a MediaSessionCompat
        mediaSession = MediaSessionCompat(baseContext, "ProgRadioPlayback").apply {

            // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
            stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                            or PlaybackStateCompat.ACTION_PLAY
                            or PlaybackStateCompat.ACTION_PLAY_PAUSE
                            or PlaybackStateCompat.ACTION_PAUSE
                            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )

            setPlaybackState(stateBuilder.build())

            // MySessionCallback() has methods that handle callbacks from a media controller
            setCallback(callback)

            // Set the session's token so that client activities can communicate with it.
            setSessionToken(sessionToken)

            playbackStateListener = PlaybackStateListener()

            player = ExoPlayer.Builder(baseContext).build()
            player?.addListener(playbackStateListener)
/*            val trackSelector = DefaultTrackSelector(baseContext)
            player?.addAnalyticsListener(EventLogger(trackSelector))*/

            player?.setAudioAttributes(
                com.google.android.exoplayer2.audio.AudioAttributes.DEFAULT,
                false
            )

            player?.playWhenReady = true
        }

        afChangeListener =
            OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // Set volume level to desired levels
//                        play()
                        this@MediaPlaybackService.callback.onPlay()
                    }
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                        // You have audio focus for a short time
//                        play()
                    }
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                        // Play over existing audio
//                        play()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        this@MediaPlaybackService.callback.onPause()
//                        player?.reset()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Temporary loss of audio focus - expect to get it back - you can keep your resources around
                        this@MediaPlaybackService.callback.onPause()
                    }
/*                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Log.e(
                        TAG,
                        "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                    )*/
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
//        unregisterReceiver(myNoisyAudioStreamReceiver)
        mediaSession?.release()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {

        //unregister listeners
        //do any other cleanup if required

        //stop service
//        unregisterReceiver(myNoisyAudioStreamReceiver)
        player?.stop()
        mediaSession?.release()
        stopSelf()
    }

    // ----------------------------------------------------------------------------------------------------

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MY_EMPTY_MEDIA_ROOT_ID, null)

    }

    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        //  Browsing not allowed
        if (MY_EMPTY_MEDIA_ROOT_ID == parentMediaId) {
            result.sendResult(null)
            return
        }

        // should not be used
        val mediaItems = emptyList<MediaBrowserCompat.MediaItem>()
        result.sendResult(mediaItems)
    }

    // ----------------------------------------------------------------------------------------------------

    private val callback = object: MediaSessionCompat.Callback() {
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == "sendUpdate") {
                val intent = Intent("UpdatePlaybackStatus")
                intent.putExtra(
                    "radioCodeName", mediaSession?.controller?.metadata?.getString(
                        MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                    )
                )
                intent.putExtra("playbackState", PlaybackState.STATE_PLAYING)

                EventBus.getDefault().post(intent)
            }

            if (action == "setList") {
                val data = extras?.getString("list")
                if (data !== null) {
                    try {
                        radioCollection = Json.decodeFromString<List<Radio>>(data)
                         buildNotification(baseContext, player?.isPlaying == true)
                    } catch (e: Exception) {
                        // handler
                    }
                } else {
                    radioCollection = null;
                }
            }

            if (action == "setTimer") {
                val minutes = extras?.getInt("minutes", 0)
                if (minutes !== null && minutes > 0) {
                    Toast.makeText(baseContext, resources.getQuantityString(R.plurals.timer_start, minutes, minutes), Toast.LENGTH_SHORT).show()
                    playerTimer = object : CountDownTimer(minutes.toLong() * 60 * 1000, 60000) {
                        override fun onTick(millisUntilFinished: Long) {
                            val intent = Intent("UpdateTimerFinish")
                            intent.putExtra("finish", (millisUntilFinished / 1000 / 60).toInt() + 1)
                            EventBus.getDefault().post(intent)
                        }
                        override fun onFinish() {
                            if (player?.isPlaying == true) {
                                onStop()

                                Toast.makeText(baseContext, getString(R.string.timer_end), Toast.LENGTH_SHORT).show()
                            }

                            val intent = Intent("UpdateTimerFinish")
                            intent.putExtra("finish", 0)
                            EventBus.getDefault().post(intent)
                        }
                    }.start()
                } else if (playerTimer != null) {
                    playerTimer?.cancel()
                    playerTimer = null
                    Toast.makeText(baseContext, getString(R.string.timer_cancelled), Toast.LENGTH_SHORT).show()

                    val intent = Intent("UpdateTimerFinish")
//                    intent.putExtra("finish", null)
                    EventBus.getDefault().post(intent)
                }
            }

            if (action == "updateMetadata") {
                mediaSession!!.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_ID, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_ARTIST, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_ARTIST
                            )
                        )
//                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Test Album")
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_TITLE, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_TITLE
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_URI, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_URI
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_ART_URI, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_ART_URI
                            )
                        )
/*                        .putBitmap(
                            MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
                        )*/
                        .build()
                )

                buildNotification(baseContext, player?.isPlaying == true)
            }

            super.onCustomAction(action, extras)
        }

/*        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val mediaController = mediaSession?.controller
            
            if (mediaController !== null) {
*//*                val pbState = mediaController.playbackState.state

                if (pbState == PlaybackStateCompat.STATE_PLAYING) {
                    mediaController.transportControls.pause()
                    return true
                } else {
                    mediaController.transportControls.play()
                    return true
                }*//*

                return if (player?.isPlaying == true) {
                    mediaController.transportControls.pause()
                    true
                } else {
                    mediaController.transportControls.play()
                    true
                }
            }

            return super.onMediaButtonEvent(mediaButtonEvent)
        }*/

        override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
//            player?.reset()

            if (player?.isPlaying == true) {

                val currentPlayingUrl = mediaSession?.controller?.metadata?.getString(
                    MediaMetadataCompat.METADATA_KEY_MEDIA_URI
                )

                val newUrl = extras?.getString(
                    MediaMetadataCompat.METADATA_KEY_MEDIA_URI
                )

                if (!newUrl.equals(currentPlayingUrl)) {
                    sendListeningSession(
                        listeningSessionStart, mediaSession?.controller?.metadata?.getString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                        )
                    )

                    player?.stop()
                } else {
                    // just update notification

                    mediaSession!!.setMetadata(
                        MediaMetadataCompat.Builder()
                            .putString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_ID, extras?.getString(
                                    MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                                )
                            )
                            .putString(
                                MediaMetadataCompat.METADATA_KEY_ARTIST, extras?.getString(
                                    MediaMetadataCompat.METADATA_KEY_ARTIST
                                )
                            )
                            .putString(
                                MediaMetadataCompat.METADATA_KEY_TITLE, extras?.getString(
                                    MediaMetadataCompat.METADATA_KEY_TITLE
                                )
                            )
                            .putString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_URI, extras?.getString(
                                    MediaMetadataCompat.METADATA_KEY_MEDIA_URI
                                )
                            )
                            .putString(
                                MediaMetadataCompat.METADATA_KEY_ART_URI, extras?.getString(
                                    MediaMetadataCompat.METADATA_KEY_ART_URI
                                )
                            )
                            .build()
                    )

                    buildNotification(baseContext, true)

                    return
                }
            }

            val am = baseContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Request audio focus for playback, this registers the afChangeListener
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setOnAudioFocusChangeListener(afChangeListener)
                setAudioAttributes(AudioAttributes.Builder().run {
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    build()
                })
                build()
            }

            val result = am.requestAudioFocus(audioFocusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // Start the service
//                startService(Intent(baseContext, MediaBrowserService::class.java))
                startService(Intent(baseContext, MediaPlaybackService::class.java))

                // Set the session active (and update metadata and state)
                mediaSession?.isActive = true

                mediaSession!!.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_ID, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_ARTIST, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_ARTIST
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_TITLE, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_TITLE
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_URI, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_URI
                            )
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_ART_URI, extras?.getString(
                                MediaMetadataCompat.METADATA_KEY_ART_URI
                            )
                        )
                        .build()
                )

                val event = EventBuilder()
                    .setCategory("android")
                    .setAction("play")
                    .setValue(3)

                val label = extras?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)

                if (label != null) {
                    event.setLabel(label)
                }

                mTracker?.send(
                    event.build()
                )

                // Register BECOME_NOISY BroadcastReceiver
                registerReceiver(myNoisyAudioStreamReceiver, intentFilter)

                val mediaItem: MediaItem = MediaItem.fromUri(uri.toString())
                player?.setMediaItem(mediaItem)
                player?.prepare()
                player?.play()

                listeningSessionStart = ZonedDateTime.now()

                buildNotification(baseContext, true)

                val intent = Intent("UpdatePlaybackStatus")
                intent.putExtra(
                    "radioCodeName", extras?.getString(
                        MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                    )
                )
                intent.putExtra("playbackState", PlaybackState.STATE_PLAYING)

                EventBus.getDefault().post(intent)
            }
        }

        override fun onPlay() {
            if (player?.isPlaying == true) {
                return
            }

            player?.stop()

            val am = baseContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Request audio focus for playback, this registers the afChangeListener
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setOnAudioFocusChangeListener(afChangeListener)
                setAudioAttributes(AudioAttributes.Builder().run {
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    build()
                })
                build()
            }

            val result = am.requestAudioFocus(audioFocusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // Start the service
                try {
                    startService(Intent(baseContext, MediaPlaybackService::class.java))

                    // Set the session active (and update metadata and state)
                    mediaSession?.isActive = true

                    // Register BECOME_NOISY BroadcastReceiver
                    registerReceiver(myNoisyAudioStreamReceiver, intentFilter)

                    val uri = mediaSession?.controller?.metadata?.getString(
                        MediaMetadataCompat.METADATA_KEY_MEDIA_URI
                    )

                    if (uri !== null) {
                        val event = EventBuilder()
                            .setCategory("android")
                            .setAction("play")
                            .setValue(3)

                        val label = mediaSession?.controller?.metadata?.getString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                        )

                        if (label != null) {
                            event.setLabel(label)
                        }

                        mTracker?.send(
                            event.build()
                        )

                        val mediaItem: MediaItem = MediaItem.fromUri(uri)
                        player?.setMediaItem(mediaItem)
                        player?.prepare()
                        player?.play()

                        listeningSessionStart = ZonedDateTime.now()

                        buildNotification(baseContext, true)

                        val intent = Intent("UpdatePlaybackStatus")
                        intent.putExtra(
                            "radioCodeName", mediaSession?.controller?.metadata?.getString(
                                MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                            )
                        )
                        intent.putExtra("playbackState", PlaybackState.STATE_PLAYING)

                        EventBus.getDefault().post(intent)
                    }
                } catch (e: IllegalStateException) {
                    // handler
                }
            }
        }

       override fun onPause() {
           if (player?.isPlaying == true) {
//               val am = baseContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
               // Update metadata and state

               sendListeningSession(
                   listeningSessionStart, mediaSession?.controller?.metadata?.getString(
                       MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                   )
               )

               val event = EventBuilder()
                   .setCategory("android")
                   .setAction("pause")
                   .setValue(1)

               val label = mediaSession?.controller?.metadata?.getString(
                   MediaMetadataCompat.METADATA_KEY_MEDIA_ID
               )

               if (label != null) {
                   event.setLabel(label)
               }

               mTracker?.send(
                   event.build()
               )

               val radioCodeName = mediaSession?.controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)

               val intent = Intent("UpdatePlaybackStatus")
               intent.putExtra("radioCodeName", radioCodeName)
               intent.putExtra("playbackState", PlaybackState.STATE_PAUSED)

               EventBus.getDefault().post(intent)

               // pause the player (custom call)
               player?.pause()

               // unregister BECOME_NOISY BroadcastReceiver
               unregisterReceiver(myNoisyAudioStreamReceiver)

               // Take the service out of the foreground, retain the notification
               buildNotification(baseContext, false)
               stopForeground(false)
           }
       }

       override fun onSkipToPrevious() {
           if (radioCollection == null || radioCollection!!.size < 2) {
               return;
           }

           val currentRadioCodeName = mediaSession?.controller?.metadata?.getString(
               MediaMetadataCompat.METADATA_KEY_MEDIA_ID
           ) ?: return

           val currentIndex = radioCollection!!.indexOfFirst { it.codeName == currentRadioCodeName }
           var nextIndex = currentIndex - 1

           if (nextIndex < 0) {
               nextIndex = radioCollection!!.size - 1
           }

           val elem = radioCollection!![nextIndex]

           val streamUri = Uri.parse(elem.streamUrl)
           val extra = Bundle()
           extra.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, elem.codeName)
           extra.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, elem.name)
           extra.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, elem.streamUrl)
           extra.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, elem.pictureUrl)

           onPlayFromUri(streamUri, extra)
       }

       override fun onSkipToNext() {
           if (radioCollection == null || radioCollection!!.size < 2) {
               return;
           }

           val currentRadioCodeName = mediaSession?.controller?.metadata?.getString(
               MediaMetadataCompat.METADATA_KEY_MEDIA_ID
           ) ?: return

           val currentIndex = radioCollection!!.indexOfFirst {
               it.codeName == currentRadioCodeName
           }
           var nextIndex = currentIndex + 1

           if (nextIndex + 1 > radioCollection!!.size) {
               nextIndex = 0
           }

           val elem = radioCollection!![nextIndex]

           val streamUri = Uri.parse(elem.streamUrl)
           val extra = Bundle()
           extra.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, elem.codeName)
           extra.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, elem.name)
           extra.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, elem.streamUrl)
           extra.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, elem.pictureUrl)

           onPlayFromUri(streamUri, extra)
       }

       override fun onStop() {
           if (player?.isPlaying == true) {
               sendListeningSession(
                   listeningSessionStart, mediaSession?.controller?.metadata?.getString(
                       MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                   )
               )
           }

           val am = baseContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
           // Abandon audio focus
           am.abandonAudioFocusRequest(audioFocusRequest)
           unregisterReceiver(myNoisyAudioStreamReceiver)
           // Stop the service
           stopSelf()
           // Set the session inactive  (and update metadata and state)
           mediaSession?.isActive = false

            // stop the player (custom call)
           player?.stop()

           val event = EventBuilder()
               .setCategory("android")
               .setAction("pause")
               .setValue(1)

           val label = mediaSession?.controller?.metadata?.getString(
               MediaMetadataCompat.METADATA_KEY_MEDIA_ID
           )

           if (label != null) {
               event.setLabel(label)
           }

           mTracker?.send(
               event.build()
           )

           val intent = Intent("UpdatePlaybackStatus")
           intent.putExtra("radioCodeName", "rtl")
           intent.putExtra("playbackState", PlaybackState.STATE_STOPPED)

           EventBus.getDefault().post(intent)

           // Take the service out of the foreground
           stopForeground(false)
       }
    }

    // ----------------------------------------------------------------------------------------------------

    inner class PlaybackStateListener : Player.Listener {
/*
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateString: String
            stateString = when (playbackState) {
                ExoPlayer.STATE_IDLE -> "ExoPlayer.STATE_IDLE      -"
                ExoPlayer.STATE_BUFFERING -> "ExoPlayer.STATE_BUFFERING -"
                ExoPlayer.STATE_READY -> "ExoPlayer.STATE_READY     -"
                ExoPlayer.STATE_ENDED -> "ExoPlayer.STATE_ENDED     -"
                else -> "UNKNOWN_STATE             -"
            }
            Log.d("kikoo", "changed state to $stateString")
        }
*/

        override fun onPlayerError(error: PlaybackException) {
            this@MediaPlaybackService.sendListeningSession(
                listeningSessionStart, mediaSession?.controller?.metadata?.getString(
                    MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                )
            )

            Toast.makeText(baseContext, getString(R.string.playing_error), Toast.LENGTH_SHORT).show()

            val intent = Intent("UpdatePlaybackStatus")
            intent.putExtra(
                "radioCodeName", mediaSession?.controller?.metadata?.getString(
                    MediaMetadataCompat.METADATA_KEY_MEDIA_ID
                )
            )
            intent.putExtra("playbackState", PlaybackState.STATE_STOPPED)

            EventBus.getDefault().post(intent)
            this@MediaPlaybackService.buildNotification(baseContext, false)
        }
    }

    // ----------------------------------------------------------------------------------------------------

    private fun sendListeningSession(datetimeStart: ZonedDateTime?, radioCodeName: String?) = runBlocking {
        launch {
            if (datetimeStart !== null && radioCodeName !== null) {
                val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

                val baseUrl = if (BuildConfig.DEBUG) { MainActivity.BASE_URL_API_DEV } else { MainActivity.BASE_URL_API_PROD }
                val mURL = "$baseUrl/listening_session"

                val values = JSONObject()
                values.put("id", radioCodeName)
                values.put("date_time_start", datetimeStart.format(formatter))
                values.put("date_time_end", ZonedDateTime.now().format(formatter))
                values.put("source", LISTENING_SOURCE)

                var que: RequestQueue

                // ssl disabled for dev
                if (BuildConfig.DEBUG) {
                    que = Volley.newRequestQueue(baseContext, object : HurlStack() {
                        @Throws(IOException::class)
                        override fun createConnection(url: URL): HttpURLConnection {
                            val connection =
                                (URL(mURL).openConnection() as HttpsURLConnection).apply {
                                    sslSocketFactory = createSocketFactory(listOf("TLSv1.2"))
                                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                                    readTimeout = 5_000
                                }
                            return connection
                        }
                    })
                } else {
                    que = Volley.newRequestQueue(baseContext)
                }

                val req = JsonObjectRequest(Request.Method.POST, mURL, values,
                    { _ ->
//                    println(response["msg"].toString())

                    }, { _ ->
                        // println(error.toString())
                    })
                req.retryPolicy = DefaultRetryPolicy(
                    0,
                    DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                    DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
                )
                que.add(req)
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val name = getString(R.string.channel_name)
        val descriptionText = getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(context: Context, play: Boolean) {
        // Get the session's metadata
        val controller = mediaSession?.controller
        val mediaMetadata = controller?.metadata
        val description = mediaMetadata?.description

        if (mediaSession != null && description != null) {
            val intent = Intent(
                applicationContext,
                MainActivity::class.java
            ) // Here pass your activity where you want to redirect.

            intent.action = Intent.ACTION_MAIN
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val contentIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID).apply {
                // Add the metadata for the currently playing track
                setOnlyAlertOnce(true)
                setContentTitle(description.title)
                setContentText(description.subtitle)
                setSubText(description.description)

                // Enable launching the player by clicking the notification
                setContentIntent(contentIntent)

                // Make the transport controls visible on the lockscreen
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                // Add an app icon and set its accent color
                // Be careful about the color
                setSmallIcon(R.drawable.ic_brand_logo)
                color = ContextCompat.getColor(context, R.color.primary)

                if (radioCollection != null && radioCollection!!.size > 1) {
                    addAction(
                        NotificationCompat.Action(
                            //                    R.drawable.pause,
                            android.R.drawable.ic_media_previous,
                            //                    getString(R.string.pause),
                            getString(R.string.previous),
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                context,
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                            )
                        )
                    )
                }

                if (play) {
                    // Stop the service when the notification is swiped away
/*                    val closeIntent = Intent(applicationContext, NotificationDismissedReceiver::class.java)

                    setDeleteIntent(
                        PendingIntent.getBroadcast(
                            this@MediaPlaybackService,
                            NOTIFICATION_ID, closeIntent, 0
                        )
                    )*/
                    setOngoing(false)

                    // Add a pause button
                    addAction(
                        NotificationCompat.Action(
//                    R.drawable.pause,
                            android.R.drawable.ic_media_pause,
//                    getString(R.string.pause),
                            getString(R.string.pause),
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                context,
                                PlaybackStateCompat.ACTION_PAUSE
                            )
                        )
                    )
                } else {
                    // Add a pause button
                    addAction(
                        NotificationCompat.Action(
                            //                    R.drawable.pause,
                            android.R.drawable.ic_media_play,
                            //                    getString(R.string.pause),
                            getString(R.string.play),
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                context,
                                PlaybackStateCompat.ACTION_PLAY
                            )
                        )
                    )
                }

                if (radioCollection != null && radioCollection!!.size > 1) {
                    addAction(
                        NotificationCompat.Action(
                            //                    R.drawable.pause,
                            android.R.drawable.ic_media_next,
                            //                    getString(R.string.pause),
                            getString(R.string.next),
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                context,
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                            )
                        )
                    )
                }

                // Take advantage of MediaStyle features

                if (radioCollection != null && radioCollection!!.size > 1) {
                    setStyle(
                        MediaStyle()
                            .setMediaSession(mediaSession?.sessionToken)
                            .setShowActionsInCompactView(0,1,2))
                } else {
                    setStyle(
                        MediaStyle()
                            .setMediaSession(mediaSession?.sessionToken)
                            .setShowActionsInCompactView(0))
                }

                    // Add a cancel button
/*                        .setShowCancelButton(true)
                        .setCancelButtonIntent(
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                context,
                                PlaybackStateCompat.ACTION_STOP
                            )
                        )*/
            }

            if (mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI) !== null) {
                val baseUrl = if (BuildConfig.DEBUG) { MainActivity.BASE_URL_DEV } else { MainActivity.BASE_URL_PROD }
                val bitmapUrl = mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI)
                if (bitmapUrl !== null) {
                    LoadImageTask(builder, play, baseUrl + bitmapUrl)
                }
            } else {
                // Display the notification and place the service in the foreground
                builder.setLargeIcon(null)
                startForeground(NOTIFICATION_ID, builder.build())
                if (!play) {
                    stopForeground(false)
                }
            }
        }
    }

    private fun LoadImageTask(
        builder: NotificationCompat.Builder?,
        isPlaying: Boolean,
        vararg p0: String?) {
        var bitmap: Bitmap? = null
        var myExecutor = Executors.newSingleThreadExecutor()
        var myHandler = Handler(Looper.getMainLooper())

        if (p0.isEmpty()) {
            return
        }

        myExecutor.execute {
            try {
                val connection = if (BuildConfig.DEBUG) {
                    (URL(p0[0]).openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = createSocketFactory(listOf("TLSv1.2"))
                        hostnameVerifier = HostnameVerifier { _, _ -> true }
                        readTimeout = 5_000
                    }
                } else {
                    val url = URL(p0[0])
                    url.openConnection() as HttpURLConnection
                }

                connection.doInput = true
                connection.connect()
                val input = connection.inputStream
                bitmap = BitmapFactory.decodeStream(input)

                myHandler.post {
                    if (bitmap !== null && builder !== null) {
                        builder.setLargeIcon(bitmap)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                        } else {
                            startForeground(NOTIFICATION_ID, builder.build())
                        }

                        if (!isPlaying) {
                            stopForeground(false)
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                if (builder != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            builder.build(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, builder.build())
                    }
                    if (!isPlaying) {
                        stopForeground(false)
                    }
                }
                bitmap = null
            }
        }

    }

    // disable ssl cert for dev
    private fun createSocketFactory(protocols: List<String>) =
        SSLContext.getInstance(protocols[0]).apply {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) =
                    Unit

                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) =
                    Unit
            })
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory
}
