package pro.hattie.app

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * What this tablet knows about its house.
 *
 * TWO THINGS ONLY: where the house is, and — if it was paired with six
 * digits — the device token it was given. No account password, no email
 * address, no name of anybody in the family, and nothing about the tablet
 * beyond the model name it offers as a device name.
 */
class House(context: Context) {

    private val prefs = context.getSharedPreferences("hattie", Context.MODE_PRIVATE)

    init { upgradeToHttpsOnce() }

    /**
     * ONE-TIME UPGRADE, version 1.3.
     *
     * A tablet set up before this version was given "http://<the box>", and
     * the app cannot re-ask: there is no settings screen. Left alone, that
     * tablet would keep loading plain http forever and never be allowed a
     * microphone — the exact fault 1.3 exists to fix. So the saved address
     * is moved to https once, on the first run of the new version.
     *
     * The flag is what keeps `http://` honest: a box with no certificate can
     * still be paired by typing the scheme out in full, and that choice is
     * not undone on the next start.
     */
    private fun upgradeToHttpsOnce() {
        if (prefs.getBoolean("https_done", false)) return
        val current = prefs.getString("base", "") ?: ""
        val edit = prefs.edit().putBoolean("https_done", true)
        if (current.startsWith("http://")) {
            edit.putString("base", "https://" + current.removePrefix("http://"))
        }
        edit.apply()
    }

    var base: String
        get() = prefs.getString("base", "") ?: ""
        set(value) = prefs.edit().putString("base", normalise(value)).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    var deviceName: String
        get() = prefs.getString("device", "") ?: defaultName()
        set(value) = prefs.edit().putString("device", value).apply()

    val paired: Boolean get() = token.isNotEmpty()
    val configured: Boolean get() = base.isNotEmpty()

    fun page(): String = base + "/hattie"

    fun forget() = prefs.edit().clear().apply()

    private fun defaultName(): String = "A " + (Build.MODEL ?: "tablet")

    companion object {
        /**
         * A house is typed in as "192.168.1.10", "hattie.local" or with a
         * scheme already on it. All three mean the same thing, and the app
         * should not make a person care which.
         *
         * BUT THE DEFAULT SCHEME IS https, and that is deliberate. Her page
         * needs a secure origin to be allowed a microphone, so http is a
         * house that can speak but never hear. `http://` typed explicitly is
         * still honoured, for a box that has no certificate yet.
         */
        fun normalise(value: String): String {
            var s = value.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }
}

/**
 * The two conversations this app has with the box: pairing, and a burst.
 *
 * Deliberately plain: HttpURLConnection and the platform's own JSON, so
 * there is nothing to keep updated and nothing that can be out of date on
 * a tablet that has been on a shelf for three years.
 */
object Box {

    /**
     * Spend six digits on a device token. No session, no password: this is
     * the tablet's first conversation with the house, and the code is what
     * the box showed its admin.
     */
    fun pair(house: House, code: String, timeoutMs: Int = 15000): String {
        val body = JSONObject()
            .put("action", "pair")
            .put("code", code)
            .put("device", house.deviceName)
            .toString()
        val (ok, status, text) = postJson(house.base + "/api/winnie", body, timeoutMs)
        if (!ok) return "I could not reach the house (" + status + ")."
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return "The house answered with something I could not read."
        if (json.optBoolean("success", false)) {
            val token = json.optString("token", "")
            if (token.isEmpty()) return "The house did not send a token."
            house.token = token
            house.deviceName = json.optString("device", house.deviceName)
            return ""
        }
        return json.optString("error", "That code was not accepted.")
    }

    /**
     * REMEMBER THIS DEVICE — and this is the route that needs no code.
     *
     * Somebody has signed in on the page with their name, their password and
     * their PIN. That is a stronger credential than six digits, so the app
     * simply asks the box to remember this tablet: it gets a token back and
     * keeps it, and from then on the ear works even if the page is reloaded
     * or the session runs out. Returns "" on success, or the box's sentence.
     *
     * It is tried on every start and every wake-up until it succeeds, because
     * before anybody has signed in there is nothing to remember — and that is
     * not a failure, it is just early.
     */
    fun enrol(house: House, timeoutMs: Int = 15000): String {
        if (house.base.isEmpty()) return "This tablet has not been given a house yet."
        val body = JSONObject()
            .put("action", "device_enrol")
            .put("device", house.deviceName)
            .toString()
        val (_, _, text) = postJson(house.base + "/api/winnie", body, timeoutMs,
                                    cookieHeader(house.base))
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        if (!json.optBoolean("success", false)) return json.optString("error", "")
        val token = json.optString("token", "")
        if (token.isNotEmpty()) house.token = token
        return ""
    }

    /**
     * One burst, in return for one answer.
     *
     * A 16 kHz mono WAV, which is what her ears want and — this matters —
     * needs no ffmpeg on the box, because it is already the format whisper
     * is fed. The ear records exactly that.
     *
     * AUTHENTICATION: the session the tablet already has. The WebView signs
     * in once (at the bench, or by the family), the cookie is kept by
     * Android, and this call carries the same cookie — so a native ear can
     * work today, without the box having to accept device tokens yet.
     *
     * THE READ TIMEOUT IS PART OF THE DESIGN, NOT A DETAIL. It was 40
     * seconds, and the box this was first run on takes about 41 to answer:
     * so the tablet hung up one second before the answer existed and then
     * reported "I could not reach the house", which is what sent us looking
     * at the tablet, the network and the microphone for a day. A caller must
     * always outlast the thing it calls, all the way out:
     *
     *     whisper inside her ears ........  90 s
     *     the ears, from PHP .............  95 s
     *     PHP itself ..................... 110 s
     *     nginx .......................... 120 s
     *     this tablet, waiting for all of it 150 s
     *
     * On a box with modern cores the whole path is one to three seconds and
     * not one of those numbers is ever reached.
     */
    fun room(house: House, wav: ByteArray, timeoutMs: Int = 150000): JSONObject? {
        val url = URL(house.base + "/api/winnie?action=room")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "audio/wav")
            if (house.token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer " + house.token)
            }
            cookieHeader(house.base)?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            conn.outputStream.use { out: OutputStream -> out.write(wav) }
            val text = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            runCatching { JSONObject(text) }.getOrNull()
        } catch (e: SocketTimeoutException) {
            /* TWO DIFFERENT TIMEOUTS, AND THEY MEAN OPPOSITE THINGS.
             * A CONNECT timeout is nothing answering the door at all — a
             * network fault, and "I could not reach the house" is the right
             * sentence for it. A READ timeout is the box having taken the
             * bytes and still working: a slow box being slow. Reporting that
             * as unreachable is what sent us looking at microphones for a
             * day, so only the read timeout gets the box's own explanation. */
            if (e.message?.contains("connect", ignoreCase = true) == true) return null
            runCatching {
                JSONObject()
                    .put("success", false)
                    .put("error", "The house is still working on that — it took longer than "
                            + (timeoutMs / 1000) + " seconds to answer. On a slower box one "
                            + "sentence can take forty seconds or more, so give it a moment "
                            + "and say it again.")
            }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Anything the page needs said, said by the box rather than guessed. */
    private fun cookieHeader(base: String): String? = runCatching {
        CookieManager.getInstance().getCookie(base)
    }.getOrNull()

    private fun postJson(url: String, body: String, timeoutMs: Int,
                         cookie: String? = null): Triple<Boolean, Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            Triple(code in 200..299, code, text)
        } catch (e: Exception) {
            Triple(false, 0, e.message ?: "")
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
