package pro.hattie.app

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * HER NAME, HEARD ON THE TABLET.
 *
 * WHY THIS EXISTS. Until now every loud thing in the room was recorded and
 * sent to the box: a television advert, a door, a chair. On a 2011 CPU that
 * is four and a half seconds of transcription for two seconds of sound, and
 * the box was spending it on EastEnders. It also meant whisper had to guess
 * whether "Patty" in somebody else's conversation was meant for her.
 *
 * THIS IS THE FIX, AND IT IS A SMALL MODEL DOING ONE JOB: listen to the
 * burst locally, in a grammar containing three phrases and nothing else.
 * If her name is not in it, nothing is sent, nothing is transcribed and
 * nothing costs anything. If it is, the burst goes to the box exactly as
 * before and the box does what it always did.
 *
 * WHY VOSK AND NOT WHISPER ON THE TABLET: whisper needs context to guess
 * words it does not know, which is why it invents "Patty". A recogniser
 * constrained to three phrases cannot invent anything - it either matches
 * one of them or reports [unk].
 *
 * THE FALLBACK MATTERS AS MUCH AS THE FEATURE. If the model is missing or
 * refuses to load, [ready] stays false and the ear behaves exactly as it
 * did before: every burst is sent. She never goes deaf because of this.
 */
object Keyword {

    /** The three ways somebody says her name. Grammar mode accepts only these. */
    private const val GRAMMAR = """["hattie", "hey hattie", "hi hattie", "[unk]"]"""

    private const val ASSET = "model-en-us.zip"
    private const val FOLDER = "kws-model"

    /** The ear records at this rate; Vosk has to be told the same one. */
    private const val EAR_RATE = 16000.0f

    @Volatile var ready = false
        private set

    private var model: Model? = null

    /**
     * Unpack the model from the APK once, then open it. Called from the ear
     * service on a background thread; nothing waits for it, and until it
     * finishes every burst is sent as it used to be.
     */
    fun prepare(context: Context) {
        if (ready || model != null) return
        synchronized(this) {
            if (ready || model != null) return
            val dir = File(context.filesDir, FOLDER)
            try {
                if (!File(dir, "conf/model.conf").exists()) {
                    unpack(context, dir)
                }
                model = Model(dir.absolutePath)
                ready = true
            } catch (e: Throwable) {
                /* Reported, not hidden. She still listens - she just cannot
                 * check for her name first, so everything is sent as before. */
                ready = false
                model = null
                File(dir, ".failed").writeText(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Is her name in this burst?
     *
     * One recogniser per burst, thrown away afterwards: a fresh one cannot
     * carry the previous sentence's state into this one, and the cost of
     * creating one is nothing next to the model that is already in memory.
     */
    /**
     * Lift the burst to a level the recogniser can work with, and report
     * how quiet it was.
     *
     * WHY THIS IS HERE AND NOT ON THE BOX. Measured on 9 Nov 2026, a foot
     * from the tablet: peak 0.11-0.14, six times quieter than a microphone
     * should be. The box now lifts a quiet burst before whisper - but that
     * happens AFTER this filter, and a quiet burst was being thrown away
     * here, so nothing ever reached the box to be lifted. She only answered
     * when Simon raised his voice, which is exactly the symptom: the gate
     * was failing, not the ears.
     *
     * Only ever upward, never more than 8x, and silence is left alone.
     */
    private fun boost(pcm: ShortArray): ShortArray {
        /* abs(), not "if (v < 0) -v else v": the two branches would be an Int
           and a Short, so Kotlin infers the common supertype
           {Comparable<*> & Number} and refuses to assign it to an Int. It
           compiled in my head and failed on the runner, 10 Nov 2026. */
        var peak = 0
        for (v in pcm) { val a = kotlin.math.abs(v.toInt()); if (a > peak) peak = a }
        val p = peak / 32768.0
        if (p < 0.01) return pcm                      /* silence stays silence */
        var gain = 0.75 / p
        if (gain <= 1.05) return pcm
        if (gain > 8.0) gain = 8.0
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            var x = (pcm[i] / 32768.0) * gain
            if (x > 0.97) x = 0.97 else if (x < -0.97) x = -0.97
            out[i] = (x * 32767).toInt().toShort()
        }
        return out
    }

    fun containsName(pcm: ShortArray): Boolean {
        val m = model ?: return true          /* not ready: do not filter */
        val heard = boost(pcm)                /* a quiet room is not a silence */
        val rec = try {
            Recognizer(m, EAR_RATE, GRAMMAR)
        } catch (e: Throwable) {
            return true                        /* never a reason to go deaf */
        }
        return try {
            val block = 3200                    /* a fifth of a second */
            var at = 0
            while (at < heard.size) {
                val n = minOf(block, heard.size - at)
                rec.acceptWaveForm(heard.copyOfRange(at, at + n), n)
                at += n
            }
            val text = (runCatching { rec.finalResult }.getOrNull() ?: "").lowercase()
            text.contains("hattie") || text.contains("hatty")
        } catch (e: Throwable) {
            true
        } finally {
            runCatching { rec.close() }
        }
    }

    /** ZIP out of the APK, into a folder Vosk can open. */
    private fun unpack(context: Context, dir: File) {
        dir.mkdirs()
        context.assets.open(ASSET).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(dir, entry.name)
                    /* A zip entry may try to escape the folder; refuse those. */
                    if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}
