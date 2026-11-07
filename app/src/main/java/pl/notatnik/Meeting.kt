package pl.notatnik

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/** Ustawienia nagrań spotkań. */
object MeetCfg {
    private const val P = "meet"
    var email = ""          // domyślny adres na podsumowania
    var send = true         // domyślnie wysyłaj
    var smtpHost = ""
    var smtpPort = 465
    var smtpUser = ""
    var smtpPass = ""
    var smtpTls = "ssl"     // ssl albo starttls
    var groqKey = ""
    var lang = "auto"       // pl, en, auto
    var template = "meeting" // meeting, client, shift
    var dict = ""           // słownik nazw, po przecinku lub w liniach
    var keepDays = 0        // 0 = nie usuwaj audio
    var onlyCharging = false
    var offlineOnly = false
    var model = "small"     // small albo medium (Whisper)

    fun load(c: Context) {
        val p = c.getSharedPreferences(P, Context.MODE_PRIVATE)
        email = p.getString("email", "") ?: ""
        send = p.getBoolean("send", true)
        smtpHost = p.getString("smtpHost", "") ?: ""
        smtpPort = p.getInt("smtpPort", 465)
        smtpUser = p.getString("smtpUser", "") ?: ""
        smtpPass = p.getString("smtpPass", "") ?: ""
        smtpTls = p.getString("smtpTls", "ssl") ?: "ssl"
        groqKey = p.getString("groqKey", "") ?: ""
        lang = p.getString("lang", "auto") ?: "auto"
        template = p.getString("template", "meeting") ?: "meeting"
        dict = p.getString("dict", "") ?: ""
        keepDays = p.getInt("keepDays", 0)
        onlyCharging = p.getBoolean("onlyCharging", false)
        offlineOnly = p.getBoolean("offlineOnly", false)
        model = p.getString("model", "small") ?: "small"
    }

    fun save(c: Context) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putString("email", email).putBoolean("send", send)
            .putString("smtpHost", smtpHost).putInt("smtpPort", smtpPort).putString("smtpUser", smtpUser)
            .putString("smtpPass", smtpPass).putString("smtpTls", smtpTls).putString("groqKey", groqKey)
            .putString("lang", lang).putString("template", template).putString("dict", dict)
            .putInt("keepDays", keepDays).putBoolean("onlyCharging", onlyCharging).putBoolean("offlineOnly", offlineOnly)
            .putString("model", model)
            .apply()
    }

    val mailReady get() = smtpHost.isNotBlank() && smtpUser.isNotBlank() && smtpPass.isNotBlank()

    fun templateName(t: String) = when (t) {
        "client" -> "Rozmowa z klientem"
        "shift" -> "Odprawa zmiany"
        else -> "Spotkanie"
    }

    fun langName(l: String) = when (l) {
        "pl" -> "Polski"
        "en" -> "Angielski"
        else -> "Automatycznie"
    }
}

