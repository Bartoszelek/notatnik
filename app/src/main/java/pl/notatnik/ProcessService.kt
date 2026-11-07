package pl.notatnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Przetwarzanie nagrań w tle: transkrypcja, mówcy, podsumowanie, e-mail, kompresja. */
class ProcessService : Service() {
    companion object {
        @Volatile var busyId: String? = null
        var listener: (() -> Unit)? = null

        fun start(c: Context, id: String? = null) {
            c.startForegroundService(Intent(c, ProcessService::class.java).putExtra("id", id))
        }

        fun pending(): List<Note> = Store.notes.filter {
            it.type == "meeting" && it.deleted == 0L && it.audio.isNotEmpty() && MeetMeta.of(it).status.let { s -> s == "pending" || s == "processing" }
        }

        /** Usuwa stary dźwięk przetworzonych nagrań (tekst zostaje). */
        fun cleanup(c: Context) {
            MeetCfg.load(c)
            if (MeetCfg.keepDays <= 0) return
            val limit = System.currentTimeMillis() - MeetCfg.keepDays * 86_400_000L
            var changed = false
            for (n in Store.notes) {
                if (n.type != "meeting" || n.audio.isEmpty() || n.created > limit) continue
                if (MeetMeta.of(n).status != "done") continue
                File(n.audio).delete()
                n.audio = ""
                changed = true
            }
            if (changed) Store.save()
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private val queue = LinkedHashSet<String>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        MeetCfg.load(this)
        val n = notif("Przygotowuję przetwarzanie…", 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(11, n)
        synchronized(queue) {
            intent?.getStringExtra("id")?.let { queue.add(it) }
            pending().forEach { queue.add(it.id) }
        }
        if (worker?.isAlive != true) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notatnik:process")
                .apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String, pct: Int, done: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("proc", "Przetwarzanie nagrań", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java).putExtra("area", "recordings"), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "proc")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_mic))
            .setContentTitle(if (done) "Nagranie przetworzone" else "Przetwarzam nagranie")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (!done) b.setOngoing(true).setProgress(100, pct, pct <= 0)
        else b.setAutoCancel(true)
        return b.build()
    }

    private var lastUi = 0L

    private fun report(n: Note, m: MeetMeta, stage: String, pct: Int) {
        m.stage = stage
        m.progress = pct
        val now = System.currentTimeMillis()
        if (now - lastUi < 1500 && pct in 1..99) return
        lastUi = now
        n.meta = m.toJson()
        try { getSystemService(NotificationManager::class.java)?.notify(11, notif("$stage – $pct%", pct)) } catch (e: SecurityException) {}
        h.post { listener?.invoke() }
    }

    private fun loop() {
        while (true) {
            val id = synchronized(queue) { queue.firstOrNull()?.also { queue.remove(it) } } ?: break
            val n = Store.note(id) ?: continue
            if (n.type != "meeting" || n.audio.isEmpty()) continue
            busyId = id
            try {
                process(n)
            } catch (e: Throwable) {
                val m = MeetMeta.of(n)
                m.status = "error"
                m.error = (e.message ?: e.javaClass.simpleName).take(300)
                n.meta = m.toJson()
                Store.touch(n); Store.save()
            }
            busyId = null
            h.post { listener?.invoke() }
        }
        try { wake?.release() } catch (e: Exception) {}
        h.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun charging(): Boolean = (getSystemService(BatteryManager::class.java))?.isCharging == true

    private fun process(n: Note) {
        val m = MeetMeta.of(n)
        m.status = "processing"; m.error = ""
        n.meta = m.toJson(); Store.save()

        // długie nagrania tylko przy ładowarce (jeśli tak ustawiono)
        if (MeetCfg.onlyCharging && n.audioMs > 20 * 60_000L) {
            while (!charging()) {
                report(n, m, "Czeka na ładowarkę", 0)
                Thread.sleep(30_000)
            }
        }

        // 1. modele
        if (!Models.ready(this, MeetCfg.model)) {
            Models.ensure(this, MeetCfg.model) { p, s -> report(n, m, s, p) }
        }

        // 2. dźwięk → WAV 16 kHz
        val src = File(n.audio)
        if (!src.exists()) throw IllegalStateException("Brak pliku nagrania")
        var temp: File? = null
        val wav = if (src.extension.equals("wav", true) && isOurWav(src)) src else {
            val t = File(cacheDir, "proc_${n.id}.wav")
            AudioConv.toWav16k(src.path, t) { p -> report(n, m, "Przygotowuję dźwięk", p) }
            temp = t
            t
        }

        // 3. rozpoznawanie mowy + odciski głosu
        report(n, m, "Rozpoznaję mowę", 1)
        val tr = Transcriber(this, MeetCfg.model, m.lang)
        val segs = try {
            tr.run(wav) { p -> report(n, m, "Rozpoznaję mowę", p.coerceIn(1, 99)) }
        } finally {
            tr.release()
        }
        segs.forEach { it.text = DictFix.apply(it.text, MeetCfg.dict) }

        // 4. kto mówi
        report(n, m, "Rozpoznaję mówców", 99)
        val who = Spk.assign(segs, Voices.all(this))
        val sp = JSONObject()
        for ((k, v) in who.names) sp.put(k.toString(), v)
        val ce = JSONObject()
        for ((k, v) in who.centroids) ce.put(k.toString(), JSONArray(v.map { it.toDouble() }))
        m.speakers = sp
        m.cents = ce
        m.segs = JSONArray(segs.map { it.toJson() })

        val transcript = segs.joinToString("\n") { "[" + MeetingService.stamp(it.start) + "] " + m.speaker(it.spk) + ": " + it.text }
        val markLines = m.marks.map { t ->
            val s = segs.lastOrNull { it.start <= t + 2000 } ?: segs.firstOrNull()
            "[" + MeetingService.stamp(t) + "] " + (s?.let { m.speaker(it.spk) + ": " + it.text } ?: "")
        }

        // 5. podsumowanie
        report(n, m, if (m.offlineOnly) "Podsumowuję (offline)" else "Podsumowuję", 99)
        val df = SimpleDateFormat(if (m.lang == "en") "EEEE, d MMMM yyyy, HH:mm" else "EEEE, d MMMM yyyy, HH:mm", if (m.lang == "en") Locale.ENGLISH else Locale("pl"))
        val date = df.format(Date(if (m.startedAt > 0) m.startedAt else n.created)) + " · " + MeetingService.stamp(n.audioMs)
        val sum = if (segs.isEmpty()) Summary(listOf(if (m.lang == "en") "No speech detected." else "Nie wykryto mowy w nagraniu."), emptyList(), emptyList(), emptyList(), "offline")
        else Summarizer.summarize(transcript, markLines, m.template, m.lang, m.offlineOnly, MeetCfg.dict)
        val text = Summarizer.format(sum, n.title, date, m.lang, markLines)
        n.body = text
        n.ocr = segs.joinToString(" ") { it.text }
        n.items.clear()
        sum.tasks.forEach { n.items.add(Item(it)) }
        m.summaryBy = sum.by
        if (sum.by == "offline" && !m.offlineOnly && Summarizer.lastError.isNotBlank()) m.error = "Groq niedostępny – użyto podsumowania offline (" + Summarizer.lastError.take(120) + ")"

        // 6. e-mail
        if (m.sendEmail && m.recipients.isNotEmpty() && !m.emailed) {
            if (MeetCfg.mailReady) {
                try {
                    report(n, m, "Wysyłam e-mail", 99)
                    val subj = (if (m.lang == "en") "Meeting summary: " else "Podsumowanie: ") + n.title
                    Mailer.send(m.recipients, subj, text + "\n\n— " + (if (m.lang == "en") "Full transcript is saved in Notatnik." else "Pełna transkrypcja jest zapisana w Notatniku."))
                    m.emailed = true
                } catch (e: Exception) {
                    m.error = (m.error + " E-mail: " + (e.message ?: "błąd")).trim()
                }
            } else m.error = (m.error + " E-mail niewysłany: skonfiguruj pocztę w ustawieniach Nagrań.").trim()
        }

        // 7. kompresja (WAV → M4A, ok. 10× mniej miejsca)
        if (src == wav && src.extension.equals("wav", true)) {
            try {
                report(n, m, "Zmniejszam plik nagrania", 99)
                val out = File(src.parentFile, src.nameWithoutExtension + ".m4a")
                AudioConv.wavToM4a(src, out)
                if (out.length() > 1000) {
                    n.audio = out.path
                    src.delete()
                } else out.delete()
            } catch (e: Exception) {
            }
        }
        temp?.delete()

        m.status = "done"
        m.stage = ""
        m.progress = 100
        n.meta = m.toJson()
        Store.touch(n)
        Store.save()
        try {
            getSystemService(NotificationManager::class.java)?.notify(12, notif(n.title + (if (m.emailed) " · wysłano e-mail" else ""), 100, true))
        } catch (e: SecurityException) {
        }
    }

    /** Nasz WAV ma 16 kHz mono 16 bit – inne WAV-y trzeba przekonwertować. */
    private fun isOurWav(f: File): Boolean = try {
        java.io.RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(44); r.readFully(b)
            val rate = (b[24].toInt() and 0xff) or ((b[25].toInt() and 0xff) shl 8) or ((b[26].toInt() and 0xff) shl 16)
            val ch = b[22].toInt()
            val bits = b[34].toInt()
            rate == Wav.RATE && ch == 1 && bits == 16
        }
    } catch (e: Exception) {
        false
    }
}
