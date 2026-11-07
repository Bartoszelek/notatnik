package pl.notatnik

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Okienko nagrywania spotkania (z widżetu, skrótu i powiadomienia). */
class MeetingActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var timer: TextView? = null
    private var calTitle = ""
    private var calPeople: List<String> = emptyList()

    // stan formularza przed startem
    private var title = ""
    private var send = true
    private var useDefault = true
    private val people = LinkedHashMap<String, Boolean>()
    private var extra = ""
    private var lang = "auto"
    private var template = "meeting"
    private var offline = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Theme.apply(this, Store.theme, Store.mode)
        MeetCfg.load(this)
        send = MeetCfg.send
        lang = MeetCfg.lang
        template = MeetCfg.template
        offline = MeetCfg.offlineOnly
        MeetingService.recover(this)
        if (MeetingService.running) {
            // okno otwarte w trakcie nagrania – pokaż aktualnych adresatów
            send = MeetingService.sendEmail
            useDefault = MeetCfg.email.isNotBlank() && MeetingService.recipients.any { it.equals(MeetCfg.email, true) }
            MeetingService.recipients.filter { !it.equals(MeetCfg.email, true) }.forEach { people[it] = true }
        }
        val sv = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(Theme.surface); cornerRadius = dp(22).toFloat() }
        }
        sv.addView(root)
        setContentView(sv)
        window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        else loadCalendar()
        render()
        if (intent?.getBooleanExtra("confirmStop", false) == true && MeetingService.running) confirmStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("confirmStop", false) == true && MeetingService.running) confirmStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadCalendar()
        render()
    }

    override fun onResume() {
        super.onResume()
        MeetingService.listener = { render() }
        h.post(tick)
    }

    override fun onPause() {
        MeetingService.listener = null
        h.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            timer?.text = MeetingService.stamp(MeetingService.elapsed())
            h.postDelayed(this, 500)
        }
    }

    /** Trwające (lub zaczynające się za chwilę) spotkanie z kalendarza: tytuł i uczestnicy. */
    private fun loadCalendar() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        try {
            val now = System.currentTimeMillis()
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now - 3 * 3600_000L)
            ContentUris.appendId(b, now + 15 * 60_000L)
            var eventId = -1L
            contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) == 1) continue
                    val begin = c.getLong(2)
                    val end = c.getLong(3)
                    if (begin <= now + 15 * 60_000L && end >= now) {
                        eventId = c.getLong(0); calTitle = c.getString(1) ?: ""
                        break
                    }
                }
            }
            if (eventId >= 0) {
                val list = ArrayList<String>()
                contentResolver.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
                    CalendarContract.Attendees.EVENT_ID + "=?", arrayOf(eventId.toString()), null)?.use { c ->
                    while (c.moveToNext()) {
                        val e = (c.getString(0) ?: "").trim().lowercase()
                        if (e.contains('@') && !e.contains("resource.calendar") && !e.equals(MeetCfg.email, true)) list.add(e)
                    }
                }
                calPeople = list.distinct()
                if (title.isBlank()) title = calTitle
                calPeople.forEach { if (it !in people) people[it] = true }
            }
        } catch (e: Exception) {
        }
    }

    private fun tv(t: String, size: Float = 15f, bold: Boolean = false, color: Int = Theme.fg) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(t: String, primary: Boolean = false, color: Int = Theme.accent, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(if (primary) Theme.onAccent else Theme.fg)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (primary) setColor(color) else { setColor(Theme.bg); setStroke(dp(1), Theme.line) }
        }
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setOnClickListener { f() }
    }

    private fun check(t: String, on: Boolean, f: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; isChecked = on; textSize = 15f; setTextColor(Theme.fg)
        setOnCheckedChangeListener { _, b -> f(b) }
    }

    private fun field(t: String, hint: String, f: (String) -> Unit) = EditText(this).apply {
        setText(t); this.hint = hint; textSize = 15f; setTextColor(Theme.fg); setHintTextColor(Theme.dim)
        background = GradientDrawable().apply { setColor(Theme.bg); cornerRadius = dp(14).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { f(s?.toString() ?: "") }
        })
    }

    private fun chips(label: String, opts: List<Pair<String, String>>, sel: String, f: (String) -> Unit) {
        root.addView(tv(label, 13f, color = Theme.dim), lp(12))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, name) in opts) {
            val on = k == sel
            r.addView(TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (on) Theme.onAccent else Theme.fg)
                if (on) typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); if (on) setColor(Theme.accent) else { setColor(Theme.bg); setStroke(dp(1), Theme.line) } }
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener { f(k); render() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        }
        root.addView(r, lp(4))
    }

    private fun recipientsBlock(editable: Boolean) {
        root.addView(check("Wyślij podsumowanie e-mailem", send) { send = it; if (MeetingService.running) MeetingService.sendEmail = it; render() }, lp(12))
        if (!send) return
        if (MeetCfg.email.isNotBlank()) root.addView(check(MeetCfg.email, useDefault) { useDefault = it; syncRecipients() }, lp(2))
        else root.addView(tv("Ustaw domyślny e-mail: Notatnik → Nagrania → Ustawienia.", 12f, color = Theme.dim), lp(2))
        if (calPeople.isNotEmpty()) root.addView(tv("Uczestnicy z kalendarza", 12f, color = Theme.dim), lp(6))
        for (p in people.keys.toList()) root.addView(check(p, people[p] == true) { people[p] = it; syncRecipients() }, lp(0))
        if (editable || MeetingService.running) root.addView(field(extra, "Inne adresy (oddziel przecinkami)") { extra = it; syncRecipients() }, lp(6))
        if (!MeetCfg.mailReady) root.addView(tv("⚠ Poczta nie jest skonfigurowana – podsumowanie zapisze się tylko w notatce.", 12f, color = 0xFFDC2626.toInt()), lp(6))
    }

    private fun recipients(): MutableList<String> {
        val l = ArrayList<String>()
        if (useDefault && MeetCfg.email.isNotBlank()) l.add(MeetCfg.email)
        people.filter { it.value }.keys.forEach { l.add(it) }
        l.addAll(Mailer.parseList(extra))
        return l.distinct().toMutableList()
    }

    private fun syncRecipients() {
        if (MeetingService.running) MeetingService.recipients = recipients()
    }

    private fun render() {
        root.removeAllViews()
        timer = null
        val rec = MeetingService.running
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(if (rec) (if (MeetingService.paused) "Wstrzymane" else "● Nagrywanie") else "Nagrywanie spotkania", 19f, true,
            if (rec && !MeetingService.paused) 0xFFDC2626.toInt() else Theme.fg), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv("✕", 20f, true, Theme.dim).apply { setPadding(dp(10), dp(4), dp(4), dp(4)); setOnClickListener { finish() } })
        root.addView(head)

        if (!rec) {
            root.addView(field(title, if (calTitle.isNotBlank()) calTitle else "Tytuł (np. Odprawa – zmiana B)") { title = it }, lp(12))
            if (calTitle.isNotBlank()) root.addView(tv("📅 Z kalendarza: $calTitle", 12f, color = Theme.dim), lp(4))
            recipientsBlock(true)
            chips("Język", listOf("auto" to "Auto", "pl" to "Polski", "en" to "English"), lang) { lang = it }
            chips("Rodzaj podsumowania", listOf("meeting" to "Spotkanie", "client" to "Klient", "shift" to "Odprawa"), template) { template = it }
            root.addView(check("Tylko offline (nic nie wychodzi z telefonu)", offline) { offline = it }, lp(10))
            root.addView(button("●  Rozpocznij nagrywanie", true, 0xFFDC2626.toInt()) { startRec() }, lp(16))
            return
        }

        if (MeetingService.title.isNotBlank()) root.addView(tv(MeetingService.title, 15f, true), lp(6))
        timer = tv(MeetingService.stamp(MeetingService.elapsed()), 46f, true, if (MeetingService.paused) Theme.dim else Theme.fg).apply { gravity = Gravity.CENTER }
        root.addView(timer, lp(10))
        if (MeetingService.autoPaused) root.addView(tv("Wstrzymane na czas rozmowy telefonicznej – wznowi się samo.", 13f, color = Theme.dim).apply { gravity = Gravity.CENTER }, lp(2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (MeetingService.paused) "▶  Wznów" else "❚❚  Pauza") { svc(MeetingService.ACT_PAUSE) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(button("★  Ważne (${MeetingService.marks.size})") { svc(MeetingService.ACT_MARK); h.postDelayed({ render() }, 300) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, lp(14))
        root.addView(button("■  Zakończ", true, 0xFFDC2626.toInt()) { confirmStop() }, lp(8))
        recipientsBlock(false)
        root.addView(tv("Możesz zamknąć to okno – nagrywanie trwa w tle. Sterowanie jest też w powiadomieniu.", 12f, color = Theme.dim), lp(12))
    }

    private fun svc(action: String) {
        startService(Intent(this, MeetingService::class.java).setAction(action))
    }

    private fun startRec() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            Toast.makeText(this, "Zezwól na mikrofon i stuknij jeszcze raz", Toast.LENGTH_LONG).show()
            return
        }
        MeetingService.title = title.trim()
        MeetingService.recipients = recipients()
        MeetingService.sendEmail = send && MeetingService.recipients.isNotEmpty()
        MeetingService.offlineOnly = offline
        MeetingService.lang = lang
        MeetingService.template = template
        MeetingService.calendar = calTitle
        MeetingService.onFinished = { n ->
            Toast.makeText(this, "Zapisano w Nagraniach: " + n.title, Toast.LENGTH_LONG).show()
        }
        // zapamiętaj wybory na następny raz
        MeetCfg.send = send; MeetCfg.lang = lang; MeetCfg.template = template; MeetCfg.offlineOnly = offline
        MeetCfg.save(this)
        startForegroundService(Intent(this, MeetingService::class.java).setAction(MeetingService.ACT_START))
        h.postDelayed({ MeetingWidget.updateAll(this); render() }, 400)
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Zakończyć nagrywanie?")
            .setMessage("Nagranie (" + MeetingService.stamp(MeetingService.elapsed()) + ") zostanie zapisane w Nagraniach.")
            .setPositiveButton("Tak, zakończ") { _, _ ->
                svc(MeetingService.ACT_FINISH)
                h.postDelayed({ render() }, 600)
            }
            .setNegativeButton("Nie, nagrywaj dalej", null)
            .show()
    }

    @Suppress("unused")
    private fun spacer() = View(this)
}
