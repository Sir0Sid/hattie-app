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

        /* THE GATES ARE NOW RELATIVE TO THE ROOM, not fixed numbers.
         *
         * They were 0.055 and 0.030, chosen when we assumed a healthy
         * microphone. On the Galaxy Tab a normal voice at two metres peaks
         * around 0.05 - BELOW the gate - so the ear never opened, and the
         * only way to be heard was to raise your voice. The tablet was not
         * deaf; it was told to listen only for shouting.
         *
         * These are the FLOOR and the multipliers: the gate is the room's
         * own noise plus a margin, so a quiet room opens at a whisper and a
         * noisy one demands more. LOUD/QUIET names are kept because the rest
         * of the loop reads them. 10 Nov 2026. */
        const val FLOOR = 0.010          // never open a burst below this
        const val LOUD_MULT = 3.0        // speech is this much above the room
        const val QUIET_MULT = 2.0       // and it ends when it falls back
        const val FRAMES_TO_START = 3
        const val FRAMES_TO_END = 8

        /* HOW MUCH AUDIO GOES BACK BEFORE THE NOISE THAT WOKE US.
         *
         * This is the fix for the fault found on a real tablet on
         * 31 Oct 2026. The ear used to start recording at the moment the
         * room got loud, which loses the beginning of the sentence - and the
         * beginning is her NAME, usually the shortest and quietest word in
         * it. The box's own log caught it exactly:
         *
         *   "what time is it?"   woken no
         *   "what is the time?"  woken no
         *
         * He had said "Hattie" first, every time. It was recorded after she
         * had already decided whether to listen. So the last second and a
         * half of the room is kept in a ring buffer and prepended when a
         * burst begins: the words before the noise are part of the burst.
         */
        const val PRE_ROLL_SECONDS = 1.5

        /* Three and a half seconds, not six. Measured on the real box on
         * 31 Oct 2026: transcription costs about two seconds of CPU for
         * every second of audio on that machine, so the length of the burst
         * IS the delay. A request to her is one short sentence, and the
         * pre-roll above already puts the front of it in. */
        const val MAX_SECONDS = 3.5

        const val SAMPLE_RATE = 16000
        const val PRE_ROLL = (PRE_ROLL_SECONDS * SAMPLE_RATE).toInt()

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
        /* Her name is checked on this tablet before anything is sent. It
         * takes a few seconds to unpack and open the model the first time;
         * until then every burst goes to the box exactly as before, so a
         * failure here costs the saving and nothing else. */
        Thread { Keyword.prepare(applicationContext) }.start()
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
    /**
     * The room profile first, and the old one only if the device refuses.
     * A tablet that will not open VOICE_COMMUNICATION must still have ears -
     * a quieter her is better than a deaf one.
     */
    /* Which profile the microphone actually opened with, for the
       notification: "comm" is the room profile, "mic" the plain fallback.
       Written on 10 Nov 2026 because "is she using the tablet's microphone
       at all?" could not be answered from the tablet, only guessed at. */
    @Volatile private var micSource = "?"

    private fun openMicrophone(bufferSize: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        /* RAW MIC FIRST, as an A/B test on 10 Nov 2026. VOICE_COMMUNICATION
           applies noise suppression and a high-pass filter: good for a phone
           call, and quite capable of removing a breathy "H" from a quiet
           voice two metres away. MIC is unprocessed, so nothing is taken
           away - but nothing is lifted either.

           The notification says which one opened ("mic" or "comm") and the
           peak beside it, so the comparison is one sentence each, not a
           guess. If "mic" is no louder, this goes back the way it was. */
        for (source in intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
            val r = try {
                /* The BUILDER, not the five-argument constructor: that form
                 * is deprecated, and on compileSdk 34 the compiler is within
                 * its rights to refuse it. This one is the documented route
                 * and exists from API 23, well below the app's minimum. */
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: Throwable) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                micSource = if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    "comm" else "mic"
                return r
            }
            try { r?.release() } catch (e: Throwable) { }
        }
        return null
    }

    private fun listenLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE) * 2
        /* FAR-FIELD, NOT CLOSE-TALK.
           This was VOICE_RECOGNITION, which is the profile for dictating with
           the tablet in your hand: flat, no automatic gain. In a room it
           measured 0.11-0.14 peak from a foot and less beyond it, and Simon
           had to raise his voice to be heard.

           VOICE_COMMUNICATION is the profile built for a device in a room: it
           enables the microphone array where the tablet has one, applies
           automatic gain so quiet speech arrives at a usable level, and
           cancels the echo of her own speaker - which also stops her hearing
           her own sentence. That last part matters more here than anywhere
           else, because the same device plays her voice.

           MIC and UNPROCESSED were the alternatives; both are raw and would
           leave the level problem where it started. Measured 10 Nov 2026. */
        val record = openMicrophone(bufferSize)
        if (record == null) {
            notice("The microphone could not be opened on this tablet.")
            stopSelf()
            return
        }
        val chunk = ShortArray(SAMPLE_RATE / 10)          // a tenth of a second
        val burst = ArrayList<Short>()
        /* The room's last second and a half, kept so the front of a
         * sentence is never lost. Only ever 24,000 numbers in memory. */
        val preRoll = ArrayDeque<Short>(PRE_ROLL)
        fun preRollPush(arr: ShortArray, count: Int) {
            for (i in 0 until count) {
                if (preRoll.size >= PRE_ROLL) preRoll.removeFirst()
                preRoll.addLast(arr[i])
            }
        }
        var loud = 0
        var quiet = 0
        var hearing = false
        var began = 0L

        record.startRecording()
        /* THE LEVEL, ON THE TABLET. Every ~1.5 seconds the notification says
           which microphone opened and how loud the room is. peak 0.10 is a
           quiet room, 0.40 is speech at a metre. If it reads 0.00 while you
           talk, the microphone is delivering silence - permission, another
           app holding it, or hardware - and no amount of gain will help.
           Added 10 Nov 2026, because this question was being answered by
           guessing at three devices in turn. */
        /* Double, because peakOf() returns one. An Int here does not compile:
           "peak > diagMax" compares a Double with an Int, which Kotlin refuses.
           That is what failed two CI runs on 10 Nov 2026 - and a hand-attached
           old APK then made the release look successful. */
        /* It starts assuming a quiet room; the first few seconds of silence
           settle it on this room's real floor. */
        var roomLevel = FLOOR
        var diagMax = 0.0
        var diagTicks = 0
        try {
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                if (speaking) {                            // she is talking: ignore the room
                    loud = 0
                    /* Her own words must not become the next burst's
                     * pre-roll, or she wakes herself. */
                    preRoll.clear()
                    if (hearing) { hearing = false; burst.clear() }
                    continue
                }

                val peak = peakOf(chunk, read)
                if (peak > diagMax) diagMax = peak
                if (++diagTicks >= 15) {                   /* a second and a half */
                    diagTicks = 0
                    val shown = String.format(java.util.Locale.UK, "%.2f", diagMax)
                    notice("Listening (" + micSource + ") · peak " + shown)
                    diagMax = 0.0
                }
                /* The room's own level, followed slowly: down quickly when
                   it is quieter, up slowly so a cough cannot raise the gate
                   for the next sentence. */
                if (!hearing && peak < roomLevel * 2.0) {
                    roomLevel = roomLevel * 0.995 + peak * 0.005
                }
                val gateLoud  = maxOf(FLOOR, roomLevel * LOUD_MULT)
                val gateQuiet = maxOf(FLOOR * 0.8, roomLevel * QUIET_MULT)

                if (hearing) {
                    burst.addAll(chunk.take(read))
                    if (peak < gateQuiet) quiet++ else quiet = 0
                    val seconds = (System.currentTimeMillis() - began) / 1000.0
                    if (seconds >= MAX_SECONDS || quiet >= FRAMES_TO_END) {
                        hearing = false
                        val pcm = burst.toShortArray()
                        burst.clear()
                        sendBurst(pcm)
                    }
                } else if (peak > gateLoud) {
                    if (++loud >= FRAMES_TO_START) {
                        loud = 0
                        hearing = true
                        quiet = 0
                        began = System.currentTimeMillis()
                        burst.clear()
                        /* THE WORDS BEFORE THE NOISE ARE PART OF THE
                         * SENTENCE. "Hattie" is spoken, and only then is the
                         * room loud enough to wake the recorder - so the
                         * sentence is built from what was already in memory,
                         * then this frame. Without this, every burst begins
                         * one word late and her name is the word that goes. */
                        burst.addAll(preRoll)
                        burst.addAll(chunk.take(read))
                        preRoll.clear()
                    }
                } else {
                    loud = 0
                }

                if (!hearing) preRollPush(chunk, read)
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
        /* IS HER NAME IN IT? If the model is loaded, a burst without her
         * name never leaves this tablet: no network, no transcription, no
         * cost, and no chance of the box answering the television. Not
         * ready means not filtering - see Keyword.containsName. */
        if (Keyword.ready && !Keyword.containsName(pcm)) return
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
 * A tablet that has been switched off at the wall comes back listening.
 * That is the difference between an appliance and a gadget - and since
 * 31 Oct 2026 it is the ONLY listener: her page has no microphone code of
 * its own any more, so there is nothing here to fight with.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val house = House(context)
        if (house.configured) EarService.start(context)
    }
}
