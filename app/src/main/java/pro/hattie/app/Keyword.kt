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
    fun containsName(pcm: ShortArray): Boolean {
        val m = model ?: return true          /* not ready: do not filter */
        val rec = try {
            Recognizer(m, EAR_RATE, GRAMMAR)
        } catch (e: Throwable) {
            return true                        /* never a reason to go deaf */
        }
        return try {
            val block = 3200                    /* a fifth of a second */
            var at = 0
            while (at < pcm.size) {
                val n = minOf(block, pcm.size - at)
                rec.acceptWaveForm(pcm.copyOfRange(at, at + n), n)
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
