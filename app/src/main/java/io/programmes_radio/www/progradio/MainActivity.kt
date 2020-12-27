package io.programmes_radio.www.progradio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    companion object {
        const val BASE_URL_PROD = "https://www.programmes-radio.io"
        const val BASE_URL_DEV = "https://local.programmes-radio.io:8080"
    }

    private var jsInterface: WebAppInterface? = null
    private var mWebView: WebView? = null

    private lateinit var localManager: LocalBroadcastManager

    // Receive player update and send it to the webview vue app
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mWebView!!.post {
                mWebView!!.evaluateJavascript(
                    "document.getElementById('app').__vue__.\$store.dispatch('updateStatusFromExternalPlayer', {playbackState: ${
                        intent?.getIntExtra(
                            "playbackState",
                            0
                        )
                    }, radioCodeName: '${intent?.getStringExtra("radioCodeName")}'});",
                    null
                )
            }
        }
    }

    private var filter = IntentFilter()

    @SuppressLint("JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // receiver for vue app update
//        if (savedInstanceState === null) {
            localManager = LocalBroadcastManager.getInstance(baseContext)
            filter.addAction("UpdatePlaybackStatus")
        localManager.registerReceiver(receiver, filter)
//        }

        volumeControlStream = AudioManager.STREAM_MUSIC

        mWebView = WebView(this)
        setContentView(mWebView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            jsInterface = WebAppInterface(this)
            mWebView!!.addJavascriptInterface(jsInterface, "Android")
        }

        mWebView!!.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                if (BuildConfig.DEBUG) {
                    handler.proceed() // Ignore SSL certificate errors
                }
            }
        }

        // Enable Javascript
        mWebView!!.settings.javaScriptEnabled = true

        mWebView!!.settings.mediaPlaybackRequiresUserGesture = false
        mWebView!!.settings.domStorageEnabled = true
        mWebView!!.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        mWebView!!.settings.setAppCacheEnabled(false)
        mWebView!!.settings.userAgentString = mWebView!!.settings.userAgentString + " progradio";

        mWebView!!.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (savedInstanceState === null) {
            // Force links and redirects to open in the WebView instead of in a browser
            if (BuildConfig.DEBUG) {
                mWebView!!.settings.setAppCacheEnabled(false)
                mWebView!!.clearCache(true)
                mWebView!!.loadUrl(BASE_URL_DEV)

//                mWebView!!.loadUrl("https://www.programmes-radio.io")
            } else {
                mWebView!!.loadUrl(BASE_URL_PROD)
            }
        }
    }

    override fun onBackPressed() {
        if (mWebView!!.canGoBack()) {
            mWebView!!.goBack()
        } else {
            super.onBackPressed()
        }
    }

    public override fun onResume() {
        super.onResume()
        jsInterface?.reconnect()
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mWebView!!.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        mWebView!!.restoreState(savedInstanceState)
    }

    public override fun onStop() {
        super.onStop()
        jsInterface?.mediaSessionDisconnect()
    }
}