/** Dane spotkania trzymane w Note.meta (JSON). */
class MeetMeta(
    var status: String = "pending",   // pending, processing, done, error
    var recipients: MutableList<String> = mutableListOf(),
    var sendEmail: Boolean = false,
    var emailed: Boolean = false,
    var offlineOnly: Boolean = false,
    var template: String = "meeting",
    var lang: String = "auto",
    var marks: MutableList<Long> = mutableListOf(),
    var startedAt: Long = 0,
    var endedAt: Long = 0,
    var source: String = "rec",       // rec, import
    var calendar: String = "",
    var error: String = "",
    var segs: JSONArray = JSONArray(),        // wypowiedzi: s, e, k (mówca), t
    var speakers: JSONObject = JSONObject(),  // numer mówcy → imię
    var cents: JSONObject = JSONObject(),     // numer mówcy → odcisk głosu (do zapamiętania)
    var summaryBy: String = "",
    var progress: Int = 0,
    var stage: String = ""
) {
    fun speaker(k: Int): String = speakers.optString(k.toString()).ifBlank { "Osoba ${k + 1}" }

    fun toJson(): String = JSONObject().put("status", status).put("recipients", JSONArray(recipients))
        .put("sendEmail", sendEmail).put("emailed", emailed).put("offlineOnly", offlineOnly)
        .put("template", template).put("lang", lang).put("marks", JSONArray(marks))
        .put("startedAt", startedAt).put("endedAt", endedAt).put("source", source)
        .put("calendar", calendar).put("error", error).put("segs", segs).put("speakers", speakers)
        .put("cents", cents).put("summaryBy", summaryBy).put("progress", progress).put("stage", stage).toString()

    companion object {
        fun of(n: Note): MeetMeta = parse(n.meta)

        fun parse(s: String): MeetMeta {
            if (s.isBlank()) return MeetMeta()
            return try {
                val o = JSONObject(s)
                val r = o.optJSONArray("recipients") ?: JSONArray()
                val m = o.optJSONArray("marks") ?: JSONArray()
                MeetMeta(
                    o.optString("status", "pending"),
                    (0 until r.length()).map { r.optString(it) }.filter { it.isNotBlank() }.toMutableList(),
                    o.optBoolean("sendEmail"), o.optBoolean("emailed"), o.optBoolean("offlineOnly"),
                    o.optString("template", "meeting"), o.optString("lang", "auto"),
                    (0 until m.length()).map { m.optLong(it) }.toMutableList(),
                    o.optLong("startedAt"), o.optLong("endedAt"), o.optString("source", "rec"),
                    o.optString("calendar"), o.optString("error"),
                    o.optJSONArray("segs") ?: JSONArray(), o.optJSONObject("speakers") ?: JSONObject(),
                    o.optJSONObject("cents") ?: JSONObject(), o.optString("summaryBy"), o.optInt("progress"), o.optString("stage")
                )
            } catch (e: Exception) {
                MeetMeta()
            }
        }
    }
}

/** Plik WAV 16 kHz mono 16 bit – zapisywany na bieżąco, nagłówek poprawiany co kilka sekund. */
object Wav {
    const val RATE = 16_000

    fun header(dataLen: Long): ByteArray {
        val b = ByteArray(44)
        fun s(o: Int, t: String) = t.toByteArray().copyInto(b, o)
        fun i32(o: Int, v: Long) { b[o] = (v and 0xff).toByte(); b[o + 1] = ((v shr 8) and 0xff).toByte(); b[o + 2] = ((v shr 16) and 0xff).toByte(); b[o + 3] = ((v shr 24) and 0xff).toByte() }
        fun i16(o: Int, v: Int) { b[o] = (v and 0xff).toByte(); b[o + 1] = ((v shr 8) and 0xff).toByte() }
        s(0, "RIFF"); i32(4, 36 + dataLen); s(8, "WAVE"); s(12, "fmt ")
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, RATE.toLong()); i32(28, RATE * 2L); i16(32, 2); i16(34, 16)
        s(36, "data"); i32(40, dataLen)
        return b
    }

    /** Poprawia nagłówek po przerwanym nagraniu (np. telefon padł). */
    fun repair(f: File) {
        try {
            RandomAccessFile(f, "rw").use { r ->
                val len = (r.length() - 44).coerceAtLeast(0)
                r.seek(0)
                r.write(header(len))
            }
        } catch (e: Exception) {
        }
    }

    fun durationMs(f: File): Long = ((f.length() - 44).coerceAtLeast(0) / 2) * 1000 / RATE
}

/** Wysyłka e-mail przez SMTP (własna skrzynka, bez zewnętrznych API). */
object Mailer {
    fun send(to: List<String>, subject: String, body: String) {
        if (!MeetCfg.mailReady) throw IllegalStateException("Uzupełnij ustawienia poczty (SMTP)")
        if (to.isEmpty()) throw IllegalStateException("Brak adresatów")
        val p = Properties()
        p["mail.smtp.host"] = MeetCfg.smtpHost
        p["mail.smtp.port"] = MeetCfg.smtpPort.toString()
        p["mail.smtp.auth"] = "true"
        p["mail.smtp.connectiontimeout"] = "20000"
        p["mail.smtp.timeout"] = "30000"
        if (MeetCfg.smtpTls == "starttls") {
            p["mail.smtp.starttls.enable"] = "true"
            p["mail.smtp.starttls.required"] = "true"
        } else {
            p["mail.smtp.ssl.enable"] = "true"
        }
        val session = javax.mail.Session.getInstance(p, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = javax.mail.PasswordAuthentication(MeetCfg.smtpUser, MeetCfg.smtpPass)
        })
        val msg = javax.mail.internet.MimeMessage(session)
        msg.setFrom(javax.mail.internet.InternetAddress(MeetCfg.smtpUser))
        msg.setRecipients(javax.mail.Message.RecipientType.TO, to.joinToString(",") { it.trim() })
        msg.setSubject(subject, "UTF-8")
        msg.setText(body, "UTF-8")
        javax.mail.Transport.send(msg)
    }

    fun parseList(s: String): List<String> =
        s.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') && it.contains('.') }.distinct()
}

