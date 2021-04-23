package io.programmes_radio.www.progradio

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.JavascriptInterface
import androidx.lifecycle.LifecycleObserver

class WebAppInterface(val context: Context) : LifecycleObserver {

    private lateinit var mediaBrowser: MediaBrowserCompat

    private var needUpdate = false

    // ----------------------------------------------------------------------------------------------------

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            // cond prevents crash for some reason
            if(mediaBrowser.isConnected) {
                // Get the token for the MediaSession
                mediaBrowser.sessionToken.also { token ->

                    // Create a MediaControllerCompat
                    val mediaController = MediaControllerCompat(
                        context, // Context
                        token
                    )

                    // Save the controller
                    val activity = context as Activity
                    MediaControllerCompat.setMediaController(activity, mediaController)
                }

                // Finish building the UI
                buildTransportControls()

                if (needUpdate) {
                    getstate()
                    needUpdate = false
                }
            }

        }

        override fun onConnectionSuspended() {
            // The Service has crashed. Disable transport controls until it automatically reconnects
        }

        override fun onConnectionFailed() {
            // The Service has refused our connection
        }
    }

    init {
        mediaBrowser = MediaBrowserCompat(
            context,
            ComponentName(context, MediaPlaybackService::class.java),
            connectionCallbacks,
            null // optional Bundle
        )

        mediaBrowser.connect()
    }

    fun reconnect() {
        mediaBrowser = MediaBrowserCompat(
            context,
            ComponentName(context, MediaPlaybackService::class.java),
            connectionCallbacks,
            null // optional Bundle
        )

        mediaBrowser.connect()
    }

    private var controllerCallback = object : MediaControllerCompat.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) { }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) { }
    }

    fun mediaSessionDisconnect() {
        val activity = context as Activity
        MediaControllerCompat.getMediaController(activity)?.unregisterCallback(controllerCallback)
        mediaBrowser.disconnect()
    }

    fun buildTransportControls() {
        val activity = context as Activity
        val mediaController = MediaControllerCompat.getMediaController(activity)

        // Grab the view for the play/pause button
/*        playPause = findViewById<ImageView>(R.id.play_pause).apply {
            setOnClickListener {
                // Since this is a play/pause button, you'll need to test the current state
                // and choose the action accordingly

                val pbState = mediaController.playbackState.state
                if (pbState == PlaybackStateCompat.STATE_PLAYING) {
                    mediaController.transportControls.pause()
                } else {
                    mediaController.transportControls.play()
                }
            }
        }*/

        // Display the initial state
//        val metadata = mediaController.metadata
//        val pbState = mediaController.playbackState

        // Register a Callback to stay in sync
        mediaController.registerCallback(controllerCallback)
    }

    /*
     * -------------------------------------------------- WebView events --------------------------------------------------
     */

    @JavascriptInterface
    fun play(radioCodeName: String, radioName: String, streamUrl: String, showTitle: String?, pictureUrl: String?) {
        if (mediaBrowser.isConnected) {
            val streamUri = Uri.parse(streamUrl)
            val extra = Bundle()
            extra.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, radioCodeName)
            extra.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, radioName)
            extra.putString(MediaMetadataCompat.METADATA_KEY_TITLE, showTitle)
            extra.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, streamUrl)
            extra.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, pictureUrl)

            val activity = context as Activity
            val mediaController = MediaControllerCompat.getMediaController(activity)

            if (mediaController?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) == radioCodeName
                && mediaController.playbackState.playbackState == PlaybackState.STATE_PLAYING) {
                MediaControllerCompat.getMediaController(activity)?.transportControls?.sendCustomAction("updateMetadata", extra)
                return
            }

            MediaControllerCompat.getMediaController(activity)?.transportControls?.playFromUri(streamUri, extra)
        }
    }

    @JavascriptInterface
    fun pause() {
        if (mediaBrowser.isConnected) {
            val activity = context as Activity
            MediaControllerCompat.getMediaController(activity)?.transportControls?.pause()
        }
    }

    @JavascriptInterface
    fun list(listJson: String) {
        if (mediaBrowser.isConnected) {
            val activity = context as Activity
            val extra = Bundle()
            extra.putString("list", listJson)
            MediaControllerCompat.getMediaController(activity)?.transportControls?.sendCustomAction("setList", extra)
        }
    }

    @JavascriptInterface
    fun getstate() {
        if (mediaBrowser.isConnected) {
            val activity = context as Activity
            MediaControllerCompat.getMediaController(activity)?.transportControls?.sendCustomAction("sendUpdate", Bundle())
        } else {
            needUpdate = true
        }
    }
}