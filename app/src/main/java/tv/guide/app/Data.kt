package tv.guide.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Channel(
    val id: String,
    val name: String,
    val group: String,
    val number: String,
    val url: String
)

class Prog(val start: Long, val stop: Long, val title: String, val desc: String) {
    val isFiller: Boolean get() = desc.contains("No published schedule")
}

object Store {

    private val attr = Regex("([a-zA-Z0-9-]+)=\"([^\"]*)\"")

    fun parsePlaylist(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var info: Map<String, String>? = null
        var title = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val m = HashMap<String, String>()
                for (hit in attr.findAll(line)) {
                    m[hit.groupValues[1]] = hit.groupValues[2]
                }
                info = m
                title = line.substringAfterLast(",", "").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val i = info
                if (i != null) {
                    val name = (i["tvg-name"] ?: "").ifEmpty { title }
                    val id = (i["tvg-id"] ?: "").ifEmpty { name }
                    val number = (i["tvg-chno"] ?: "").ifEmpty { (out.size + 1).toString() }
                    out.add(Channel(id, name, i["group-title"] ?: "", number, line))
                }
                info = null
            }
        }
        return out
    }

    fun parseGuide(file: File, keepAfter: Long): HashMap<String, ArrayList<Prog>> {
        val map = HashMap<String, ArrayList<Prog>>()
        val bis = BufferedInputStream(FileInputStream(file), 65536)
        bis.mark(4)
        val b0 = bis.read()
        val b1 = bis.read()
        bis.reset()
        val input: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bis, 65536) else bis
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, "UTF-8")
            var inProg = false
            var channel = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val tag = p.name
                    if (tag == "programme") {
                        inProg = true
                        channel = p.getAttributeValue(null, "channel") ?: ""
                        start = parseTime(p.getAttributeValue(null, "start"), cal)
                        stop = parseTime(p.getAttributeValue(null, "stop"), cal)
                        title = ""
                        desc = ""
                    } else if (inProg && tag == "title") {
                        title = p.nextText()
                    } else if (inProg && tag == "desc") {
                        val d = p.nextText()
                        desc = if (d.length > 400) d.substring(0, 400) + "..." else d
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    inProg = false
                    if (stop > keepAfter && start > 0L && channel.isNotEmpty()) {
                        var list = map[channel]
                        if (list == null) {
                            list = ArrayList()
                            map[channel] = list
                        }
                        list.add(Prog(start, stop, title, desc))
                    }
                }
                ev = p.next()
            }
        } finally {
            try {
                input.close()
            } catch (e: IOException) {
            }
        }
        for (list in map.values) {
            list.sortBy { it.start }
        }
        return map
    }

    private fun parseTime(s: String?, cal: Calendar): Long {
        if (s == null || s.length < 14) return 0L
        return try {
            cal.clear()
            cal.set(
                s.substring(0, 4).toInt(),
                s.substring(4, 6).toInt() - 1,
                s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(),
                s.substring(10, 12).toInt(),
                s.substring(12, 14).toInt()
            )
            var t = cal.timeInMillis
            if (s.length >= 20) {
                val sign = if (s[15] == '-') -1 else 1
                val hours = s.substring(16, 18).toInt()
                val mins = s.substring(18, 20).toInt()
                t -= sign * (hours * 3600000L + mins * 60000L)
            }
            t
        } catch (e: Exception) {
            0L
        }
    }

    private fun open(address: String): HttpURLConnection {
        var url = URL(address)
        for (hop in 0..5) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GuideTV")
            val code = c.responseCode
            if (code == 200) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && next != null) {
                url = URL(url, next)
            } else {
                throw IOException("HTTP $code")
            }
        }
        throw IOException("Too many redirects")
    }

    fun fetch(address: String, dest: File) {
        val c = open(address)
        val tmp = File(dest.path + ".tmp")
        try {
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
        } finally {
            c.disconnect()
        }
        if (tmp.length() < 10L) throw IOException("Empty file")
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Couldn't save file")
    }
}
