package io.programmes_radio.www.progradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : AppCompatActivity() {

    companion object {
        const val BASE_URL_PROD = "https://www.programmes-radio.com"
        const val BASE_URL_API_PROD = "https://api.programmes-radio.com"
        const val BASE_URL_DEV = "https://www.programmes-radio.com"
        const val BASE_URL_API_DEV = "https://api.programmes-radio.com"
//        const val BASE_URL_DEV = "https://local2.programmes-radio.com:8080"
//        const val BASE_URL_API_DEV = "https://local2.programmes-radio.com:8080/api"
    }

    private val internalLinks = arrayOf<String>("radio-addict.com", "programmes-radio.com", "localhost")

    private var jsInterface: WebAppInterface? = null
    private var mWebView: WebView? = null

    lateinit private var mGeoLocationRequestOrigin: String
    lateinit private var mGeoLocationCallback: GeolocationPermissions.Callback
    val MY_PERMISSIONS_REQUEST_LOCATION = 99

    // Receive player update and send it to the webview vue app
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun doEvent(intent: Intent) {
        if (intent.action == "UpdatePlaybackStatus") {
            mWebView!!.post {
                mWebView!!.evaluateJavascript(
                    "document.getElementById('app').__vue_app__.config.globalProperties.\$pinia._s.get('player').updateStatusFromExternalPlayer({playbackState: ${
                        intent.getIntExtra(
                            "playbackState",
                            0
                        )
                    }, radioCodeName: '${intent.getStringExtra("radioCodeName")}'});",
                    null
                )
            }

            return;
        }

        if (intent.action == "UpdateTimerFinish") {
            mWebView!!.post {
                mWebView!!.evaluateJavascript(
                    "document.getElementById('app').__vue_app__.config.globalProperties.\$pinia._s.get('player').updateTimerEnding(${
                        intent.getIntExtra(
                            "finish",
                            0
                        )});",
                    null
                )
            }

            return;
        }

        if (intent.action == "Command") {
            mWebView!!.post {
                mWebView!!.evaluateJavascript(
                    "document.getElementById('app').__vue_app__.config.globalProperties.\$pinia._s.get('player').commandFromExternalPlayer({command: '${
                        intent.getStringExtra("command")
                    }'});",
                    null
                )
            }

            return;
        }

        if (intent.action == "UpdateSong") {
            mWebView!!.post {
                mWebView!!.evaluateJavascript(
                    "document.getElementById('app').__vue_app__.config.globalProperties.\$pinia._s.get('player').updateSong({name: '${intent.getStringExtra("name")}', topic: '${intent.getStringExtra("topic")}', song: ${intent.getStringExtra("song")}});",
                    null
                )
            }

            return;
        }
    }

    @SuppressLint("JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // receiver for vue app update
//        if (savedInstanceState == null) {
        EventBus.getDefault().register(this);
//        }

        volumeControlStream = AudioManager.STREAM_MUSIC

        mWebView = WebView(this)
        setContentView(mWebView)

        jsInterface = WebAppInterface(this)
        if (jsInterface != null) {
            mWebView!!.addJavascriptInterface(jsInterface!!, "Android")
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
        mWebView!!.settings.userAgentString = mWebView!!.settings.userAgentString + " progradio";

        mWebView!!.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // from https://xabaras.medium.com/android-webview-handling-geolocation-permission-request-cc482f3de210
        mWebView!!.webChromeClient =  object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // Do We need to ask for permission?
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {

                    // Should we show an explanation?
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    ) {

                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.permission_location_rationale)
                            .setNeutralButton(android.R.string.ok) { _, _ ->
                                mGeoLocationRequestOrigin = origin
                                mGeoLocationCallback = callback
                                ActivityCompat.requestPermissions(
                                    this@MainActivity,
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                    MY_PERMISSIONS_REQUEST_LOCATION
                                )
                            }
                            .show()

                    } else {
                        // No explanation needed, we can request the permission.
                        mGeoLocationRequestOrigin = origin
                        mGeoLocationCallback = callback
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                            MY_PERMISSIONS_REQUEST_LOCATION
                        )
                    }
                } else {
                    // Tell the WebView that permission has been granted
                    callback.invoke(origin, true, false)
                }
            }
        }

        mWebView!!.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val isInternalLink = internalLinks.any { request.url.toString().contains(it) }

                if (isInternalLink) {
                    return false;
                }

                val intent = Intent(Intent.ACTION_VIEW, request.url)
                view.context.startActivity(intent)
                return true
            }
        }

        if (savedInstanceState == null) {
            // Force links and redirects to open in the WebView instead of in a browser
            if (BuildConfig.DEBUG) {
                mWebView!!.clearCache(true)
                mWebView!!.loadUrl(BASE_URL_DEV)
            } else {
                mWebView!!.loadUrl(BASE_URL_PROD)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_LOCATION -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mGeoLocationCallback.invoke(mGeoLocationRequestOrigin, true, false)
                } else {
                    mGeoLocationCallback.invoke(mGeoLocationRequestOrigin, false, false)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mWebView!!.canGoBack()) {
            mWebView!!.goBack()
        } else {
            super.onBackPressed()
        }
    }

    public override fun onResume() {
        super.onResume()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        jsInterface?.reconnect()
        jsInterface?.getstate()
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mWebView!!.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        super.onRestoreInstanceState(savedInstanceState)
        mWebView!!.restoreState(savedInstanceState)
    }

    override fun onPause() {
        super.onPause()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    public override fun onStop() {
        super.onStop()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        CookieManager.getInstance().flush();
        jsInterface?.mediaSessionDisconnect()
    }
}