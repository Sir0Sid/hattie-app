package pro.hattie.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Her EARS, natively — and this is the whole reason the app exists.
 *
 * A browser will only open a microphone on a secure origin, which is why
 * the kiosk route needed a certificate, a name and a DNS record in every
 * house. An app is not held to that, so this runs on plain http on the
 * family's own network and nothing has to be set up in anybody's router.
 *
 * HOW IT LISTENS, and the shape is the same one her page uses, on purpose:
 * the microphone is held open by a FOREGROUND SERVICE (Android insists a
 * microphone is visible, and it is right to), the *volume* of the room is
 * watched here, and only a BURST of speech is sent to the box. Nothing is
 * streamed, nothing is written to disk except the WAV being sent, and a
 * quiet room sends nothing at all.
 *
 * Whether her name was in the burst, and what she answers, is decided on
 * the box — so a tablet and a typed sentence obey exactly the same rule.
 */
class EarService : Service() {

    companion object {
        const val CHANNEL_ID = "hattie-ear"
        const val NOTIFICATION_ID = 41

        /* The four numbers that tune it, in one place, matching the four on
         * her page. See voice-services/README.md, section 8. */
        const val LOUD = 0.055
        const val QUIET = 0.030
        const val FRAMES_TO_START = 3
        const val FRAMES_TO_END = 8
        const val MAX_SECONDS = 12.0

        const val SAMPLE_RATE = 16000

        fun start(context: Context) {
            val intent = Intent(context, EarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EarService::class.java))
        }
    }

    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var speaking = false
    private var player: MediaPlayer? = null
    private lateinit var house: House

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        house = House(this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (house.base.isEmpty()) {
            notice("This tablet has not been given a house yet.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice("The microphone was refused, so I cannot hear the room.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        worker = Thread { listenLoop() }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    /** The notification IS the promise: a microphone in a house is visible. */
    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.channel_description)
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            /* The version is in the title ON PURPOSE: "is this the build I
               think it is?" has to be answerable on the tablet, in one look,
               without a cable and without guessing. */
            .setContentTitle(getString(R.string.app_name) + " " + appVersion())
            .setContentText(getString(R.string.listening))
            .setSmallIcon(R.drawable.ic_ear)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notice(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        manager.notify(NOTIFICATION_ID + 1, Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_ear)
            .build())
    }

    /**
     * One microphone, one buffer, one state machine, exactly like the one in
     * her page — and the same two guards: nothing is sent while she is
     * speaking (a microphone and a speaker in one room hear each other), and
     * one burst is one sentence, not a monologue.
     */
    private fun listenLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE) * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            notice("The microphone could not be opened on this tablet.")
            stopSelf()
            return
        }
        val chunk = ShortArray(SAMPLE_RATE / 10)          // a tenth of a second
        val burst = ArrayList<Short>()
        var loud = 0
        var quiet = 0
        var hearing = false
        var began = 0L

        record.startRecording()
        try {
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                if (speaking) {                            // she is talking: ignore the room
                    loud = 0
                    if (hearing) { hearing = false; burst.clear() }
                    continue
                }

                val peak = peakOf(chunk, read)
                if (hearing) {
                    burst.addAll(chunk.take(read))
                    if (peak < QUIET) quiet++ else quiet = 0
                    val seconds = (System.currentTimeMillis() - began) / 1000.0
                    if (seconds >= MAX_SECONDS || quiet >= FRAMES_TO_END) {
                        hearing = false
                        val pcm = burst.toShortArray()
                        burst.clear()
                        sendBurst(pcm)
                    }
                } else if (peak > LOUD) {
                    if (++loud >= FRAMES_TO_START) {
                        loud = 0
                        hearing = true
                        quiet = 0
                        began = System.currentTimeMillis()
                        burst.clear()
                        burst.addAll(chunk.take(read))
                    }
                } else {
                    loud = 0
                }
            }
        } catch (e: Exception) {
            notice("Listening stopped: " + (e.message ?: "unknown"))
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun peakOf(chunk: ShortArray, count: Int): Double {
        var peak = 0.0
        for (i in 0 until count) {
            val v = abs(chunk[i].toInt()) / 32768.0
            if (v > peak) peak = v
        }
        /* The page measures a peak too; a mean is used here only to make the
         * numbers comparable in the log below. */
        return peak
    }

    /**
     * SAY WHEN SOMETHING IS WRONG. A listening appliance that goes quiet is
     * indistinguishable from a broken one, and the two commonest failures are
     * both fixable in a minute: the box cannot be reached, or her ears cannot
     * hear (no whisper engine). So a refusal is reported in the notification,
     * once per quarter of a minute rather than at every sound in the room.
     */
    /** Which build is this? Shown in the notification. */
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private var lastComplaint = 0L

    private fun complain(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastComplaint < 15000) return
        lastComplaint = now
        notice(text)
    }

    /** One burst to the box, and her answer out loud. */
    private fun sendBurst(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        /* Nothing shorter than a quarter of a second is speech worth
         * sending: a door, a chair or the telly's advert would go to whisper
         * and cost the box a whole transcription.
         *
         * Do not put a small number in that sentence. This comment used to
         * say "a couple of seconds of the box's two cores", and the measured
         * truth on the box it was written for is about FORTY seconds of both
         * cores per burst — which is why every timeout in the chain had to be
         * rewritten (see Box.room). A burst costs whatever a transcription
         * costs, and on a weak CPU that is a lot. */
        if (pcm.size < SAMPLE_RATE / 4) return
        val wav = wavFrom(pcm)
        val answer = Box.room(house, wav)
        if (answer == null) {
            complain("I could not reach the house. Is the box on this network?")
            return
        }
        if (!answer.optBoolean("success", false)) {
            complain(answer.optString("error", "The house refused that."))
            return
        }
        if (!answer.optBoolean("woken", false)) return          // not for her: stay quiet
        val say = answer.optString("say", "")
        if (say.isNotEmpty()) speak(say)
    }

    /** Her voice, base64 WAV, played by the app so it does not depend on the
     *  page being on screen at that moment. */
    private fun speak(base64: String) {
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        val file = File(cacheDir, "answer.wav")
        runCatching { file.writeBytes(bytes) }.onFailure { return }
        speaking = true
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { speaking = false }
                setOnErrorListener { _, _, _ -> speaking = false; true }
                prepare()
                start()
            }
        }.onFailure { speaking = false }
    }

    /**
     * A 16 kHz mono 16-bit WAV, built here rather than converted on the box.
     * This is why her ears need no ffmpeg: what the microphone gives is
     * already what whisper is fed.
     */
    private fun wavFrom(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        fun putInt(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value shr 8) and 0xff).toByte()
            out[offset + 2] = ((value shr 16) and 0xff).toByte()
            out[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        putInt(4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        putInt(16, 16)                                  // PCM header size
        putShort(20, 1)                                 // PCM
        putShort(22, 1)                                 // mono
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)                     // byte rate
        putShort(32, 2)                                 // block align
        putShort(34, 16)                                // bits per sample
        "data".toByteArray().copyInto(out, 36)
        putInt(40, dataSize)
        var offset = 44
        for (sample in pcm) {
            out[offset] = (sample.toInt() and 0xff).toByte()
            out[offset + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
            offset += 2
        }
        return out
    }

    /** Kept for the day the volume needs smoothing; unused today. */
    @Suppress("unused")
    private fun rmsOf(chunk: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) sum += chunk[i].toDouble() * chunk[i]
        return sqrt(sum / maxOf(1, count)) / 32768.0
    }
}

/**
 * THERE IS NO BOOT RECEIVER ANY MORE, AND THAT IS DELIBERATE.
 *
 * It existed to bring the background ear back after a power cut. The ear and
 * her page cannot both hold the microphone — Android gives it to one caller
 * and answers the other with NotReadableError — so the page is now the only
 * listener, and starting a service at boot to take the microphone away from
 * it would recreate the fault every time the tablet was switched on.
 *
 * EarService is kept in this file rather than deleted: if background
 * listening is ever wanted back, it returns as a choice — the page's room
 * mode off, the service on — and never as two things at once.
 */
