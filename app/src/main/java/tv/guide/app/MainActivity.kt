package tv.guide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_PLAYLIST = "https://raw.githubusercontent.com/eandsconsultings/epg/dist/full.m3u"
        const val DEFAULT_GUIDE = "https://raw.githubusercontent.com/eandsconsultings/epg/dist/guide.xml.gz"
        const val ALL = "All channels"
        const val FAVS = "Favorites"
        const val SETTINGS = "Settings"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BG: Int = Color.parseColor("#E60E151D")
        val FOCUS: Int = Color.parseColor("#FF2F6FED")
        val ACCENT: Int = Color.parseColor("#FF7FB0FF")
        val SUB: Int = Color.parseColor("#FFB8C4D0")
    }

    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("h:mm a", Locale.US)

    private lateinit var player: ExoPlayer
    private lateinit var guide: LinearLayout
    private lateinit var catList: RecyclerView
    private lateinit var chList: RecyclerView
    private lateinit var detailTitle: TextView
    private lateinit var detailBody: TextView
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private val catAdapter = CatAdapter()
    private val chAdapter = ChAdapter()

    private var channels: List<Channel> = emptyList()
    private var progs: Map<String, List<Prog>> = emptyMap()
    private var cats: List<String> = listOf(ALL, FAVS, SETTINGS)
    private var shown: List<Channel> = emptyList()
    private var cat: String = ALL
    private var current: Channel? = null
    private var favs: HashSet<String> = HashSet()
    private var lastBack = 0L
    private var loading = false
    private var ticks = 0

    private fun prefs() = getSharedPreferences("tv", Context.MODE_PRIVATE)

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun rowBg(focused: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(if (focused) FOCUS else Color.TRANSPARENT)
        d.cornerRadius = dp(6).toFloat()
        return d
    }

    private fun text(size: Float, color: Int, bold: Boolean, lines: Int): TextView {
        val t = TextView(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.maxLines = lines
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        favs = HashSet(prefs().getStringSet("favs", emptySet()) ?: emptySet())
        buildUi()
        load(false)
        ui.postDelayed(tick, 30000L)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 GuideTV")
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val name = current?.name ?: "This channel"
                toast("$name isn't playing right now")
                showGuide()
            }
        })
        val pv = PlayerView(this)
        pv.useController = false
        pv.isFocusable = false
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        pv.player = player
        root.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))

        guide = LinearLayout(this)
        guide.orientation = LinearLayout.VERTICAL
        guide.setBackgroundColor(BG)
        guide.setPadding(dp(20), dp(16), dp(20), dp(8))

        val cols = LinearLayout(this)
        cols.orientation = LinearLayout.HORIZONTAL

        catList = RecyclerView(this)
        catList.layoutManager = LinearLayoutManager(this)
        catList.adapter = catAdapter
        catList.itemAnimator = null
        cols.addView(catList, LinearLayout.LayoutParams(dp(190), MATCH))

        chList = RecyclerView(this)
        chList.layoutManager = LinearLayoutManager(this)
        chList.adapter = chAdapter
        chList.itemAnimator = null
        val chLp = LinearLayout.LayoutParams(0, MATCH, 1f)
        chLp.leftMargin = dp(14)
        chLp.rightMargin = dp(14)
        cols.addView(chList, chLp)

        val detail = LinearLayout(this)
        detail.orientation = LinearLayout.VERTICAL
        detailTitle = text(20f, Color.WHITE, true, 2)
        detailBody = text(14f, SUB, false, 40)
        detailBody.setLineSpacing(dp(2).toFloat(), 1f)
        detail.addView(detailTitle)
        val bodyLp = LinearLayout.LayoutParams(MATCH, WRAP)
        bodyLp.topMargin = dp(6)
        detail.addView(detailBody, bodyLp)
        cols.addView(detail, LinearLayout.LayoutParams(dp(270), MATCH))

        guide.addView(cols, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val hint = text(12f, SUB, false, 1)
        hint.text = "OK: watch     Hold OK or Menu: favorite     Back: close guide     Up/Down while watching: change channel"
        val hintLp = LinearLayout.LayoutParams(MATCH, WRAP)
        hintLp.topMargin = dp(6)
        guide.addView(hint, hintLp)
        root.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH))

        banner = text(16f, Color.WHITE, false, 3)
        banner.setBackgroundColor(BG)
        banner.setPadding(dp(18), dp(12), dp(18), dp(12))
        banner.visibility = View.GONE
        val bannerLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
        bannerLp.leftMargin = dp(28)
        bannerLp.bottomMargin = dp(28)
        root.addView(banner, bannerLp)

        status = text(18f, Color.WHITE, false, 3)
        status.gravity = Gravity.CENTER
        status.text = "Loading channels and guide..."
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        setContentView(root)
    }

    // ---------- data ----------

    private fun playlistUrl(): String = prefs().getString("playlist", DEFAULT_PLAYLIST) ?: DEFAULT_PLAYLIST

    private fun guideUrl(): String = prefs().getString("guide", DEFAULT_GUIDE) ?: DEFAULT_GUIDE

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        val hadData = channels.isNotEmpty()
        val pUrl = playlistUrl()
        val gUrl = guideUrl()
        val pf = File(cacheDir, "playlist.m3u")
        val gf = File(cacheDir, "guide.xml.gz")
        Thread {
            var err: String? = null
            if (!hadData && pf.exists()) {
                try {
                    publish(pf, gf)
                } catch (e: Exception) {
                }
            }
            val now = System.currentTimeMillis()
            var changed = false
            try {
                if (force || !pf.exists() || now - pf.lastModified() > 30L * 60000L) {
                    Store.fetch(pUrl, pf)
                    changed = true
                }
            } catch (e: Exception) {
                err = "Couldn't update the channel list"
            }
            try {
                if (force || !gf.exists() || now - gf.lastModified() > 2L * 3600000L) {
                    Store.fetch(gUrl, gf)
                    changed = true
                }
            } catch (e: Exception) {
                if (err == null) err = "Couldn't update the guide"
            }
            if (pf.exists() && (changed || hadData || channels.isEmpty())) {
                try {
                    publish(pf, gf)
                } catch (e: Exception) {
                    err = "Couldn't read the channel list"
                }
            }
            val message = err
            ui.post {
                loading = false
                if (channels.isEmpty()) {
                    status.visibility = View.VISIBLE
                    status.text = "No channels loaded.\nCheck your internet, or the playlist link in Settings."
                }
                if (message != null) toast(message)
            }
        }.start()
    }

    private fun publish(pf: File, gf: File) {
        val ch = Store.parsePlaylist(pf.readText())
        var pr: Map<String, List<Prog>> = HashMap()
        if (gf.exists()) {
            try {
                pr = Store.parseGuide(gf, System.currentTimeMillis() - 3600000L)
            } catch (e: Exception) {
            }
        }
        if (ch.isEmpty()) return
        val guideData = pr
        ui.post { setData(ch, guideData) }
    }

    private fun setData(ch: List<Channel>, pr: Map<String, List<Prog>>) {
        val focusedPos = focusedChannelPos()
        channels = ch
        progs = pr
        val groups = ArrayList<String>()
        for (c in ch) {
            if (c.group.isNotEmpty() && !groups.contains(c.group)) groups.add(c.group)
        }
        groups.sort()
        val list = ArrayList<String>()
        list.add(ALL)
        list.add(FAVS)
        list.addAll(groups)
        list.add(SETTINGS)
        cats = list
        if (!cats.contains(cat)) cat = ALL
        shown = filtered()
        catAdapter.notifyDataSetChanged()
        chAdapter.notifyDataSetChanged()
        status.visibility = View.GONE
        if (current == null) {
            val last = prefs().getString("last", null)
            val c = ch.firstOrNull { it.id == last }
            if (c != null) play(c, false)
        }
        if (guide.visibility == View.VISIBLE) {
            val idx = if (focusedPos >= 0) focusedPos else currentIndex()
            focusChannel(idx)
        }
    }

    private fun hasListings(c: Channel): Boolean {
        val list = progs[c.id] ?: return false
        return list.isNotEmpty() && !list[0].isFiller
    }

    private fun filtered(): List<Channel> {
        val hide = prefs().getBoolean("hide", false)
        val out = ArrayList<Channel>()
        for (c in channels) {
            val inCat = when (cat) {
                ALL -> true
                FAVS -> favs.contains(c.id)
                else -> c.group == cat
            }
            if (inCat && (!hide || hasListings(c))) out.add(c)
        }
        return out
    }

    private fun progAt(c: Channel, t: Long): Int {
        val list = progs[c.id] ?: return -1
        for (i in list.indices) {
            val p = list[i]
            if (p.start <= t && t < p.stop) return i
        }
        return -1
    }

    private fun range(p: Prog): String = clock.format(Date(p.start)) + " - " + clock.format(Date(p.stop))

    // ---------- playback ----------

    private fun play(c: Channel, showBanner: Boolean) {
        current = c
        prefs().edit().putString("last", c.id).apply()
        val item = MediaItem.Builder().setUri(Uri.parse(c.url))
        val u = c.url.lowercase(Locale.US).substringBefore('?')
        if (u.endsWith(".mpd")) {
            item.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (!(u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".flv") ||
                    u.endsWith(".mp3") || u.endsWith(".aac") || u.endsWith(".ts"))
        ) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = true
        if (showBanner) showBanner(c)
        refreshRows()
    }

    private fun showBanner(c: Channel) {
        val sb = StringBuilder()
        sb.append(c.number).append("   ").append(c.name)
        val list = progs[c.id]
        val i = progAt(c, System.currentTimeMillis())
        if (list != null && i >= 0 && !list[i].isFiller) {
            sb.append('\n').append(list[i].title).append("   ").append(range(list[i]))
            if (i + 1 < list.size) sb.append("\nNext: ").append(list[i + 1].title)
        }
        banner.text = sb.toString()
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 4500L)
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun currentIndex(): Int {
        val id = current?.id ?: return 0
        val i = shown.indexOfFirst { it.id == id }
        return if (i < 0) 0 else i
    }

    private fun zap(step: Int) {
        val list = if (shown.isNotEmpty()) shown else channels
        if (list.isEmpty()) return
        val id = current?.id
        val i = list.indexOfFirst { it.id == id }
        val next = if (i < 0) 0 else (i + step + list.size) % list.size
        play(list[next], true)
    }

    // ---------- guide ----------

    private fun showGuide() {
        guide.visibility = View.VISIBLE
        banner.visibility = View.GONE
        refreshRows()
        focusChannel(currentIndex())
    }

    private fun hideGuide() {
        guide.visibility = View.GONE
    }

    private fun refreshRows() {
        if (shown.isNotEmpty()) chAdapter.notifyItemRangeChanged(0, shown.size, "tick")
    }

    private fun focusedChannelPos(): Int {
        val v = chList.focusedChild ?: return -1
        return chList.getChildAdapterPosition(v)
    }

    private fun focusChannel(index: Int) {
        if (shown.isEmpty()) {
            catList.requestFocus()
            return
        }
        val idx = if (index < 0 || index >= shown.size) 0 else index
        (chList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, dp(120))
        ui.postDelayed({
            val holder = chList.findViewHolderForAdapterPosition(idx)
            if (holder != null) holder.itemView.requestFocus() else chList.requestFocus()
        }, 80L)
    }

    private fun selectCat(name: String) {
        cat = name
        shown = filtered()
        chAdapter.notifyDataSetChanged()
        catAdapter.notifyItemRangeChanged(0, cats.size, "sel")
        if (shown.isEmpty()) {
            detailTitle.text = name
            detailBody.text = if (name == FAVS) {
                "No favorites yet.\nHighlight a channel and hold OK, or press Menu, to add it."
            } else {
                "No channels here."
            }
        } else {
            focusChannel(0)
        }
    }

    private fun toggleFav(c: Channel) {
        if (favs.contains(c.id)) {
            favs.remove(c.id)
            toast("Removed from favorites")
        } else {
            favs.add(c.id)
            toast("Added to favorites")
        }
        prefs().edit().putStringSet("favs", HashSet(favs)).apply()
        if (cat == FAVS) {
            val pos = focusedChannelPos()
            shown = filtered()
            chAdapter.notifyDataSetChanged()
            focusChannel(if (pos >= shown.size) shown.size - 1 else pos)
        } else {
            refreshRows()
        }
    }

    private fun showDetail(c: Channel) {
        detailTitle.text = c.name
        val sb = StringBuilder()
        if (c.group.isNotEmpty()) sb.append(c.group).append('\n')
        val list = progs[c.id]
        val i = progAt(c, System.currentTimeMillis())
        if (list == null || i < 0 || list[i].isFiller) {
            sb.append("\nNo published schedule for this channel.")
        } else {
            val p = list[i]
            sb.append("\nNOW   ").append(range(p)).append('\n').append(p.title).append('\n')
            if (p.desc.isNotEmpty()) sb.append(p.desc).append('\n')
            var j = i + 1
            if (j < list.size) sb.append("\nNEXT\n")
            var n = 0
            while (j < list.size && n < 7) {
                sb.append(clock.format(Date(list[j].start))).append("   ").append(list[j].title).append('\n')
                j++
                n++
            }
        }
        detailBody.text = sb.toString()
    }

    private fun bindChannel(h: ChHolder, c: Channel) {
        val now = System.currentTimeMillis()
        val mark = if (current?.id == c.id) "▶ " else ""
        val star = if (favs.contains(c.id)) "★ " else ""
        h.title.text = c.number + "   " + mark + star + c.name
        val list = progs[c.id]
        val i = progAt(c, now)
        if (list != null && i >= 0 && !list[i].isFiller) {
            val p = list[i]
            h.now.text = p.title + "     " + range(p)
            h.bar.visibility = View.VISIBLE
            val span = if (p.stop - p.start > 0L) p.stop - p.start else 1L
            h.bar.progress = (((now - p.start) * 1000L) / span).toInt()
        } else {
            h.now.text = if (c.group.isEmpty()) "No listings" else c.group + " · no listings"
            h.bar.visibility = View.INVISIBLE
        }
    }

    // ---------- settings ----------

    private fun settings() {
        val hide = prefs().getBoolean("hide", false)
        val items = arrayOf(
            "Reload channels and guide now",
            "Hide channels without listings: " + (if (hide) "On" else "Off"),
            "Change playlist link",
            "Change guide link",
            "Reset links to default"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        toast("Reloading...")
                        load(true)
                    }
                    1 -> {
                        prefs().edit().putBoolean("hide", !hide).apply()
                        selectCat(cat)
                    }
                    2 -> editLink("playlist", "Playlist link", DEFAULT_PLAYLIST)
                    3 -> editLink("guide", "Guide link", DEFAULT_GUIDE)
                    4 -> {
                        prefs().edit().remove("playlist").remove("guide").apply()
                        toast("Reloading...")
                        load(true)
                    }
                }
            }
            .show()
    }

    private fun editLink(key: String, title: String, def: String) {
        val input = EditText(this)
        input.setText(prefs().getString(key, def))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    prefs().edit().putString(key, v).apply()
                    toast("Reloading...")
                    load(true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- keys and lifecycle ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (guide.visibility != View.VISIBLE) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) showGuide()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (down) zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (down) zap(1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (down) player.playWhenReady = !player.playWhenReady
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (down) {
                        val t = System.currentTimeMillis()
                        if (t - lastBack < 2500L) {
                            finish()
                        } else {
                            lastBack = t
                            toast("Press Back again to exit")
                        }
                    }
                    return true
                }
            }
        } else {
            when (k) {
                KeyEvent.KEYCODE_BACK -> {
                    if (down) {
                        if (current != null) hideGuide() else finish()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (down) {
                        val pos = focusedChannelPos()
                        if (pos >= 0 && pos < shown.size) toggleFav(shown[pos])
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (guide.visibility == View.VISIBLE) refreshRows()
            if (ticks % 120 == 0) load(false)
            ui.postDelayed(this, 30000L)
        }
    }

    override fun onStart() {
        super.onStart()
        if (current != null) player.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player.playWhenReady = false
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    // ---------- lists ----------

    class RowHolder(v: View) : RecyclerView.ViewHolder(v)

    class ChHolder(v: View, val title: TextView, val now: TextView, val bar: ProgressBar) :
        RecyclerView.ViewHolder(v)

    inner class CatAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount(): Int = cats.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val t = text(15f, Color.WHITE, false, 1)
            t.isFocusable = true
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            t.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            val h = RowHolder(t)
            t.setOnFocusChangeListener { v, focused -> v.background = rowBg(focused) }
            t.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos >= 0 && pos < cats.size) {
                    val name = cats[pos]
                    if (name == SETTINGS) settings() else selectCat(name)
                }
            }
            return h
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val t = holder.itemView as TextView
            val name = cats[position]
            t.text = name
            t.setTextColor(if (name == cat) ACCENT else Color.WHITE)
            t.typeface = if (name == cat) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    inner class ChAdapter : RecyclerView.Adapter<ChHolder>() {
        override fun getItemCount(): Int = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChHolder {
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.VERTICAL
            row.isFocusable = true
            row.setPadding(dp(12), dp(7), dp(12), dp(7))
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            val title = text(16f, Color.WHITE, true, 1)
            val now = text(13f, SUB, false, 1)
            val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 1000
            row.addView(title)
            row.addView(now)
            val barLp = LinearLayout.LayoutParams(MATCH, dp(3))
            barLp.topMargin = dp(4)
            row.addView(bar, barLp)
            val h = ChHolder(row, title, now, bar)
            row.setOnFocusChangeListener { v, focused ->
                v.background = rowBg(focused)
                val pos = h.bindingAdapterPosition
                if (focused && pos >= 0 && pos < shown.size) showDetail(shown[pos])
            }
            row.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos >= 0 && pos < shown.size) {
                    play(shown[pos], false)
                    hideGuide()
                }
            }
            row.setOnLongClickListener {
                val pos = h.bindingAdapterPosition
                if (pos >= 0 && pos < shown.size) toggleFav(shown[pos])
                true
            }
            return h
        }

        override fun onBindViewHolder(holder: ChHolder, position: Int) {
            bindChannel(holder, shown[position])
        }
    }
}