/** Nagrywanie spotkania w tle: bezpieczny zapis, pauza, znaczniki ★, pauza przy połączeniu, ostrzeżenie o baterii. */
class MeetingService : Service() {
    companion object {
        const val ACT_START = "pl.notatnik.MEET_START"
        const val ACT_PAUSE = "pl.notatnik.MEET_PAUSE"
        const val ACT_MARK = "pl.notatnik.MEET_MARK"
        const val ACT_FINISH = "pl.notatnik.MEET_FINISH"

        @Volatile var running = false
        @Volatile var paused = false
        @Volatile var autoPaused = false
        var accMs = 0L
        var resumedAt = 0L
        var startedWall = 0L
        var title = ""
        var recipients: MutableList<String> = mutableListOf()
        var sendEmail = false
        var offlineOnly = false
        var template = "meeting"
        var lang = "auto"
        var calendar = ""
        val marks: MutableList<Long> = mutableListOf()
        var file: File? = null
        var listener: (() -> Unit)? = null
        var onFinished: ((Note) -> Unit)? = null

        fun elapsed(): Long = accMs + if (running && !paused) System.currentTimeMillis() - resumedAt else 0L

        fun stamp(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        /** Plik z ustawieniami nagrania – potrzebny do odzyskania po awarii. */
        fun sidecar(f: File) = File(f.parentFile, f.nameWithoutExtension + ".json")

        /** Odzyskuje nagrania przerwane awarią (WAV bez notatki). */
        fun recover(c: Context): Int {
            if (running) return 0
            Store.init(c)
            val dir = Store.dir("meet")
            val known = Store.notes.map { it.audio }.toSet()
            var n = 0
            dir.listFiles { f -> f.extension == "wav" }?.forEach { f ->
                if (f.path in known || f.length() < 44 + Wav.RATE * 2) return@forEach
                Wav.repair(f)
                val side = sidecar(f)
                val meta = if (side.exists()) MeetMeta.parse(side.readText()) else MeetMeta()
                meta.status = "pending"
                val t = try { JSONObject(side.readText()).optString("title") } catch (e: Exception) { "" }
                val note = Note(type = "meeting", title = (t.ifBlank { "Spotkanie" }) + " (odzyskane)", audio = f.path, audioMs = Wav.durationMs(f))
                note.created = if (meta.startedAt > 0) meta.startedAt else f.lastModified()
                note.meta = meta.toJson()
                Store.notes.add(note)
                Store.touch(note)
                side.delete()
                n++
            }
            if (n > 0) Store.save()
            return n
        }
    }

    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val h = Handler(Looper.getMainLooper())
    private var batteryWarned = false
    private var lastSide = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        MeetCfg.load(this)
        when (intent?.action) {
            ACT_START -> if (!running) start()
            ACT_PAUSE -> if (running) { togglePause(); autoPaused = false }
            ACT_MARK -> if (running) mark()
            ACT_FINISH -> if (running) finish()
        }
        if (!running) stopSelf()
        return START_NOT_STICKY
    }

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("meet", "Nagrywanie spotkań", NotificationManager.IMPORTANCE_LOW))
        fun svc(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, MeetingService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 40, Intent(this, MeetingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val finish = PendingIntent.getActivity(this, 41, Intent(this, MeetingActivity::class.java).putExtra("confirmStop", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, "meet")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_mic))
            .setContentTitle(if (paused) (if (autoPaused) "Wstrzymane – trwa połączenie" else "Nagrywanie wstrzymane") else "Nagrywanie: " + title.ifBlank { "spotkanie" })
            .setContentText("★ ważne: ${marks.size}" + if (paused) " · " + stamp(elapsed()) else "")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                if (paused) "Wznów" else "Pauza", svc(ACT_PAUSE, 42)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_star), "★ Ważne", svc(ACT_MARK, 43)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Zakończ", finish).build())
        if (!paused) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed()).setShowWhen(true)
        return b.build()
    }

    private fun refresh() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(9, notif())
        } catch (e: SecurityException) {
        }
        h.post { listener?.invoke() }
    }

    private fun writeSidecar() {
        val f = file ?: return
        try {
            val m = MeetMeta(recipients = recipients, sendEmail = sendEmail, offlineOnly = offlineOnly, template = template,
                lang = lang, marks = marks.toMutableList(), startedAt = startedWall, calendar = calendar)
            val o = JSONObject(m.toJson()).put("title", title)
            sidecar(f).writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    private fun start() {
        val f = File(Store.dir("meet"), "meet_${System.currentTimeMillis()}.wav")
        file = f
        marks.clear()
        accMs = 0
        paused = false
        autoPaused = false
        startedWall = System.currentTimeMillis()
        resumedAt = startedWall
        batteryWarned = false
        if (Build.VERSION.SDK_INT >= 30) startForeground(9, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(9, notif())
        val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Wav.RATE))
        } catch (e: SecurityException) {
            null
        }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Brak dostępu do mikrofonu", Toast.LENGTH_LONG).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        rec = r
        running = true
        writeSidecar()
        r.startRecording()
        thread = Thread {
            val buf = ByteArray(Wav.RATE) // pół sekundy
            var written = 0L
            var lastFix = System.currentTimeMillis()
            try {
                RandomAccessFile(f, "rw").use { out ->
                    out.write(Wav.header(0))
                    while (running) {
                        val n = r.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        if (!paused) {
                            out.write(buf, 0, n)
                            written += n
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastFix > 3000) {
                            // nagłówek aktualny na bieżąco – po awarii plik jest odtwarzalny
                            val pos = out.filePointer
                            out.seek(0); out.write(Wav.header(written)); out.seek(pos)
                            lastFix = now
                        }
                    }
                    out.seek(0)
                    out.write(Wav.header(written))
                }
            } catch (e: Exception) {
            }
        }.also { it.start() }
        h.post(watch)
        refresh()
    }

    /** Co sekundę: pauza przy rozmowie telefonicznej, ostrzeżenie o baterii. */
    private val watch = object : Runnable {
        override fun run() {
            if (!running) return
            val am = getSystemService(AudioManager::class.java)
            val inCall = am?.mode == AudioManager.MODE_IN_CALL
            if (inCall && !paused) {
                autoPaused = true; togglePause()
            } else if (!inCall && paused && autoPaused) {
                autoPaused = false; togglePause()
            }
            val bm = getSystemService(BatteryManager::class.java)
            val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val charging = bm?.isCharging == true
            if (!charging && lvl in 1..15 && !batteryWarned) {
                batteryWarned = true
                warn("Słaba bateria ($lvl%)", "Nagranie jest zapisywane na bieżąco – nic nie przepadnie. Podłącz ładowarkę.")
            }
            if (!charging && lvl in 1..3) {
                warn("Bateria prawie pusta", "Zakończyłem nagrywanie, żeby je bezpiecznie zapisać.")
                finish(); return
            }
            if (System.currentTimeMillis() - lastSide > 15_000) { writeSidecar(); lastSide = System.currentTimeMillis() }
            h.postDelayed(this, 1000)
        }
    }

    private fun warn(t: String, s: String) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(10, Notification.Builder(this, "meet")
                .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_mic)).setContentTitle(t).setContentText(s)
                .setStyle(Notification.BigTextStyle().bigText(s)).setAutoCancel(true).build())
        } catch (e: SecurityException) {
        }
    }

    private fun togglePause() {
        if (paused) {
            paused = false
            resumedAt = System.currentTimeMillis()
        } else {
            accMs += System.currentTimeMillis() - resumedAt
            paused = true
        }
        refresh()
    }

    private fun mark() {
        marks.add(elapsed())
        writeSidecar()
        refresh()
        Toast.makeText(this, "★ Zaznaczono " + stamp(elapsed()), Toast.LENGTH_SHORT).show()
    }

    private fun finish() {
        if (!running) return
        val total = elapsed()
        running = false
        h.removeCallbacks(watch)
        try { thread?.join(3000) } catch (e: Exception) {}
        try { rec?.stop() } catch (e: Exception) {}
        try { rec?.release() } catch (e: Exception) {}
        rec = null
        val f = file
        if (f != null && f.exists()) {
            Wav.repair(f)
            val df = SimpleDateFormat("d.MM.yyyy HH:mm", Locale("pl"))
            val note = Note(type = "meeting", title = title.ifBlank { MeetCfg.templateName(template) + " " + df.format(Date(startedWall)) },
                audio = f.path, audioMs = total)
            note.created = startedWall
            note.meta = MeetMeta(status = "pending", recipients = recipients.toMutableList(), sendEmail = sendEmail,
                offlineOnly = offlineOnly, template = template, lang = lang, marks = marks.toMutableList(),
                startedAt = startedWall, endedAt = System.currentTimeMillis(), calendar = calendar).toJson()
            Store.notes.add(note)
            Store.touch(note)
            Store.save()
            sidecar(f).delete()
            h.post { onFinished?.invoke(note) }
            try { ProcessService.start(this, note.id) } catch (e: Exception) {}
        }
        file = null
        paused = false
        MeetingWidget.updateAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        h.post { listener?.invoke() }
        stopSelf()
    }

    override fun onDestroy() {
        // awaryjne zamknięcie – plik zostaje, odzyska go recover()
        h.removeCallbacks(watch)
        super.onDestroy()
    }
}

