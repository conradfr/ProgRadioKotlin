package io.programmes_radio.www.progradio

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.http.SslError
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import androidx.core.util.TypedValueCompat.pxToDp
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.ViewCompat;

class MainActivity : ComponentActivity() {
    companion object {
        const val BASE_URL_PROD = "https://www.programmes-radio.com"
        const val BASE_URL_API_PROD = "https://api.programmes-radio.com"
        const val BASE_URL_DEV = "https://www.programmes-radio.com/"
        const val BASE_URL_API_DEV = "https://api.programmes-radio.com"
//        const val BASE_URL_DEV = "https://7b995044ada8.ngrok-free.app"
//        const val BASE_URL_API_DEV = "https://fb92476b9c34.ngrok-free.app:4001"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // receiver for vue app update
//        if (savedInstanceState == null) {
        EventBus.getDefault().register(this);
//        }

        volumeControlStream = AudioManager.STREAM_MUSIC

        mWebView = WebView(this)
        setContentView(mWebView)

        jsInterface = WebAppInterface(this)
        mWebView!!.addJavascriptInterface(jsInterface!!, "Android")

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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // dispatch insets because insets aren't applied when the webpage first loads.
                view.requestApplyInsets()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(mWebView) { _, windowInsets ->
            // Retrieve insets as raw pixels
            val safeDrawingInsets = windowInsets.getInsets(
                systemBars() or displayCutout() or ime()
            )
            val displayMetrics = mWebView!!.context.resources.displayMetrics

            // Convert raw pixels to density independent pixels
            val top = pxToDp(safeDrawingInsets.top.toFloat(), displayMetrics)
            val right = pxToDp(safeDrawingInsets.right.toFloat(), displayMetrics)
            val bottom = pxToDp(safeDrawingInsets.bottom.toFloat(), displayMetrics)
            val left = pxToDp(safeDrawingInsets.left.toFloat(), displayMetrics)

            val safeAreaJs = """
                document.documentElement.style.setProperty('--safe-area-inset-top', '${top}px');
                document.documentElement.style.setProperty('--safe-area-inset-right', '${right}px');
                document.documentElement.style.setProperty('--safe-area-inset-bottom', '${bottom}px');
                document.documentElement.style.setProperty('--safe-area-inset-left', '${left}px');
            """

            // Inject the density independent pixels into the CSS variables as CSS pixels
            mWebView!!.evaluateJavascript(safeAreaJs, null)

            windowInsets
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mWebView?.saveState(outState) // Save WebView state
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }

        mWebView?.restoreState(savedInstanceState) // Restore WebView state
        jsInterface?.reconnect()
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        jsInterface?.reconnect() // Optional: Reconnect media session if needed
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
        jsInterface?.mediaSessionDisconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        jsInterface?.mediaSessionDisconnect()
        mWebView?.destroy()
    }
}
