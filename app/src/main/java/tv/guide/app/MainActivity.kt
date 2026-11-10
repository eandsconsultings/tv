package tv.guide.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The whole screen (guide, menus, search, settings) is the page in assets/index.html.
 * The picture is played by the device's own player in two video layers underneath
 * that page: layer 0 is the main picture, layer 1 the second window.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private val views = arrayOfNulls<PlayerView>(2)
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val urls = arrayOfNulls<String>(2)
    private val boxes = arrayOfNulls<String>(2)
    private val position = AtomicLongArray(2)
    private val ready = AtomicIntegerArray(2)
    @Volatile
    private var info = "{}"
    private var paused = false
    private var typing = false
    private var stopped = false
    private var shownPage = 0
    private var startedAt = 0L
    private var touched = false

    private val ticker = object : Runnable {
        override fun run() {
            for (i in 0..1) {
                val p = players[i]
                if (p == null) {
                    ready.set(i, 0)
                } else {
                    position.set(i, p.currentPosition)
                    ready.set(i, if (p.playbackState == Player.STATE_READY && p.playWhenReady) 1 else 0)
                }
            }
            info = describe()
            ui.postDelayed(this, 500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        for (i in 0..1) {
            val pv = PlayerView(this)
            pv.useController = false
            pv.setShutterBackgroundColor(Color.BLACK)
            pv.isFocusable = false
            pv.isFocusableInTouchMode = false
            if (i == 1) {
                val surface = pv.videoSurfaceView
                if (surface is SurfaceView) surface.setZOrderMediaOverlay(true)
                pv.subtitleView?.visibility = View.GONE
                pv.visibility = View.GONE
            }
            views[i] = pv
            root.addView(pv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = true
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        web.webViewClient = object : WebViewClient() {
            // The page asks for a browser video library it doesn't need here.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u.contains("hls.min.js")) {
                    return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
        }
        web.addJavascriptInterface(Bridge(), "Native")
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.requestFocus()
        startedAt = System.currentTimeMillis()
        showPage(bestLocalPage())
        ui.post(ticker)
        Thread { refreshPage(false) }.start()
        handleInstall(intent)
    }

    // ---------- the screen updates itself ----------
    // The newest screen is fetched from GitHub each time the app opens. The copy built
    // into the app is the fallback, so the app still works with no connection.

    private fun pageFile() = File(filesDir, "index.html")

    private fun number(html: String, name: String): Int =
        Regex("name=\"" + name + "\" content=\"(\\d+)\"").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun usable(html: String): Boolean =
        html.contains("</html>") && html.contains("tvKey") && number(html, "api") <= API

    private fun bestLocalPage(): String {
        val built = assets.open("index.html").bufferedReader().use { it.readText() }
        val saved = try {
            if (pageFile().exists()) pageFile().readText() else ""
        } catch (e: Exception) {
            ""
        }
        return if (usable(saved) && number(saved, "page") > number(built, "page")) saved else built
    }

    private fun showPage(html: String) {
        shownPage = number(html, "page")
        try {
            val f = pageFile()
            if (!f.exists() || f.readText() != html) f.writeText(html)
            web.loadUrl(Uri.fromFile(f).toString())
        } catch (e: Exception) {
            web.loadUrl("file:///android_asset/index.html")
        }
    }

    private fun get(address: String): ByteArray {
        var url = URL(address)
        for (hop in 0..5) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "GuideTV")
            val code = c.responseCode
            if (code == 200) {
                try {
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { it.copyTo(out) }
                    return out.toByteArray()
                } finally {
                    c.disconnect()
                }
            }
            val next = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && next != null) url = URL(url, next) else throw IOException("HTTP $code")
        }
        throw IOException("Too many redirects")
    }

    /** Runs off the main thread. asked = the user pressed "Refresh the screen now". */
    private fun refreshPage(asked: Boolean) {
        try {
            val html = String(get(PAGE_URL + "?t=" + System.currentTimeMillis()), Charsets.UTF_8)
            if (!usable(html) || number(html, "page") <= shownPage) {
                if (asked) say(if (usable(html) || html.isEmpty()) "The screen is already up to date" else "A newer screen needs a newer app. Use Check for app update.")
                return
            }
            ui.post {
                val early = System.currentTimeMillis() - startedAt < 8000L && !touched
                if (asked || early) {
                    showPage(html)
                } else {
                    try {
                        pageFile().writeText(html)
                    } catch (e: Exception) {
                    }
                    say("Screen update downloaded. It shows the next time you open the app.")
                }
            }
        } catch (e: Exception) {
            if (asked) say("Couldn't reach the update. Check the connection.")
        }
    }

    // ---------- app updates ----------

    @Suppress("DEPRECATION")
    private fun myVersion(): Int = try {
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    private fun checkUpdate() {
        try {
            val body = String(get("https://api.github.com/repos/eandsconsultings/tv/commits/apk"), Charsets.UTF_8)
            val latest = Regex("\"message\"\\s*:\\s*\"Build (\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val mine = myVersion()
            if (latest == null) {
                say("Couldn't check for an app update")
            } else if (latest <= mine) {
                say("You have the latest app (0.$mine)")
            } else {
                say("Downloading app 0.$latest...")
                install(get(APK_URL + "?t=" + System.currentTimeMillis()))
            }
        } catch (e: Exception) {
            say("Couldn't check for an app update")
        }
    }

    private fun install(apk: ByteArray) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val session = installer.openSession(installer.createSession(params))
        session.openWrite("tv.apk", 0, apk.size.toLong()).use { out ->
            out.write(apk)
            session.fsync(out)
        }
        val back = Intent(this, MainActivity::class.java).setAction(INSTALL_ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        session.commit(PendingIntent.getActivity(this, 1, back, flags).intentSender)
        session.close()
    }

    /** The system reports back here; the first report asks us to show its install prompt. */
    private fun handleInstall(intent: Intent?) {
        if (intent == null || intent.action != INSTALL_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try {
                if (prompt != null) startActivity(prompt)
            } catch (e: Exception) {
                say("The update couldn't open the install prompt")
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS && status != -99) {
            say("The update didn't install. Allow Guide TV under Install unknown apps, then try again.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstall(intent)
    }

    private fun say(message: String) {
        js("window.nativeToast && nativeToast(" + JSONObject.quote(message) + ")")
    }

    // ---------- players ----------

    private fun release(i: Int) {
        val old = players[i] ?: return
        players[i] = null
        views[i]?.player = null
        try {
            old.release()
        } catch (e: Exception) {
        }
    }

    /** Always starts from a brand new player, so a stuck decoder never carries over. */
    private fun startPlayer(i: Int, url: String) {
        release(i)
        urls[i] = url
        position.set(i, 0L)
        ready.set(i, 0)
        if (stopped) return
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 GuideTV")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(12000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setReleaseTimeoutMs(400L)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (players[i] === p) js("window.nativeError && nativeError($i)")
            }
        })
        if (i == 1) p.volume = 0f
        val item = MediaItem.Builder().setUri(Uri.parse(url))
        val u = url.lowercase(Locale.US).substringBefore('?')
        if (u.endsWith(".mpd")) {
            item.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (!(u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".flv") ||
                    u.endsWith(".mp3") || u.endsWith(".aac") || u.endsWith(".ts"))
        ) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        players[i] = p
        views[i]?.player = p
        p.setMediaItem(item.build())
        p.prepare()
        if (i == 0) paused = false
        p.playWhenReady = true
    }

    private fun stopPlayer(i: Int) {
        urls[i] = null
        release(i)
    }

    private fun place(i: Int, x: Int, y: Int, w: Int, h: Int, show: Boolean) {
        val key = "$x,$y,$w,$h,$show"
        if (boxes[i] == key) return
        boxes[i] = key
        val pv = views[i] ?: return
        if (!show || w <= 0 || h <= 0) {
            pv.visibility = View.GONE
            return
        }
        val lp = FrameLayout.LayoutParams(w, h)
        lp.leftMargin = x
        lp.topMargin = y
        pv.layoutParams = lp
        pv.visibility = View.VISIBLE
    }

    /** What the main picture is: quality, and the sound and caption choices it offers. */
    private fun describe(): String {
        val p = players[0] ?: return "{}"
        val o = JSONObject()
        try {
            val f = p.videoFormat
            o.put("rate", f?.bitrate ?: -1)
            o.put("w", f?.width ?: 0)
            o.put("h", f?.height ?: 0)
            val audio = JSONArray()
            val text = JSONArray()
            var audioSel = -1
            var textSel = -1
            for (g in p.currentTracks.groups) {
                if (g.length < 1) continue
                val fmt = g.getTrackFormat(0)
                val name = fmt.label ?: fmt.language ?: "Track"
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    if (g.isSelected) audioSel = audio.length()
                    audio.put(name)
                } else if (g.type == C.TRACK_TYPE_TEXT) {
                    if (g.isSelected) textSel = text.length()
                    text.put(name)
                }
            }
            o.put("audio", audio)
            o.put("audioSel", audioSel)
            o.put("text", text)
            o.put("textSel", textSel)
        } catch (e: Exception) {
        }
        return o.toString()
    }

    private fun pickTrack(type: Int, n: Int) {
        val p = players[0] ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (n < 0) {
            b.setTrackTypeDisabled(type, true)
        } else {
            var k = 0
            for (g in p.currentTracks.groups) {
                if (g.type != type || g.length < 1) continue
                if (k == n) {
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                }
                k++
            }
        }
        p.trackSelectionParameters = b.build()
    }

    private fun js(code: String) {
        ui.post {
            try {
                web.evaluateJavascript(code, null)
            } catch (e: Exception) {
            }
        }
    }

    // ---------- what the page can ask for ----------

    inner class Bridge {
        @JavascriptInterface
        fun play(i: Int, url: String) {
            if (i in 0..1) ui.post { startPlayer(i, url) }
        }

        @JavascriptInterface
        fun stop(i: Int) {
            if (i in 0..1) ui.post { stopPlayer(i) }
        }

        @JavascriptInterface
        fun layout(i: Int, x: Int, y: Int, w: Int, h: Int, show: Boolean) {
            if (i in 0..1) ui.post { place(i, x, y, w, h, show) }
        }

        @JavascriptInterface
        fun pos(i: Int): Double = if (i in 0..1) position.get(i).toDouble() else 0.0

        @JavascriptInterface
        fun ready(i: Int): Boolean = i in 0..1 && ready.get(i) == 1

        @JavascriptInterface
        fun info(): String = info

        @JavascriptInterface
        fun pause(on: Boolean) {
            ui.post {
                paused = on
                players[0]?.playWhenReady = !on
            }
        }

        @JavascriptInterface
        fun aspect(mode: String) {
            ui.post {
                views[0]?.resizeMode = when (mode) {
                    "Stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        }

        @JavascriptInterface
        fun audioTrack(n: Int) {
            ui.post { pickTrack(C.TRACK_TYPE_AUDIO, n) }
        }

        @JavascriptInterface
        fun textTrack(n: Int) {
            ui.post { pickTrack(C.TRACK_TYPE_TEXT, n) }
        }

        @JavascriptInterface
        fun keyboard(show: Boolean) {
            ui.post {
                typing = show
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (show) {
                    web.requestFocus()
                    imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    imm.hideSoftInputFromWindow(web.windowToken, 0)
                }
            }
        }

        @JavascriptInterface
        fun version(): String = "0." + myVersion()

        @JavascriptInterface
        fun checkUpdate() {
            Thread { this@MainActivity.checkUpdate() }.start()
        }

        @JavascriptInterface
        fun refresh() {
            Thread { refreshPage(true) }.start()
        }

        @JavascriptInterface
        fun exit() {
            ui.post { finish() }
        }
    }

    // ---------- remote ----------

    private fun keyName(e: KeyEvent): String? = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> "Enter"
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> "Escape"
        KeyEvent.KEYCODE_MENU -> "m"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> " "
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "p"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "s"
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        else -> {
            val c = e.unicodeChar
            if (c in 32..126) c.toChar().toString() else null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While a text box is open the on-screen keyboard and the page handle keys themselves.
        if (typing && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val name = keyName(event) ?: return super.dispatchKeyEvent(event)
        touched = true
        val type = when (event.action) {
            KeyEvent.ACTION_DOWN -> "keydown"
            KeyEvent.ACTION_UP -> "keyup"
            else -> return true
        }
        js("window.tvKey && tvKey('$type', ${JSONObject.quote(name)}, ${event.repeatCount > 0})")
        return true
    }

    // ---------- leaving and coming back ----------

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            for (i in 0..1) {
                val u = urls[i]
                if (u != null) startPlayer(i, u)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        for (i in 0..1) release(i)
    }

    companion object {
        /** Highest "api" number of a screen this app can run. */
        const val API = 2
        const val PAGE_URL = "https://raw.githubusercontent.com/eandsconsultings/tv/main/app/src/main/assets/index.html"
        const val APK_URL = "https://raw.githubusercontent.com/eandsconsultings/tv/apk/tv.apk"
        const val INSTALL_ACTION = "tv.guide.app.INSTALL"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        for (i in 0..1) release(i)
        try {
            web.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