/** Widżet 1×1 „Spotkanie” – otwiera okienko nagrywania. */
class MeetingWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) m.updateAppWidget(id, views(c))
    }

    companion object {
        fun views(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_meeting)
            v.setTextViewText(R.id.meet_label, if (MeetingService.running) "● Nagrywa" else "Spotkanie")
            val pi = PendingIntent.getActivity(c, 44, Intent(c, MeetingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.meet_root, pi)
            return v
        }

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, MeetingWidget::class.java))
            if (ids.isNotEmpty()) for (id in ids) m.updateAppWidget(id, views(c))
        }
    }
}

/** „Udostępnij → Notatnik”: nagranie z innej aplikacji trafia do Nagrań. */
class ImportAudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        var ok = 0
        for (u in uris) if (importAudio(this, u) != null) ok++
        Toast.makeText(this, if (ok > 0) "Dodano do Nagrań: $ok" else "Nie udało się zaimportować nagrania", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        /** Kopiuje plik audio do Notatnika i tworzy wpis w Nagraniach. */
        fun importAudio(c: Context, u: Uri): Note? = try {
            var name = "Nagranie"
            c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) name = cur.getString(0) ?: name
            }
            val ext = name.substringAfterLast('.', "m4a").lowercase().take(5).ifBlank { "m4a" }
            val f = File(Store.dir("meet"), "import_${System.currentTimeMillis()}.$ext")
            c.contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            val mmr = android.media.MediaMetadataRetriever()
            val dur = try {
                mmr.setDataSource(f.path)
                mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                try { mmr.release() } catch (e: Exception) {}
            }
            MeetCfg.load(c)
            val n = Note(type = "meeting", title = name.substringBeforeLast('.'), audio = f.path, audioMs = dur)
            n.meta = MeetMeta(status = "pending", source = "import", template = MeetCfg.template, lang = MeetCfg.lang,
                startedAt = System.currentTimeMillis()).toJson()
            Store.notes.add(n)
            Store.touch(n)
            Store.save()
            try { ProcessService.start(c, n.id) } catch (e: Exception) {}
            n
        } catch (e: Exception) {
            null
        }
    }
}
