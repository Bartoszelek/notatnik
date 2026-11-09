package pl.notatnik

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val REQ_SCAN = 70
    private val REQ_KEEP = 71
    private val REQ_EXPORT = 72
    private val REQ_IMPORT = 73
    private val REQ_DRIVE = 74
    private val REQ_SCANS_DIR = 75
    private val REQ_MIC = 76
    private val REQ_NOTIF = 77
    private val REQ_PHOTO_CAM = 78
    private val REQ_PHOTO_PICK = 79
    private val REQ_CARD = 80
    private val REQ_INVITE = 81
    private val REQ_PDF = 82
    private val REQ_AUDIO = 91
    private val ACTION_INSTALL = "pl.notatnik.INSTALL_RESULT"

    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<String, Bitmap>(60)

    private var screen = "lib"
    private var filter = "all"
    private var query = ""
    private var cur: Note? = null
    private var pendingIntent: Intent? = null
    private var afterMic: (() -> Unit)? = null
    private var update: Updater.Info? = null
    private var updMsg = ""
    private var updStatus: TextView? = null
    private var lastView = ""
    private var player: MediaPlayer? = null
    private var recView: TextView? = null
    private var focusAdd = false
    private var area = "notes"
    private var recipeScale = 1.0
    private var recipeEdit = false
    private var cookMode = false
    private var cookStep = -1
    private var camFile: File? = null
    private var photoFor: String? = null

    // ------------------------------------------------------------ kolory
    private val inv get() = Store.eink && Store.invert
    private val bg get() = if (Store.eink) (if (inv) Color.BLACK else Color.WHITE) else Theme.bg
    private val surface get() = if (Store.eink) bg else Theme.surface
    private val fg get() = if (Store.eink) (if (inv) Color.WHITE else Color.BLACK) else Theme.fg
    private val dim get() = if (Store.eink) (if (inv) 0xFFAAAAAA.toInt() else 0xFF555555.toInt()) else Theme.dim
    private val lineC get() = if (Store.eink) fg else Theme.line
    private val accent get() = if (Store.eink) fg else Theme.accent
    private val onAccent get() = if (Store.eink) bg else Color.WHITE
    private val dateFmt = SimpleDateFormat("d.MM.yyyy, HH:mm", Locale("pl"))

    private fun colorOf(n: Note): Int = when {
        Store.eink || n.color == 0 -> surface
        Theme.dark -> Store.COLORS_DARK.getOrElse(n.color) { surface }
        else -> Store.COLORS_LIGHT.getOrElse(n.color) { surface }
    }

    // ------------------------------------------------------------ cykl życia
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        root = FrameLayout(this)
        root.fitsSystemWindows = true
        setContentView(root)
        pendingIntent = intent
        area = if (Store.startArea == "last") Store.lastArea else Store.startArea
        if (area !in listOf("notes", "recordings", "recipes", "scans")) area = "notes"
        intent?.getStringExtra("area")?.let { area = it }
        render()
        consumeIntent()
        checkUpdate(false)
        Reminders.scheduleAll(this)
        Store.purgeTrash()
        Shared.schedule(this)
        NotesWidget.updateAll(this)
        MeetCfg.load(this)
        if (MeetingService.recover(this) > 0) toast("Odzyskano przerwane nagranie – jest w Nagraniach")
        ProcessService.cleanup(this)
        if (ProcessService.busyId == null && ProcessService.pending().isNotEmpty() && Models.ready(this, MeetCfg.model)) {
            try { ProcessService.start(this) } catch (e: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent = intent
        consumeIntent()
    }

    override fun onResume() {
        super.onResume()
        ProcessService.listener = {
            if ((screen == "edit" && cur?.type == "meeting") || (screen == "lib" && area == "recordings")) render()
        }
        if (screen == "lib") render()
        h.post(tick)
        h.post(sharedTick)
    }

    /** Wspólne notatki: synchronizacja przy otwarciu i co minutę, gdy aplikacja jest na ekranie. */
    private val sharedTick = object : Runnable {
        override fun run() {
            if (Shared.active()) Shared.syncAsync(this@MainActivity) {
                h.post { if (screen == "lib") render() else if (screen == "edit" && cur?.shared == true && !isEditing()) render() }
            }
            h.postDelayed(this, 60_000)
        }
    }

    private fun isEditing(): Boolean = currentFocus is EditText

    override fun onPause() {
        ProcessService.listener = null
        h.removeCallbacks(tick)
        h.removeCallbacks(sharedTick)
        saveCurrent()
        super.onPause()
    }

    override fun onStop() {
        io.execute { Sync.auto(applicationContext) }
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        stopDictation()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "help" -> go("settings")
            "edit", "settings" -> go("lib")
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            recView?.text = if (RecorderService.running) "Nagrywanie… " + hms(System.currentTimeMillis() - RecorderService.startedAt) else ""
            h.postDelayed(this, 500)
        }
    }

    private fun go(s: String) {
        if (screen == "edit" && s != "edit") {
            saveCurrent()
            player?.release()
            player = null
            if (cookMode) {
                cookMode = false
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        screen = s
        render()
    }

    private fun saveCurrent() {
        val n = cur ?: return
        if (screen != "edit") return
        val empty = n.title.isBlank() && n.body.isBlank() && n.items.isEmpty() && n.audio.isEmpty() && n.pdf.isEmpty() && n.image.isEmpty() && n.photos.isEmpty()
        if (empty && !n.shared) Store.notes.remove(n)
        Store.save()
        NotesWidget.updateAll(this)
        if (n.shared) Shared.syncAsync(this)
    }

    // ------------------------------------------------------------ pomocnicze widoki
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hms(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false, color: Int = fg): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun round(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = lineC) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun btn(text: String, selected: Boolean = false, size: Float = 15f, onClick: () -> Unit): TextView =
        tv(text, size, true, if (selected) onAccent else fg).apply {
            background = if (Store.eink) round(if (selected) fg else bg, 10, 2, fg)
            else round(if (selected) accent else surface, 14, if (selected) 0 else 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun primary(text: String, onClick: () -> Unit): TextView =
        tv(text, 16f, true, onAccent).apply {
            background = round(accent, if (Store.eink) 10 else 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun icon(res: Int, sizeDp: Int = 24, color: Int = fg, onClick: (() -> Unit)? = null) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun roundIcon(res: Int, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.background = if (Store.eink) round(bg, 22, 2, fg) else round(surface, 22, 1)
        f.addView(icon(res, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        f.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) }
        return f
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun lp(w: Int = MATCH, hh: Int = WRAP, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }

    private fun line() = View(this).apply {
        setBackgroundColor(lineC)
        layoutParams = LinearLayout.LayoutParams(MATCH, if (Store.eink) dp(1) else 1)
    }

    private fun page(bottomPad: Int = 40): LinearLayout {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox()
        col.setPadding(dp(16), dp(12), dp(16), dp(bottomPad))
        sv.addView(col)
        root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return col
    }

    private fun panel(col: LinearLayout, top: Int = 8): LinearLayout {
        val p = vbox().apply {
            background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18)
            setPadding(dp(16), dp(6), dp(16), dp(12))
        }
        col.addView(p, lp(top = top))
        return p
    }

    private fun section(col: LinearLayout, title: String, top: Int = 22) {
        col.addView(tv(title.uppercase(), 12f, true, if (Store.eink) dim else accent).apply { letterSpacing = 0.08f }, lp(top = top))
    }

    private fun edit(text: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, plain: Boolean = false) =
        EditText(this).apply {
            setText(text)
            this.hint = hint
            setTextColor(fg)
            setHintTextColor(dim)
            inputType = type
            if (plain) {
                background = null
                setPadding(0, dp(6), 0, dp(6))
            } else {
                background = if (Store.eink) round(bg, 10, 2, fg) else round(surface, 14, 1)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
        }

    private fun watch(e: EditText, f: (String) -> Unit) = e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
    })

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun seg(col: LinearLayout, label: String, opts: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        col.addView(tv(label, 15f), lp(top = 12))
        val r = hbox().apply { gravity = Gravity.TOP }
        opts.forEachIndexed { n, (text, sel) ->
            r.addView(btn(text, sel, 14f) { onPick(n) }, lp(WRAP, WRAP, top = 6).apply { rightMargin = dp(6) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
    }

    private fun item(col: LinearLayout, left: View, right: View? = null, onClick: (() -> Unit)? = null) {
        if (col.childCount > 0) col.addView(line())
        val r = hbox()
        r.setPadding(0, dp(12), 0, dp(12))
        r.addView(left, lp(0, WRAP, 1f))
        if (right != null) r.addView(right)
        if (onClick != null) r.setOnClickListener { onClick() }
        col.addView(r)
    }

    private fun grid(col: LinearLayout, cols: Int, items: List<View>, gap: Int = 6) {
        for (start in items.indices step cols) {
            val row = hbox().apply { gravity = Gravity.TOP }
            for (k in 0 until cols) {
                val v = items.getOrNull(start + k) ?: View(this)
                row.addView(v, lp(0, WRAP, 1f).apply { leftMargin = dp(gap / 2); rightMargin = dp(gap / 2) })
            }
            col.addView(row, lp(top = gap))
        }
    }

    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = vbox().apply {
            setPadding(dp(18), dp(10), dp(18), dp(24))
            background = if (Store.eink) GradientDrawable().apply { setColor(bg); setStroke(dp(3), fg) }
            else GradientDrawable().apply {
                setColor(bg)
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        if (!Store.eink) col.addView(View(this).apply { background = round(lineC, 3) },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        val head = hbox()
        head.addView(tv(title, 20f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
        head.addView(icon(R.drawable.ic_close, 26) { d.dismiss() })
        col.addView(head)
        build(col, d)
        val sv = ScrollView(this)
        sv.addView(col)
        d.setContentView(sv)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(0)
        }
        d.show()
        return d
    }

    private fun applyWindow(pageColor: Int? = null) {
        if (!Store.eink) Theme.apply(this, Store.theme, Store.mode)
        val c = pageColor ?: bg
        root.setBackgroundColor(c)
        window.statusBarColor = c
        window.navigationBarColor = c
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (inv || (!Store.eink && Theme.dark)) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    private fun loadThumb(path: String, iv: ImageView) {
        thumbs.get(path)?.let { iv.setImageBitmap(it); return }
        io.execute {
            val bm = Scans.bitmap(File(path), 600)
            h.post { if (bm != null) { thumbs.put(path, bm); iv.setImageBitmap(bm) } }
        }
    }

    // ------------------------------------------------------------ ekrany
    private fun render() {
        val key = "$screen|${cur?.id}"
        val oldY = if (key == lastView) (root.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        lastView = key
        applyWindow(if (screen == "edit") cur?.let { colorOf(it).takeIf { _ -> it.color != 0 && !Store.eink } } else null)
        root.removeAllViews()
        updStatus = null
        recView = null
        when (screen) {
            "edit" -> renderEdit()
            "settings" -> renderSettings()
            "help" -> renderHelp()
            else -> renderLib()
        }
        if (oldY > 0) (root.getChildAt(0) as? ScrollView)?.let { sv -> sv.post { sv.scrollTo(0, oldY) } }
    }

    private fun matches(n: Note): Boolean {
        if (n.template) return false
        if (filter == "trash") return n.deleted > 0 && Store.area(n) == area
        if (n.deleted > 0 || Store.area(n) != area) return false
        val okFilter = when {
            filter == "archive" -> n.archived
            n.archived -> false
            filter == "text" -> n.type == "text"
            filter == "list" -> n.isList
            filter == "shared" -> n.shared
            filter == "audio" -> n.type == "audio" || n.audio.isNotEmpty()
            filter == "meet" -> n.type == "meeting"
            filter == "dict" -> n.type == "audio"
            filter == "pending" -> n.type == "meeting" && MeetMeta.of(n).status != "done"
            filter == "docs" -> n.type == "scan"
            filter == "cards" -> n.type == "card"
            filter == "photo" -> n.photos.isNotEmpty()
            filter == "remind" -> n.reminder > System.currentTimeMillis()
            filter.startsWith("l:") -> filter.removePrefix("l:") in n.labels
            else -> true
        }
        if (!okFilter) return false
        if (query.isBlank()) return true
        return Store.norm(n.searchText()).contains(Store.norm(query.trim()))
    }

    private fun switchArea(a: String) {
        area = a
        if (Store.lastArea != a) { Store.lastArea = a; Store.save() }
        filter = "all"
        query = ""
        render()
    }

    private fun areaTabs(onHero: Boolean): View {
        val r = hbox()
        listOf(
            Triple("notes", R.drawable.ic_note, "Notatki"),
            Triple("recordings", R.drawable.ic_mic, "Nagrania"),
            Triple("recipes", R.drawable.ic_recipe, "Przepisy"),
            Triple("scans", R.drawable.ic_scan, "Skany")
        ).forEach { (k, res, label) ->
            val sel = k == area
            val f = FrameLayout(this)
            val tint = when {
                onHero -> if (sel) Theme.accent else Color.WHITE
                Store.eink -> if (sel) bg else fg
                else -> if (sel) Theme.onAccent else fg
            }
            f.background = when {
                onHero -> round(if (sel) Color.WHITE else Theme.withAlpha(Color.WHITE, 0.2f), 20)
                Store.eink -> if (sel) round(fg, 20) else round(bg, 20, 2, fg)
                else -> if (sel) round(Theme.accent, 20) else round(surface, 20, 1)
            }
            f.addView(icon(res, 22, tint), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            f.contentDescription = label
            f.setOnClickListener { switchArea(k) }
            f.setOnLongClickListener { toast(label); true }
            r.addView(f, LinearLayout.LayoutParams(dp(52), dp(40)).apply { rightMargin = dp(8) })
        }
        return r
    }

    private fun renderLib() {
        val col = page()
        val recipes = area == "recipes"
        val scans = area == "scans"
        val recs = area == "recordings"
        val areaTitle = when (area) { "recipes" -> "Przepisy"; "scans" -> "Skany"; "recordings" -> "Nagrania"; else -> "Notatnik" }
        val total = Store.notes.count { it.deleted == 0L && !it.archived && !it.template && Store.area(it) == area }
        if (Store.eink) {
            val head = hbox()
            head.addView(tv(areaTitle, 32f, true), lp(0, WRAP, 1f))
            head.addView(roundIcon(R.drawable.ic_settings) { go("settings") })
            col.addView(head)
            col.addView(areaTabs(false), lp(top = 8))
        } else {
            val hero = vbox().apply {
                background = Theme.gradient(dp(24).toFloat())
                elevation = dp(4).toFloat()
                setPadding(dp(20), dp(16), dp(14), dp(18))
                setOnLongClickListener { Store.mode = if (Theme.dark) "light" else "dark"; Store.save(); render(); true }
            }
            val top = hbox()
            top.addView(areaTabs(true), lp(0, WRAP, 1f))
            val gear = FrameLayout(this).apply { background = round(Theme.withAlpha(Color.WHITE, 0.22f), 20); setOnClickListener { go("settings") } }
            gear.addView(icon(R.drawable.ic_settings, 20, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            top.addView(gear, LinearLayout.LayoutParams(dp(40), dp(40)))
            hero.addView(top)
            hero.addView(tv(areaTitle, 34f, true, Color.WHITE), lp(top = 8))
            val rem = Store.notes.count { it.deleted == 0L && it.reminder > System.currentTimeMillis() }
            hero.addView(tv(when {
                recipes -> "$total przepisów"
                scans -> "$total dokumentów i wizytówek"
                recs -> "$total nagrań" + Store.notes.count { it.type == "meeting" && it.deleted == 0L && MeetMeta.of(it).status != "done" }
                    .let { if (it > 0) " · $it do przetworzenia" else "" }
                else -> "$total wpisów" + (if (rem > 0) " · $rem przypomnień" else "")
            }, 13f, false, Theme.withAlpha(Color.WHITE, 0.85f)))
            col.addView(hero, lp(top = 4))
        }

        val upd = update
        if (upd != null) {
            val box = hbox().apply { background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18); setPadding(dp(16), dp(12), dp(12), dp(12)) }
            val busy = updMsg.startsWith("Pobieram") || updMsg.startsWith("Błąd") || updMsg.startsWith("Potwierdź")
            val st = tv(if (busy) updMsg else "Nowa wersja 1.${upd.number}", 15f, true)
            updStatus = st
            box.addView(st, lp(0, WRAP, 1f))
            box.addView(btn("Zainstaluj", true, 14f) { installUpdate() })
            col.addView(box, lp(top = 12))
        }
        if (MeetingService.running) {
            val box = hbox().apply {
                background = round(if (Store.eink) bg else 0xFFDC2626.toInt(), 16, if (Store.eink) 2 else 0, fg); setPadding(dp(16), dp(12), dp(12), dp(12))
                setOnClickListener { openMeeting() }
            }
            box.addView(tv((if (MeetingService.paused) "❚❚ " else "● ") + "Spotkanie: " + MeetingService.title.ifBlank { "nagrywanie" }, 16f, true,
                if (Store.eink) fg else Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
            box.addView(btn("Otwórz", true, 14f) { openMeeting() })
            col.addView(box, lp(top = 12))
        }
        if (RecorderService.running) {
            val box = hbox().apply { background = round(if (Store.eink) bg else 0xFFDC2626.toInt(), 16, if (Store.eink) 2 else 0, fg); setPadding(dp(16), dp(12), dp(12), dp(12)) }
            val t = tv("Nagrywanie…", 16f, true, if (Store.eink) fg else Color.WHITE)
            recView = t
            box.addView(t, lp(0, WRAP, 1f))
            box.addView(btn("Zatrzymaj", true, 14f) { stopRecording() })
            col.addView(box, lp(top = 12))
        }

        val search = edit(query, when {
            recipes -> "Szukaj przepisu lub składnika"
            scans -> "Szukaj w treści dokumentów i wizytówek"
            recs -> "Szukaj w nagraniach i podsumowaniach"
            else -> "Szukaj w notatkach, listach i zdjęciach"
        }, InputType.TYPE_CLASS_TEXT).apply { setSingleLine(true) }
        val list = vbox()
        watch(search) { query = it; fillList(list) }
        col.addView(search, lp(top = 14))

        val quick = hbox().apply { gravity = Gravity.TOP }
        val qa: List<Triple<Int, String, () -> Unit>> = when {
            recipes -> listOf(
                Triple(R.drawable.ic_plus, "Nowy przepis") { newRecipe() },
                Triple(R.drawable.ic_open, "Z linku") { recipeFromLink("") },
                Triple(R.drawable.ic_camera, "Ze zdjęcia") { val r = newRecipe(); addPhoto(r) }
            )
            recs -> listOf(
                Triple(R.drawable.ic_mic, "Spotkanie") { openMeeting() },
                Triple(R.drawable.ic_stop, "Dyktafon") { newNote("record") },
                Triple(R.drawable.ic_open, "Importuj") {
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_AUDIO)
                    } catch (e: Exception) {
                        toast("Nie mogę otworzyć plików")
                    }
                },
                Triple(R.drawable.ic_plus, "Skrót") { pinMeetingShortcut() },
                Triple(R.drawable.ic_settings, "Ustawienia") { meetingSettings() }
            )
            scans -> listOf(
                Triple(R.drawable.ic_scan, "Dokument") { startScan() },
                Triple(R.drawable.ic_label, "Wizytówka") { startCardScan() },
                Triple(R.drawable.ic_note, "Plik PDF") {
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PDF)
                    } catch (e: Exception) {
                        toast("Nie mogę otworzyć plików")
                    }
                },
                Triple(R.drawable.ic_open, "Z folderu") {
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SCANS_DIR) } catch (e: Exception) { toast("Nie mogę otworzyć wyboru folderu") }
                }
            )
            else -> listOf(
                Triple(R.drawable.ic_note, "Notatka") { newNote("text") },
                Triple(R.drawable.ic_check_list, "Lista") { newNote("list") },
                Triple(R.drawable.ic_mic, "Dyktuj") { newNote("dictate") },
                Triple(R.drawable.ic_stop, "Nagraj") { newNote("record") },
                Triple(R.drawable.ic_label, "Szablon") { templatesSheet() }
            )
        }
        for ((res, label, f) in qa) {
            val v = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 16)
                setPadding(dp(2), dp(12), dp(2), dp(10))
                setOnClickListener { f() }
            }
            v.addView(icon(res, 24, accent))
            v.addView(tv(label, 11f, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(top = 4))
            quick.addView(v, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        col.addView(quick, lp(top = 12))

        val chips = hbox()
        val fl = mutableListOf("all" to "Wszystko")
        if (recipes) {
            Store.labels("recipes").forEach { fl.add("l:$it" to it) }
        } else if (recs) {
            fl.addAll(listOf("meet" to "Spotkania", "dict" to "Dyktafon", "pending" to "Do przetworzenia"))
            Store.labels("recordings").forEach { fl.add("l:$it" to "#$it") }
            if (Store.notes.any { it.archived && it.deleted == 0L && Store.area(it) == "recordings" }) fl.add("archive" to "Archiwum")
        } else if (scans) {
            fl.addAll(listOf("docs" to "Dokumenty", "cards" to "Wizytówki", "remind" to "Przypomnienia"))
            Store.labels("scans").forEach { fl.add("l:$it" to "#$it") }
            if (Store.notes.any { it.archived && it.deleted == 0L && it.isScanArea }) fl.add("archive" to "Archiwum")
        } else {
            fl.add("today" to "Dziś")
            fl.addAll(listOf("text" to "Notatki", "list" to "Listy"))
            if (Store.notes.any { it.shared && it.deleted == 0L }) fl.add("shared" to "Wspólne")
            fl.addAll(listOf("photo" to "Zdjęcia", "remind" to "Przypomnienia"))
            Store.labels("notes").forEach { fl.add("l:$it" to "#$it") }
            if (Store.notes.any { it.archived && it.deleted == 0L && Store.area(it) == "notes" }) fl.add("archive" to "Archiwum")
        }
        if (Store.notes.any { it.deleted > 0 && Store.area(it) == area }) fl.add("trash" to "Kosz")
        for ((k, label) in fl) chips.addView(btn(label, filter == k, 13f) { filter = k; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(top = 14))
        col.addView(list, lp(top = 4))
        fillList(list)
    }

    private fun fillList(list: LinearLayout) {
        list.removeAllViews()
        if (filter == "today") {
            agenda(list)
            return
        }
        val all = Store.notes.filter { matches(it) }.sortedByDescending { if (filter == "trash") it.deleted else it.updated }
        if (filter == "trash") {
            list.addView(tv("Usunięte wpisy znikają na zawsze po 30 dniach. Stuknij, żeby przywrócić.", 13f, color = dim), lp(top = 12))
            if (all.isNotEmpty()) list.addView(btn("Opróżnij kosz", size = 13f) { emptyTrash() }, lp(WRAP, WRAP, top = 8))
        }
        if (all.isEmpty()) {
            list.addView(tv(when {
                area == "recipes" && query.isBlank() && filter == "all" -> "Nie masz jeszcze przepisów. Dodaj pierwszy albo zaimportuj z linku do strony z przepisem."
                area == "scans" && query.isBlank() && filter == "all" -> "Nie masz jeszcze skanów. Zeskanuj dokument lub wizytówkę albo wczytaj stare skany z folderu."
                area == "recordings" && query.isBlank() && filter == "all" -> "Nie masz jeszcze nagrań. Stuknij „Spotkanie”, żeby nagrać spotkanie, albo „Skrót”, żeby dodać przycisk nagrywania na pulpit."
                Store.notes.isEmpty() -> "Pusto. Dodaj pierwszą notatkę przyciskami powyżej."
                else -> "Nic nie znaleziono."
            }, 15f, color = dim), lp(top = 18))
            return
        }
        val pinned = if (filter == "trash") emptyList() else all.filter { it.pinned && !it.archived }
        val rest = all.filter { it !in pinned }
        if (pinned.isNotEmpty()) {
            section(list, "Przypięte", 14)
            masonry(list, pinned)
            if (rest.isNotEmpty()) section(list, "Pozostałe", 18)
        }
        masonry(list, rest)
    }

    /** Widok „Dziś”: przypomnienia na najbliższe 7 dni (z powtarzanymi). */
    private fun agenda(list: LinearLayout) {
        val now = System.currentTimeMillis()
        val end = now + 7 * 86_400_000L
        val ev = ArrayList<Pair<Long, Note>>()
        for (n in Store.notes) {
            if (n.reminder <= 0 || n.deleted > 0 || n.archived) continue
            var t = n.reminder
            var k = 0
            while (t in now..end && k < 40) {
                ev.add(Pair(t, n)); k++
                if (n.repeat.isEmpty()) break
                t = Reminders.next(n, t)
            }
        }
        if (ev.isEmpty()) {
            list.addView(tv("Brak przypomnień w najbliższym tygodniu. Dzwonek w notatce ustawia przypomnienie.", 15f, color = dim), lp(top = 18))
            return
        }
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        val today = dayKey.format(Date())
        val tomorrow = dayKey.format(Date(now + 86_400_000L))
        val dayName = SimpleDateFormat("EEEE, d MMMM", Locale("pl"))
        val time = SimpleDateFormat("HH:mm", Locale("pl"))
        var lastDay = ""
        var p: LinearLayout? = null
        for ((t, n) in ev.sortedBy { it.first }) {
            val dk = dayKey.format(Date(t))
            if (dk != lastDay) {
                lastDay = dk
                section(list, when (dk) { today -> "Dziś"; tomorrow -> "Jutro"; else -> dayName.format(Date(t)) }, 16)
                p = panel(list)
            }
            val left = vbox()
            left.addView(tv(n.title.ifEmpty { if (n.isList) "Lista" else "Notatka" }, 16f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            left.addView(tv(Reminders.describe(n), 12f, color = dim))
            p?.let { pp -> item(pp, left, tv(time.format(Date(t)), 18f, true, accent)) { openNote(n) } }
        }
    }

    private fun trashSheet(n: Note) {
        sheet(n.title.ifEmpty { "Usunięta notatka" }) { col, d ->
            col.addView(tv("Usunięto " + dateFmt.format(Date(n.deleted)) + ". Zniknie na zawsze po 30 dniach.", 13f, color = dim), lp(top = 4))
            col.addView(primary("Przywróć") {
                n.deleted = 0; Store.touch(n); Store.save(); Reminders.schedule(this, n); NotesWidget.updateAll(this); d.dismiss(); render()
            }, lp(top = 12))
            col.addView(btn("Usuń na zawsze") { deleteForever(n); d.dismiss(); render() }, lp(top = 8))
        }
    }

    private fun deleteForever(n: Note) {
        (listOf(n.audio, n.pdf, n.thumb, n.image) + n.photos).filter { it.isNotEmpty() }.forEach { File(it).delete() }
        Store.notes.remove(n)
        Store.save()
    }

    private fun emptyTrash() {
        sheet("Opróżnić kosz?") { col, d ->
            col.addView(tv("Wpisy z kosza zostaną usunięte na zawsze.", 14f, color = dim), lp(top = 6))
            col.addView(primary("Tak, opróżnij") {
                Store.notes.filter { it.deleted > 0 && Store.area(it) == area }.forEach { deleteForever(it) }
                d.dismiss(); filter = "all"; render()
            }, lp(top = 12))
            col.addView(btn("Anuluj") { d.dismiss() }, lp(top = 8))
        }
    }

    /** Przeniesienie do kosza (wspólna notatka znika też u drugiej osoby). */
    private fun moveToTrash(n: Note) {
        n.deleted = System.currentTimeMillis()
        n.pinned = false
        Reminders.schedule(this, n)
        Store.save()
        NotesWidget.updateAll(this)
        if (n.shared) Shared.syncAsync(this)
        toast("Przeniesiono do kosza")
    }

    private fun estimate(n: Note): Int {
        var hgt = 60
        if (n.thumb.isNotEmpty() || n.photos.isNotEmpty()) hgt += if (n.isRecipe) 150 else 130
        hgt += when {
            n.locked -> 20
            n.isList -> minOf(n.live.count { !it.done }, 6) * 24
            n.type == "scan" -> 60
            n.isRecipe -> 40
            else -> minOf(n.body.length / 28 + n.body.count { it == '\n' }, 8) * 20
        }
        return hgt
    }

    private fun masonry(col: LinearLayout, notes: List<Note>) {
        if (Store.layout == "list") {
            notes.forEach { col.addView(noteCard(it), lp(top = 10)) }
            return
        }
        val row = hbox().apply { gravity = Gravity.TOP }
        val left = vbox()
        val right = vbox()
        var lh = 0
        var rh = 0
        for (n in notes) {
            val e = estimate(n)
            if (lh <= rh) {
                left.addView(noteCard(n), lp(top = 10)); lh += e
            } else {
                right.addView(noteCard(n), lp(top = 10)); rh += e
            }
        }
        row.addView(left, lp(0, WRAP, 1f).apply { rightMargin = dp(5) })
        row.addView(right, lp(0, WRAP, 1f).apply { leftMargin = dp(5) })
        col.addView(row)
    }

    private fun noteCard(n: Note): View {
        val c = vbox().apply {
            background = if (Store.eink) round(bg, 12, 2, fg) else round(colorOf(n), 18, if (n.color == 0) 1 else 0)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            clipToOutline = true
            setOnClickListener { if (n.deleted > 0) trashSheet(n) else openNote(n) }
            setOnLongClickListener { if (n.deleted > 0) trashSheet(n) else noteMenu(n); true }
        }
        val img = if (n.locked) "" else n.thumb.ifEmpty { n.photos.firstOrNull() ?: "" }
        if (img.isNotEmpty()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = round(Color.WHITE, 12)
                clipToOutline = true
            }
            loadThumb(img, iv)
            c.addView(iv, lp(MATCH, dp(if (n.isRecipe) 140 else 120)).apply { bottomMargin = dp(8) })
        }
        if (n.title.isNotBlank()) c.addView(tv(n.title, if (n.isRecipe) 17f else 16f, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        when {
            n.locked -> c.addView(tv("🔒 Zablokowana", 13f, true, dim), lp(top = 4))
            n.isRecipe -> {
                val info = listOfNotNull(n.cookTime.ifEmpty { null }?.let { "⏱ $it" }, if (n.servings > 0) "🍽 ${n.servings} porcji" else null,
                    if (n.live.isNotEmpty()) "${n.live.size} składników" else null).joinToString(" · ")
                if (info.isNotEmpty()) c.addView(tv(info, 12f, true, dim), lp(top = 4))
            }
            n.isList -> {
                val open = n.live.filter { !it.done }
                open.take(6).forEach { it2 ->
                    val r = hbox()
                    r.addView(icon(R.drawable.ic_box, 16, dim))
                    r.addView(tv(it2.text, 14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
                    c.addView(r, lp(top = 4))
                }
                val more = open.size - 6
                val done = n.live.size - open.size
                if (more > 0 || done > 0) c.addView(tv(listOfNotNull(
                    if (more > 0) "+$more" else null, if (done > 0) "$done zaznaczonych" else null
                ).joinToString(" · "), 12f, color = dim), lp(top = 4))
            }
            n.type == "scan" -> {
                c.addView(tv("PDF · ${n.pages} str.", 12f, true, accent), lp(top = 2))
                if (n.ocr.isNotBlank()) c.addView(tv(n.ocr.trim().replace(Regex("\\s+"), " "), 13f, color = dim).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }, lp(top = 4))
            }
            else -> if (n.body.isNotBlank()) c.addView(TextView(this).apply {
                text = Fmt.render(n.body.trim())
                textSize = 14f
                setTextColor(fg)
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
            }, lp(top = if (n.title.isBlank()) 0 else 4))
        }
        if (n.audio.isNotEmpty() && !n.locked) {
            val r = hbox()
            r.addView(icon(R.drawable.ic_mic, 16, accent))
            r.addView(tv("Nagranie " + hms(n.audioMs), 13f, true, accent), lp(WRAP, WRAP).apply { leftMargin = dp(6) })
            c.addView(r, lp(top = 6))
        }
        val chips = mutableListOf<String>()
        if (n.shared) chips.add("👥 wspólna")
        if (n.photos.size > 1 && !n.locked) chips.add("📷 ${n.photos.size}")
        if (n.reminder > System.currentTimeMillis()) chips.add("⏰ " + Reminders.describe(n))
        n.labels.forEach { chips.add("#$it") }
        if (chips.isNotEmpty()) c.addView(tv(chips.joinToString("  "), 11f, true, dim).apply { maxLines = 2 }, lp(top = 8))
        return c
    }

    // ------------------------------------------------------------ tworzenie
    private fun newNote(kind: String) {
        when (kind) {
            "scan" -> { area = "scans"; startScan() }
            "record" -> withMic { area = "recordings"; startRecording(null) }
            else -> {
                area = "notes"
                val n = Note(type = if (kind == "list") "list" else "text")
                Store.notes.add(n)
                Store.touch(n)
                focusAdd = kind == "list"
                openNote(n)
                if (kind == "dictate") h.postDelayed({ dictateInto(n) }, 300)
            }
        }
    }

    private fun newRecipe(): Note {
        area = "recipes"
        val n = Note(type = "recipe", servings = 4)
        Store.notes.add(n)
        Store.touch(n)
        recipeEdit = true
        openNoteNow(n)
        return n
    }

    private fun openNote(n: Note) {
        if (n.locked && System.currentTimeMillis() > Store.unlockedUntil) {
            authenticate("Odblokuj notatkę") {
                Store.unlockedUntil = System.currentTimeMillis() + 60_000L
                openNoteNow(n)
            }
        } else openNoteNow(n)
    }

    private fun openNoteNow(n: Note) {
        if (screen == "edit") saveCurrent()
        if (n.deleted == 0L && !n.template) area = Store.area(n)
        cur = n
        player?.release()
        player = null
        recipeScale = 1.0
        cookStep = -1
        if (n.isRecipe && n.title.isNotEmpty()) recipeEdit = false
        screen = "edit"
        render()
    }

    // ------------------------------------------------------------ szablony i kopie
    private fun copyOf(n: Note, template: Boolean, uncheck: Boolean): Note {
        val c = Note.fromJson(n.toJson())
        c.id = java.util.UUID.randomUUID().toString().take(12)
        c.template = template
        c.shared = false
        c.sharedSha = ""
        c.syncedAt = 0
        c.reminder = 0
        c.repeat = ""
        c.deleted = 0
        c.pinned = false
        c.created = System.currentTimeMillis()
        c.updated = c.created
        c.items.removeAll { it.del }
        c.items.forEach { it.id = java.util.UUID.randomUUID().toString().take(10); it.touch(); if (uncheck) it.done = false }
        return c
    }

    private fun templatesSheet() {
        val ts = Store.notes.filter { it.template && it.deleted == 0L }
        sheet("Szablony") { col, d ->
            if (ts.isEmpty()) col.addView(tv("Nie masz jeszcze szablonów. Otwórz notatkę lub listę (np. „Lista na wyjazd”) i w menu wybierz „Zapisz jako szablon”.", 14f, color = dim), lp(top = 8))
            for (t in ts) {
                val left = vbox()
                left.addView(tv(t.title.ifEmpty { "Szablon" }, 16f, true))
                left.addView(tv(if (t.isList) "${t.live.size} pozycji" else t.body.take(50), 12f, color = dim).apply { maxLines = 1 })
                item(col, left, icon(R.drawable.ic_trash, 22, dim) {
                    Store.notes.remove(t); Store.save(); d.dismiss(); templatesSheet()
                }) {
                    d.dismiss()
                    val c = copyOf(t, false, true)
                    Store.notes.add(c)
                    Store.save()
                    openNote(c)
                }
            }
        }
    }

    // ------------------------------------------------------------ edytor
    private fun renderEdit() {
        val n = cur
        if (n == null) {
            screen = "lib"; renderLib(); return
        }
        if (n.isRecipe) {
            renderRecipe(n)
            return
        }
        val col = page(60)
        val top = hbox()
        top.addView(roundIcon(R.drawable.ic_back_arrow) { go("lib") }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = 0 })
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(roundIcon(if (n.pinned) R.drawable.ic_pin_on else R.drawable.ic_pin) { n.pinned = !n.pinned; Store.touch(n); render() })
        top.addView(roundIcon(R.drawable.ic_bell) { reminderSheet(n) })
        top.addView(roundIcon(R.drawable.ic_palette) { colorSheet(n) })
        top.addView(roundIcon(R.drawable.ic_more) { noteMenu(n) })
        col.addView(top)
        if (n.template) col.addView(tv("To jest szablon – zmiany dotyczą wszystkich przyszłych kopii.", 13f, true, accent), lp(top = 10))
        if (n.shared) col.addView(tv("👥 Wspólna – zmiany widzi druga osoba", 13f, true, accent), lp(top = 10))

        val title = edit(n.title, "Tytuł", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, plain = true).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        watch(title) { n.title = it; Store.touch(n) }
        col.addView(title, lp(top = 12))
        if (n.photos.isNotEmpty() || n.type == "text") photoStrip(col, n)

        when (n.type) {
            "list" -> listEditor(col, n)
            "scan" -> scanView(col, n)
            else -> {
                if (n.audio.isNotEmpty()) audioPlayer(col, n)
                if (n.type == "meeting") meetingInfo(col, n)
                val body = edit(n.body, if (n.audio.isNotEmpty()) "Notatka do nagrania" else "Notatka",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, plain = true).apply {
                    textSize = 17f
                    gravity = Gravity.TOP
                    minLines = 6
                }
                Fmt.style(body.text, dim)
                watch(body) { n.body = it; Store.touch(n) }
                body.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (s != null) Fmt.style(s, dim)
                    }
                })
                col.addView(formatBar(body), lp(top = 6))
                col.addView(body, lp(top = 4))
                if (n.type == "meeting") meetingTail(col, n)
                if (n.title.isEmpty() && n.body.isEmpty() && n.audio.isEmpty()) body.post { body.requestFocus() }
            }
        }

        val chips = hbox().apply { gravity = Gravity.TOP }
        if (n.reminder > 0) chips.addView(btn("⏰ " + Reminders.describe(n), size = 12f) { reminderSheet(n) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        n.labels.forEach { l -> chips.addView(btn("#$l", size = 12f) { labelSheet(n) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) }) }
        if (chips.childCount > 0) col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(top = 14))

        val bar = hbox()
        fun b(t: String, f: () -> Unit) = bar.addView(btn(t, size = 13f) { f() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        b("🎤 Dyktuj") { dictateInto(n) }
        b("📷 Zdjęcie") { addPhoto(n) }
        if (n.isList) b("Posortuj działami") { Shop.sort(n); Store.touch(n); render(); toast("Posortowano według działów sklepu") }
        if (n.type == "text" || n.type == "audio") b(if (n.audio.isEmpty()) "Nagraj" else "Nagraj od nowa") { withMic { startRecording(n.id) } }
        if (n.type == "text") b("Zmień w listę") {
            n.items.clear()
            n.body.lines().map { it.trim().removePrefix("- ").removePrefix("• ") }.filter { it.isNotEmpty() }.forEach { n.items.add(Item(it)) }
            n.body = ""
            n.type = "list"
            Store.touch(n)
            render()
        }
        if (n.isList && !n.shared) b("Zmień w tekst") {
            n.body = n.live.joinToString("\n") { it.text }
            n.items.clear()
            n.type = "text"
            Store.touch(n)
            render()
        }
        b("Udostępnij") { shareNote(n) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }, lp(top = 18))
        col.addView(tv("Edytowano " + dateFmt.format(Date(n.updated)), 12f, color = dim), lp(top = 12))
    }

    /** Pasek formatowania: pogrubienie, nagłówek, punkty, numeracja. */
    private fun formatBar(e: EditText): View {
        val r = hbox()
        fun wrap(pre: String, post: String) {
            val s = e.selectionStart.coerceAtLeast(0)
            val en = e.selectionEnd.coerceAtLeast(s)
            e.text.insert(en, post)
            e.text.insert(s, pre)
            e.setSelection(s + pre.length, en + pre.length)
        }
        fun prefix(p: String) {
            val t = e.text
            val s = e.selectionStart.coerceAtLeast(0)
            val ls = t.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (it < 0 || s == 0) 0 else it + 1 }
            if (t.startsWith(p, ls)) t.delete(ls, ls + p.length) else t.insert(ls, p)
        }
        listOf("B" to { wrap("**", "**") }, "Nagłówek" to { prefix("# ") }, "• Punkt" to { prefix("• ") }, "1. Numer" to { prefix("1. ") })
            .forEach { (label, f) -> r.addView(btn(label, size = 12f) { f(); e.requestFocus() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) }) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun listEditor(col: LinearLayout, n: Note) {
        val box = vbox()
        col.addView(box, lp(top = 4))
        fun draw() {
            box.removeAllViews()
            fun row(it2: Item, done: Boolean) {
                val r = hbox()
                r.setPadding(0, dp(2), 0, dp(2))
                val chk = icon(if (done) R.drawable.ic_box_on else R.drawable.ic_box, 26, if (done) dim else accent)
                chk.setOnClickListener { it2.done = !it2.done; it2.touch(); Store.touch(n); draw() }
                chk.setOnLongClickListener { moveSheet(n, it2) { draw() }; true }
                r.addView(chk)
                val e = edit(it2.text, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, plain = true).apply {
                    textSize = 16f
                    if (done) setTextColor(dim)
                    paintFlags = if (done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                watch(e) { t -> it2.text = t; it2.touch(); Store.touch(n) }
                r.addView(e, lp(0, WRAP, 1f).apply { leftMargin = dp(10) })
                r.addView(icon(R.drawable.ic_close, 18, dim) { Store.removeItem(n, it2); draw() })
                box.addView(r)
            }
            n.items.filter { !it.done && !it.del }.forEach { row(it, false) }
            val add = edit("", if (n.isRecipe) "+ Dodaj składnik" else "+ Dodaj pozycję", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, plain = true).apply {
                textSize = 16f
                imeOptions = EditorInfo.IME_ACTION_DONE
                setPadding(dp(36), dp(8), 0, dp(8))
            }
            val sugg = hbox()
            fun addText(t: String) {
                if (t.isBlank()) return
                n.items.add(Item(t.trim()))
                Store.touch(n)
                focusAdd = true
                draw()
            }
            watch(add) { t ->
                sugg.removeAllViews()
                Shop.suggest(t, n).forEach { s -> sugg.addView(btn(s, size = 12f) { addText(s) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) }) }
            }
            add.setOnEditorActionListener { _, a, _ ->
                if (a == EditorInfo.IME_ACTION_DONE || a == EditorInfo.IME_ACTION_NEXT) {
                    addText(add.text.toString())
                    true
                } else false
            }
            box.addView(add)
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sugg) })
            if (focusAdd) {
                focusAdd = false
                add.post { add.requestFocus() }
            }
            val done = n.items.filter { it.done && !it.del }
            if (done.isNotEmpty()) {
                box.addView(tv("Zaznaczone (${done.size})", 13f, true, dim), lp(top = 10))
                done.forEach { row(it, true) }
                box.addView(btn("Usuń zaznaczone", size = 12f) { done.forEach { Store.removeItem(n, it) }; draw() }, lp(WRAP, WRAP, top = 6))
            }
        }
        draw()
    }

    private fun moveSheet(n: Note, it2: Item, redraw: () -> Unit) {
        sheet(it2.text.take(40)) { col, d ->
            col.addView(btn("W górę") {
                val i = n.items.indexOf(it2)
                if (i > 0) { n.items.removeAt(i); n.items.add(i - 1, it2) }
                it2.touch(); Store.touch(n); d.dismiss(); redraw()
            }, lp(top = 10))
            col.addView(btn("W dół") {
                val i = n.items.indexOf(it2)
                if (i >= 0 && i < n.items.size - 1) { n.items.removeAt(i); n.items.add(i + 1, it2) }
                it2.touch(); Store.touch(n); d.dismiss(); redraw()
            }, lp(top = 8))
            col.addView(btn("Na samą górę") {
                n.items.remove(it2); n.items.add(0, it2); it2.touch(); Store.touch(n); d.dismiss(); redraw()
            }, lp(top = 8))
            col.addView(btn("Usuń") { Store.removeItem(n, it2); d.dismiss(); redraw() }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ zdjęcia
    private fun photoStrip(col: LinearLayout, n: Note) {
        val r = hbox().apply { gravity = Gravity.TOP }
        for (p in n.photos.toList()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = round(surface, 12)
                clipToOutline = true
                setOnClickListener { photoViewer(n, p) }
            }
            loadThumb(p, iv)
            r.addView(iv, LinearLayout.LayoutParams(dp(if (n.photos.size == 1) 220 else 120), dp(if (n.photos.size == 1) 160 else 120)).apply { rightMargin = dp(8) })
        }
        if (n.photos.isNotEmpty()) {
            val add = FrameLayout(this).apply {
                background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 12, 1)
                setOnClickListener { addPhoto(n) }
            }
            add.addView(icon(R.drawable.ic_plus, 28, accent), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            r.addView(add, LinearLayout.LayoutParams(dp(70), dp(if (n.photos.size == 1) 160 else 120)))
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 10))
        }
    }

    private fun photoViewer(n: Note, path: String) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = vbox().apply { setBackgroundColor(Color.BLACK); setPadding(dp(10), dp(24), dp(10), dp(24)) }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        io.execute {
            val bm = Scans.bitmap(File(path), 2000)
            h.post { if (bm != null) iv.setImageBitmap(bm) }
        }
        col.addView(iv, lp(MATCH, 0, 1f))
        val r = hbox().apply { gravity = Gravity.CENTER }
        fun b(t: String, f: () -> Unit) = r.addView(tv(t, 15f, true, Color.WHITE).apply {
            background = round(0xFF2A2A2A.toInt(), 14); setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { f() }
        }, lp(WRAP, WRAP).apply { leftMargin = dp(5); rightMargin = dp(5) })
        b("Udostępnij") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, fileUri(path))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Udostępnij zdjęcie"))
        }
        b("Usuń") {
            n.photos.remove(path)
            File(path).delete()
            Store.touch(n)
            Store.save()
            d.dismiss()
            render()
        }
        b("Zamknij") { d.dismiss() }
        col.addView(r, lp(top = 12))
        d.setContentView(col)
        d.show()
    }

    private fun addPhoto(n: Note) {
        photoFor = n.id
        sheet("Dodaj zdjęcie") { col, d ->
            col.addView(primary("Zrób zdjęcie") {
                d.dismiss()
                try {
                    val f = File(Store.dir("photos"), "p_${System.currentTimeMillis()}.jpg")
                    camFile = f
                    val uri = FileProvider.getUriForFile(this, "pl.notatnik.files", f)
                    startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_PHOTO_CAM)
                } catch (e: Exception) {
                    toast("Nie mogę uruchomić aparatu")
                }
            }, lp(top = 12))
            col.addView(btn("Wybierz z galerii (można kilka)") {
                d.dismiss()
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PHOTO_PICK)
                } catch (e: Exception) {
                    toast("Nie mogę otworzyć galerii")
                }
            }, lp(top = 8))
            col.addView(tv("Tekst ze zdjęć zostanie odczytany – znajdziesz notatkę, wpisując go w wyszukiwarkę.", 12f, color = dim), lp(top = 10))
        }
    }

    /** Zapisuje zdjęcia przy notatce: zmniejsza, odczytuje tekst (do wyszukiwania). */
    private fun attachPhotos(n: Note, uris: List<Uri>, camera: File?) {
        val (d, label) = progress("Dodaję zdjęcia")
        io.execute {
            val added = ArrayList<String>()
            val texts = ArrayList<String>()
            val sources: List<Any> = if (camera != null) listOf(camera) else uris
            sources.forEachIndexed { k, src ->
                h.post { label.text = "Zdjęcie ${k + 1} z ${sources.size}…" }
                try {
                    val dest = if (src is File) src else File(Store.dir("photos"), "p_${System.currentTimeMillis()}_$k.jpg")
                    if (src is Uri) contentResolver.openInputStream(src)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    val bm = Scans.bitmap(dest, 2000)
                    if (bm != null) {
                        dest.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        texts.add(Scans.recognize(bm))
                        added.add(dest.path)
                    }
                } catch (e: Exception) {
                }
            }
            h.post {
                d.dismiss()
                n.photos.addAll(added)
                val t = texts.joinToString("\n").trim()
                if (t.isNotEmpty()) n.photoText = (n.photoText + "\n" + t).trim()
                if (n.isRecipe && n.body.isBlank() && t.isNotEmpty()) n.body = t
                Store.touch(n)
                Store.save()
                if (added.isEmpty()) toast("Nie udało się dodać zdjęcia")
                render()
            }
        }
    }

    // ------------------------------------------------------------ przepisy
    private fun renderRecipe(n: Note) {
        val col = page(60)
        val top = hbox()
        top.addView(roundIcon(R.drawable.ic_back_arrow) { go("lib") }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = 0 })
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(roundIcon(if (recipeEdit) R.drawable.ic_box_on else R.drawable.ic_note) { recipeEdit = !recipeEdit; Store.save(); render() })
        top.addView(roundIcon(R.drawable.ic_more) { noteMenu(n) })
        col.addView(top)
        if (recipeEdit) {
            col.addView(tv("Edycja przepisu", 13f, true, accent), lp(top = 10))
            val title = edit(n.title, "Nazwa przepisu", plain = true).apply { textSize = 24f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
            watch(title) { n.title = it; Store.touch(n) }
            col.addView(title, lp(top = 6))
            if (n.photos.isEmpty()) col.addView(btn("📷 Dodaj zdjęcie potrawy", size = 14f) { addPhoto(n) }, lp(WRAP, WRAP, top = 6)) else photoStrip(col, n)
            val r = hbox()
            val serv = edit(if (n.servings > 0) n.servings.toString() else "", "Porcje", InputType.TYPE_CLASS_NUMBER)
            watch(serv) { n.servings = it.toIntOrNull() ?: 0; Store.touch(n) }
            r.addView(serv, lp(0, WRAP, 1f).apply { rightMargin = dp(8) })
            val time = edit(n.cookTime, "Czas, np. 45 min")
            watch(time) { n.cookTime = it; Store.touch(n) }
            r.addView(time, lp(0, WRAP, 1.4f))
            col.addView(r, lp(top = 12))
            section(col, "Składniki", 18)
            listEditor(col, n)
            section(col, "Przygotowanie", 18)
            val body = edit(n.body, "Każdy krok w nowej linii", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
                gravity = Gravity.TOP; minLines = 6
            }
            watch(body) { n.body = it; Store.touch(n) }
            col.addView(body, lp(top = 6))
            section(col, "Wskazówki", 18)
            val tips = edit(n.tips, "Porady – każda w nowej linii", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
                gravity = Gravity.TOP; minLines = 2
            }
            watch(tips) { n.tips = it; Store.touch(n) }
            col.addView(tips, lp(top = 6))
            col.addView(btn("Etykiety: " + n.labels.joinToString(", ").ifEmpty { "dodaj (np. Obiad, Ciasto)" }, size = 13f) { labelSheet(n) }, lp(WRAP, WRAP, top = 12))
            col.addView(primary("Gotowe") { recipeEdit = false; Store.save(); render() }, lp(top = 16))
            return
        }
        val photo = n.photos.firstOrNull()
        if (photo != null) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = round(surface, 20)
                clipToOutline = true
                setOnClickListener { photoViewer(n, photo) }
            }
            loadThumb(photo, iv)
            col.addView(iv, lp(MATCH, dp(220), top = 12))
        }
        col.addView(tv(n.title.ifEmpty { "Przepis" }, 26f, true), lp(top = 12))
        val base = if (n.servings > 0) n.servings else 0
        val info = listOfNotNull(n.cookTime.ifEmpty { null }?.let { "⏱ $it" }, n.labels.joinToString(", ").ifEmpty { null }).joinToString(" · ")
        if (info.isNotEmpty()) col.addView(tv(info, 14f, color = dim), lp(top = 4))
        if (base > 0) {
            val portions = Math.round(base * recipeScale).toInt().coerceAtLeast(1)
            val r = hbox()
            r.addView(btn("−", size = 16f) { if (portions > 1) { recipeScale = (portions - 1).toDouble() / base; render() } })
            r.addView(tv("$portions " + if (portions == 1) "porcja" else if (portions in 2..4) "porcje" else "porcji", 17f, true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            r.addView(btn("+", size = 16f) { recipeScale = (portions + 1).toDouble() / base; render() })
            col.addView(r, lp(top = 12))
            if (recipeScale != 1.0) col.addView(tv("Ilości przeliczone dla $portions porcji (przepis jest na $base).", 12f, color = dim), lp(top = 4))
        }
        val ingr = n.live
        if (ingr.isNotEmpty()) {
            section(col, "Składniki (${ingr.size})", 18)
            val p = panel(col)
            for (it2 in ingr) {
                val r = hbox()
                r.addView(icon(if (it2.done) R.drawable.ic_box_on else R.drawable.ic_box, 22, if (it2.done) dim else accent))
                r.addView(tv(Scale.line(it2.text, recipeScale), if (cookMode) 19f else 16f, false, if (it2.done) dim else fg), lp(0, WRAP, 1f).apply { leftMargin = dp(10) })
                r.setPadding(0, dp(8), 0, dp(8))
                r.setOnClickListener { it2.done = !it2.done; it2.touch(); Store.touch(n); render() }
                p.addView(r)
            }
            val br = hbox()
            br.addView(btn("🛒 Do listy zakupów", size = 13f) { toShopping(n) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
            if (ingr.any { it.done }) br.addView(btn("Odznacz wszystkie", size = 13f) { ingr.forEach { it.done = false; it.touch() }; Store.touch(n); render() })
            col.addView(HorizontalScrollView(this).apply { addView(br) }, lp(top = 8))
        }
        val steps = n.body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isNotEmpty()) {
            section(col, "Przygotowanie", 20)
            steps.forEachIndexed { k, s ->
                val sel = k == cookStep
                val r = hbox().apply {
                    gravity = Gravity.TOP
                    background = if (Store.eink) round(bg, 12, if (sel) 3 else 1, fg) else round(if (sel) Theme.soft else surface, 16)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { cookStep = if (sel) -1 else k; render() }
                }
                val num = tv("${k + 1}", 14f, true, onAccent).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
                }
                r.addView(num, LinearLayout.LayoutParams(dp(28), dp(28)))
                r.addView(TextView(this).apply {
                    text = Fmt.render(s.replace(Regex("^\\d+[.)]\\s*"), ""))
                    textSize = if (cookMode) 20f else 16f
                    setTextColor(fg)
                    setLineSpacing(0f, 1.15f)
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
                col.addView(r, lp(top = 8))
            }
        }
        val tipsList = n.tips.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (tipsList.isNotEmpty()) {
            section(col, "Wskazówki", 20)
            val tp = panel(col)
            tipsList.forEach { t -> tp.addView(tv("💡 $t", if (cookMode) 18f else 15f), lp(top = 8)) }
        }
        val br = hbox()
        br.addView(btn(if (cookMode) "Zakończ gotowanie" else "👩‍🍳 Tryb gotowania", cookMode, 13f) {
            cookMode = !cookMode
            if (cookMode) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            render()
        }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        br.addView(btn("Edytuj", size = 13f) { recipeEdit = true; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        br.addView(btn("Udostępnij", size = 13f) { shareNote(n) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        if (n.source.isNotEmpty()) br.addView(btn("Źródło", size = 13f) { openUrl(n.source) })
        col.addView(HorizontalScrollView(this).apply { addView(br) }, lp(top = 18))
        if (cookMode) col.addView(tv("Ekran nie zgaśnie. Stuknij krok, żeby go zaznaczyć.", 12f, color = dim), lp(top = 8))
    }

    /** Dodaje składniki (przeliczone) do wybranej listy zakupów. */
    private fun toShopping(n: Note) {
        val lists = Store.notes.filter { it.isList && it.deleted == 0L && !it.archived && !it.template }.sortedByDescending { it.updated }
        sheet("Dodaj składniki do listy") { col, d ->
            fun addTo(target: Note) {
                val have = target.live.filter { !it.done }.map { Store.norm(it.text) }.toSet()
                var k = 0
                for (it2 in n.live.filter { !it.done }) {
                    val t = Scale.line(it2.text, recipeScale)
                    if (Store.norm(t) in have) continue
                    target.items.add(Item(t)); k++
                }
                Store.touch(target)
                Store.save()
                NotesWidget.updateAll(this)
                if (target.shared) Shared.syncAsync(this)
                d.dismiss()
                toast("Dodano $k pozycji do: ${target.title.ifEmpty { "Lista" }}")
            }
            for (l in lists.take(15)) col.addView(btn((if (l.shared) "👥 " else "") + l.title.ifEmpty { "Lista bez tytułu" }) { addTo(l) }, lp(top = 8))
            col.addView(primary("Nowa lista „Zakupy”") {
                val l = Note(type = "list", title = "Zakupy")
                Store.notes.add(l)
                addTo(l)
            }, lp(top = 12))
        }
    }

    private fun recipeFromLink(prefill: String) {
        var url = prefill
        if (url.isEmpty()) {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.startsWith("http")) url = clip.trim()
        }
        sheet("Przepis z linku") { col, d ->
            col.addView(tv("Wklej adres strony z przepisem (np. z Kwestii Smaku, Aniagotuje, Kuchni Lidla). Pobiorę nazwę, składniki, kroki i zdjęcie.", 13f, color = dim), lp(top = 4))
            val e = edit(url, "https://…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            col.addView(e, lp(top = 10))
            col.addView(primary("Pobierz przepis") {
                val u = e.text.toString().trim()
                if (!u.startsWith("http")) { toast("Wklej pełny adres zaczynający się od http"); return@primary }
                d.dismiss()
                val (pd, label) = progress("Pobieram przepis")
                io.execute {
                    var err = ""
                    val res = try {
                        RecipeImport.load(u)
                    } catch (x: Exception) {
                        err = x.message ?: ""
                        null
                    }
                    var photo = ""
                    if (res != null && res.image.isNotEmpty()) {
                        h.post { label.text = "Pobieram zdjęcie…" }
                        val f = File(Store.dir("photos"), "r_${System.currentTimeMillis()}.jpg")
                        if (RecipeImport.downloadImage(res.image, f) && Scans.bitmap(f, 50) != null) photo = f.path else f.delete()
                    }
                    h.post {
                        pd.dismiss()
                        if (res == null) {
                            toast("Nie udało się pobrać strony" + if (err.isNotEmpty()) ": $err" else "")
                            return@post
                        }
                        area = "recipes"
                        val n = Note(type = "recipe", title = res.title, servings = res.servings, cookTime = res.time, source = res.url)
                        res.ingredients.forEach { n.items.add(Item(it)) }
                        n.body = res.steps.joinToString("\n")
                        n.tips = res.tips.joinToString("\n")
                        if (photo.isNotEmpty()) n.photos.add(photo)
                        Store.notes.add(n)
                        Store.touch(n)
                        Store.save()
                        recipeEdit = res.ingredients.isEmpty()
                        if (res.ingredients.isEmpty()) toast("Strona nie ma danych przepisu – uzupełnij ręcznie")
                        openNoteNow(n)
                    }
                }
            }, lp(top = 12))
        }
    }

    private fun audioPlayer(col: LinearLayout, n: Note) {
        val p = vbox().apply { background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 18); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val r = hbox()
        val play = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) } }
        val pi = icon(R.drawable.ic_play, 22, onAccent)
        play.addView(pi, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        r.addView(play, LinearLayout.LayoutParams(dp(48), dp(48)))
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
        }
        r.addView(seek, lp(0, WRAP, 1f))
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        var sp = 0
        val spB = btn("1×", size = 12f) {}
        r.addView(spB)
        p.addView(r)
        val time = tv("0:00 / " + hms(n.audioMs), 12f, color = dim)
        p.addView(time, lp(top = 4))
        col.addView(p, lp(top = 8))
        val updater = object : Runnable {
            override fun run() {
                val mp = player ?: return
                try {
                    if (mp.duration > 0) seek.progress = (mp.currentPosition * 1000L / mp.duration).toInt()
                    time.text = hms(mp.currentPosition.toLong()) + " / " + hms(mp.duration.toLong())
                    pi.setImageResource(if (mp.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                } catch (e: Exception) {
                }
                if (screen == "edit") h.postDelayed(this, 400)
            }
        }
        fun ensure(): MediaPlayer? {
            player?.let { return it }
            return try {
                MediaPlayer().apply {
                    setDataSource(n.audio)
                    prepare()
                    setOnCompletionListener { pi.setImageResource(R.drawable.ic_play) }
                }.also { player = it }
            } catch (e: Exception) {
                toast("Nie mogę odtworzyć nagrania"); null
            }
        }
        play.setOnClickListener {
            val mp = ensure() ?: return@setOnClickListener
            if (mp.isPlaying) mp.pause() else {
                mp.start()
                try { mp.playbackParams = PlaybackParams().setSpeed(speeds[sp]) } catch (e: Exception) {}
                h.post(updater)
            }
            pi.setImageResource(if (mp.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        spB.setOnClickListener {
            sp = (sp + 1) % speeds.size
            spB.text = (if (speeds[sp] % 1f == 0f) speeds[sp].toInt().toString() else speeds[sp].toString().replace(".", ",")) + "×"
            val mp = player
            if (mp != null && mp.isPlaying) try { mp.playbackParams = PlaybackParams().setSpeed(speeds[sp]) } catch (e: Exception) {}
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) ensure()?.let { it.seekTo((it.duration.toLong() * v / 1000).toInt()) }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun scanView(col: LinearLayout, n: Note) {
        val file = File(n.pdf)
        val pages = hbox().apply { gravity = Gravity.TOP }
        for (p in 0 until minOf(n.pages, 20)) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = round(Color.WHITE, 10, 1)
                setOnClickListener { openPdf(n) }
            }
            pages.addView(iv, LinearLayout.LayoutParams(dp(150), dp(210)).apply { rightMargin = dp(10) })
            val key = n.pdf + "#" + p
            val c = thumbs.get(key)
            if (c != null) iv.setImageBitmap(c) else io.execute {
                val bm = Scans.render(file, p, 450)
                h.post { if (bm != null) { thumbs.put(key, bm); iv.setImageBitmap(bm) } }
            }
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pages) }, lp(top = 10))
        col.addView(tv("PDF · ${n.pages} str. · zapisany też w Dokumenty/Skany", 12f, color = dim), lp(top = 6))
        val r = hbox()
        r.addView(btn("Otwórz PDF", true, 13f) { openPdf(n) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        r.addView(btn("Udostępnij", size = 13f) { shareNote(n) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        if (n.ocr.isNotBlank()) r.addView(btn("Kopiuj tekst", size = 13f) { copy(n.ocr, "Skopiowano tekst ze skanu") })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 10))
        val body = edit(n.body, "Twoja notatka do skanu", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, plain = true)
        watch(body) { n.body = it; Store.touch(n) }
        col.addView(body, lp(top = 10))
        if (n.ocr.isNotBlank()) {
            section(col, "Odczytany tekst", 14)
            col.addView(tv(n.ocr.trim(), 14f, color = dim).apply { setTextIsSelectable(true) }, lp(top = 6))
        }
    }

    private fun fileUri(path: String): Uri = FileProvider.getUriForFile(this, "pl.notatnik.files", File(path))

    private fun openPdf(n: Note) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(n.pdf), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("Brak aplikacji do otwierania PDF")
        }
    }

    private fun copy(text: String, msg: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Notatnik", text))
        toast(msg)
    }

    private fun noteText(n: Note): String = listOf(
        n.title,
        if (n.isList || n.isRecipe) n.live.joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text } + (if (n.isRecipe) "\n\n" + n.body else "") else n.body
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    private fun shareNote(n: Note) {
        val i = Intent(Intent.ACTION_SEND)
        when {
            n.type == "scan" && n.pdf.isNotEmpty() -> i.setType("application/pdf").putExtra(Intent.EXTRA_STREAM, fileUri(n.pdf))
                .putExtra(Intent.EXTRA_SUBJECT, n.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            n.audio.isNotEmpty() && n.body.isBlank() -> i.setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, fileUri(n.audio))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else -> i.setType("text/plain").putExtra(Intent.EXTRA_TEXT, noteText(n)).putExtra(Intent.EXTRA_SUBJECT, n.title)
        }
        startActivity(Intent.createChooser(i, "Udostępnij"))
    }

    // ------------------------------------------------------------ okna: menu, kolor, etykiety, przypomnienie
    private fun noteMenu(n: Note) {
        sheet(n.title.ifEmpty { if (n.isRecipe) "Przepis" else "Notatka" }) { col, d ->
            fun add(t: String, f: () -> Unit) = col.addView(btn(t) { d.dismiss(); f() }, lp(top = 8))
            if (n.template) {
                add("Utwórz notatkę z tego szablonu") { val c = copyOf(n, false, true); Store.notes.add(c); Store.save(); openNote(c) }
                add("Usuń szablon") { Store.notes.remove(n); Store.save(); cur = null; go("lib") }
                return@sheet
            }
            add(if (n.pinned) "Odepnij" else "Przypnij na górze") { n.pinned = !n.pinned; Store.touch(n); Store.save(); render() }
            add("Kolor") { colorSheet(n) }
            add("Etykiety") { labelSheet(n) }
            add("Przypomnienie") { reminderSheet(n) }
            add("Dodaj zdjęcie") { addPhoto(n) }
            if (Store.sharedRepo.isNotEmpty()) {
                if (!n.shared) add("👥 Wspólna z żoną") {
                    n.shared = true; Store.touch(n); Store.save()
                    Shared.syncAsync(this) { h.post { toast("Udostępniono – pojawi się u drugiej osoby po synchronizacji") } }
                    render()
                } else add("Przestań współdzielić") {
                    io.execute { try { Shared.unshare(n) } catch (e: Exception) {} }
                    render()
                }
            }
            add("Duplikuj") { val c = copyOf(n, false, false); c.title = (n.title + " (kopia)").trim(); Store.notes.add(c); Store.save(); openNote(c) }
            if (!n.isRecipe) add("Zapisz jako szablon") { val c = copyOf(n, true, true); Store.notes.add(c); Store.save(); toast("Zapisano szablon – znajdziesz go pod przyciskiem Szablon") }
            add(if (n.locked) "Odblokuj (wyłącz odcisk)" else "🔒 Zablokuj odciskiem") {
                if (n.locked) authenticate("Wyłącz blokadę") { n.locked = false; Store.touch(n); Store.save(); render() }
                else { n.locked = true; Store.touch(n); Store.save(); NotesWidget.updateAll(this); toast("Notatka zablokowana"); render() }
            }
            add("Kopiuj tekst") { copy(noteText(n), "Skopiowano") }
            add("Udostępnij") { shareNote(n) }
            add(if (n.archived) "Przywróć z archiwum" else "Archiwizuj") {
                n.archived = !n.archived; Store.touch(n); Store.save(); Reminders.schedule(this, n); NotesWidget.updateAll(this)
                if (screen == "edit") go("lib") else render()
            }
            add("Usuń (do kosza)") {
                moveToTrash(n)
                if (screen == "edit") { cur = null; screen = "lib" }
                render()
            }
        }
    }

    private fun colorSheet(n: Note) {
        sheet("Kolor notatki") { col, d ->
            val views = Store.COLORS_LIGHT.indices.map { k ->
                val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { n.color = k; Store.touch(n); Store.save(); d.dismiss(); render() } }
                val c = if (k == 0) surface else if (Theme.dark) Store.COLORS_DARK[k] else Store.COLORS_LIGHT[k]
                v.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(if (n.color == k) 3 else 1), if (n.color == k) fg else lineC) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                v.addView(tv(Store.COLOR_NAMES[k], 10f, color = dim).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(top = 4))
                v as View
            }
            grid(col, 4, views, 8)
        }
    }

    private fun labelSheet(n: Note) {
        sheet("Etykiety") { col, d ->
            val all = (Store.labels(Store.area(n)) + n.labels).distinct().sorted()
            if (all.isNotEmpty()) {
                val r = vbox()
                all.forEach { l ->
                    r.addView(btn((if (l in n.labels) "✓ " else "") + l, l in n.labels, 14f) {
                        if (l in n.labels) n.labels.remove(l) else n.labels.add(l)
                        Store.touch(n); Store.save(); d.dismiss(); labelSheet(n)
                    }, lp(WRAP, WRAP, top = 6))
                }
                col.addView(r)
            }
            val e = edit("", "Nowa etykieta, np. Dom, Praca, Zakupy")
            col.addView(e, lp(top = 12))
            col.addView(primary("Dodaj etykietę") {
                val t = e.text.toString().trim()
                if (t.isNotEmpty() && t !in n.labels) n.labels.add(t)
                Store.touch(n); Store.save(); d.dismiss(); render()
            }, lp(top = 8))
            col.addView(btn("Gotowe") { d.dismiss(); render() }, lp(top = 8))
        }
    }

    /** Ustawia przypomnienie: [at] = pierwszy termin, [repeat] = "" (jednorazowe) albo rodzaj powtarzania. */
    private fun setReminder(n: Note, at: Long, repeat: String = "", days: Int = 0, every: Int = 1, unit: String = "d") {
        n.reminder = at
        n.repeat = if (at > 0) repeat else ""
        n.repDays = days
        n.repEvery = every
        n.repUnit = unit
        Store.touch(n)
        Reminders.schedule(this, n)
        Store.save()
        NotesWidget.updateAll(this)
        if (at > 0 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        render()
        if (at > 0) toast("Przypomnienie: " + Reminders.describe(n))
    }

    private fun pickTime(h0: Int, m0: Int, done: (Int, Int) -> Unit) {
        TimePickerDialog(this, { _, hh, mm -> done(hh, mm) }, h0, m0, true).show()
    }

    private fun pickDateTime(start: Long, done: (Long) -> Unit) {
        val c = Calendar.getInstance()
        if (start > 0) c.timeInMillis = start
        DatePickerDialog(this, { _, y, m, dd ->
            pickTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) { hh, mm ->
                val k = Calendar.getInstance()
                k.set(y, m, dd, hh, mm, 0)
                k.set(Calendar.MILLISECOND, 0)
                done(k.timeInMillis)
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun reminderSheet(n: Note) {
        sheet("Przypomnienie") { col, d ->
            if (n.reminder > 0) {
                val cur = hbox().apply { background = if (Store.eink) round(bg, 12, 2, fg) else round(surface, 14); setPadding(dp(14), dp(10), dp(10), dp(10)) }
                cur.addView(icon(R.drawable.ic_bell, 20, accent))
                cur.addView(tv(Reminders.describe(n), 15f, true), lp(0, WRAP, 1f).apply { leftMargin = dp(10) })
                cur.addView(btn("Usuń", size = 13f) { d.dismiss(); setReminder(n, 0) })
                col.addView(cur, lp(top = 10))
            }

            // --- jednorazowo
            section(col, "Jednorazowo", 16)
            fun at(daysAhead: Int, hour: Int): Long {
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, daysAhead)
                c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                return c.timeInMillis
            }
            val now = System.currentTimeMillis()
            val quick = mutableListOf<Pair<String, Long>>()
            quick.add("Za 15 min" to now + 15 * 60_000L)
            quick.add("Za godzinę" to now + 3600_000L)
            if (at(0, 18) > now + 60_000) quick.add("Dziś 18:00" to at(0, 18))
            quick.add("Jutro 9:00" to at(1, 9))
            grid(col, 2, quick.map { (label, t) -> btn(label, size = 14f) { d.dismiss(); setReminder(n, t) } as View }, 8)

            col.addView(tv("Za określony czas", 14f, color = dim), lp(top = 12))
            val afterRow = hbox()
            val eNum = edit("2", "", InputType.TYPE_CLASS_NUMBER).apply { gravity = Gravity.CENTER }
            afterRow.addView(eNum, LinearLayout.LayoutParams(dp(70), WRAP))
            var unit = "h"
            val units = listOf("m" to "min", "h" to "godz.", "d" to "dni", "w" to "tyg.")
            val unitRow = hbox()
            fun drawUnits() {
                unitRow.removeAllViews()
                units.forEach { (k, label) -> unitRow.addView(btn(label, unit == k, 13f) { unit = k; drawUnits() }, lp(WRAP, WRAP).apply { leftMargin = dp(6) }) }
            }
            drawUnits()
            afterRow.addView(unitRow)
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(afterRow) }, lp(top = 6))
            col.addView(btn("Ustaw „za…”", size = 14f) {
                val v = eNum.text.toString().toLongOrNull() ?: 0L
                if (v <= 0) { toast("Wpisz liczbę"); return@btn }
                val ms = when (unit) { "m" -> 60_000L; "h" -> 3600_000L; "d" -> 86_400_000L; else -> 7 * 86_400_000L }
                d.dismiss()
                setReminder(n, System.currentTimeMillis() + v * ms)
            }, lp(top = 8))
            col.addView(primary("Konkretny dzień i godzina") {
                d.dismiss()
                pickDateTime(n.reminder) { t -> setReminder(n, t) }
            }, lp(top = 10))

            // --- powtarzanie
            section(col, "Powtarzaj", 22)
            col.addView(btn("Codziennie o wybranej godzinie", size = 14f) {
                d.dismiss()
                pickTime(8, 0) { hh, mm ->
                    val c = Calendar.getInstance()
                    c.set(Calendar.HOUR_OF_DAY, hh); c.set(Calendar.MINUTE, mm); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
                    setReminder(n, c.timeInMillis, "daily")
                }
            }, lp(top = 8))

            col.addView(tv("W wybrane dni tygodnia", 14f, color = dim), lp(top = 12))
            var mask = if (n.repeat == "weekdays") n.repDays else 0
            val dayRow = hbox()
            fun drawDays() {
                dayRow.removeAllViews()
                Reminders.DAY_NAMES.forEachIndexed { i, name ->
                    val on = mask and (1 shl i) != 0
                    dayRow.addView(btn(name, on, 13f) { mask = mask xor (1 shl i); drawDays() }, lp(0, WRAP, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
                }
            }
            drawDays()
            col.addView(dayRow, lp(top = 6))
            val presets = hbox()
            presets.addView(btn("Dni robocze", size = 12f) { mask = 0b0011111; drawDays() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
            presets.addView(btn("Weekend", size = 12f) { mask = 0b1100000; drawDays() }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
            presets.addView(btn("Wyczyść", size = 12f) { mask = 0; drawDays() })
            col.addView(presets, lp(top = 6))
            col.addView(btn("Ustaw dni i godzinę", size = 14f) {
                if (mask == 0) { toast("Zaznacz przynajmniej jeden dzień"); return@btn }
                d.dismiss()
                pickTime(8, 0) { hh, mm -> setReminder(n, Reminders.firstWeekday(mask, hh, mm), "weekdays", mask) }
            }, lp(top = 8))

            col.addView(tv("Co określony czas", 14f, color = dim), lp(top = 12))
            val everyRow = hbox()
            val eEvery = edit("2", "", InputType.TYPE_CLASS_NUMBER).apply { gravity = Gravity.CENTER }
            everyRow.addView(eEvery, LinearLayout.LayoutParams(dp(70), WRAP))
            var eu = "d"
            val euRow = hbox()
            fun drawEu() {
                euRow.removeAllViews()
                listOf("h" to "godz.", "d" to "dni", "w" to "tyg.").forEach { (k, label) ->
                    euRow.addView(btn(label, eu == k, 13f) { eu = k; drawEu() }, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
                }
            }
            drawEu()
            everyRow.addView(euRow)
            col.addView(everyRow, lp(top = 6))
            col.addView(btn("Ustaw od… (wybierz pierwszy termin)", size = 14f) {
                val v = eEvery.text.toString().toIntOrNull() ?: 0
                if (v <= 0) { toast("Wpisz liczbę"); return@btn }
                d.dismiss()
                pickDateTime(0) { t -> setReminder(n, t, "every", 0, v, eu) }
            }, lp(top = 8))

            val more = hbox()
            more.addView(btn("Co miesiąc", size = 14f) { d.dismiss(); pickDateTime(n.reminder) { t -> setReminder(n, t, "monthly") } }, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
            more.addView(btn("Co rok", size = 14f) { d.dismiss(); pickDateTime(n.reminder) { t -> setReminder(n, t, "yearly") } }, lp(0, WRAP, 1f))
            col.addView(more, lp(top = 12))
            col.addView(tv("Przy „Co miesiąc” i „Co rok” wybierz pierwszy termin – kolejne będą tego samego dnia. W powiadomieniu są przyciski Gotowe i Odłóż 10 min.", 12f, color = dim), lp(top = 10))
        }
    }

    // ------------------------------------------------------------ dyktowanie (na żywo, offline jeśli telefon pozwala)
    private var recognizer: SpeechRecognizer? = null
    private var dictating = false

    private fun withMic(f: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else {
            afterMic = f
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun dictateInto(n: Note) = withMic {
        dictate { text ->
            if (n.isList) n.items.add(Item(text.replaceFirstChar { it.uppercase() }))
            else n.body = (n.body.trimEnd() + (if (n.body.isBlank()) "" else " ") + text.replaceFirstChar { if (n.body.isBlank() || n.body.trimEnd().endsWith(".")) it.uppercase() else it.toString() }).trim()
            Store.touch(n)
        }
    }

    private fun stopDictation() {
        dictating = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun dictate(onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Rozpoznawanie mowy niedostępne – zainstaluj aplikację Google")
            return
        }
        var committed = ""
        lateinit var live: TextView
        val dlg = sheet("Dyktowanie") { col, d ->
            col.addView(tv("Mów – tekst pojawia się na bieżąco. Pauza nie przerywa dyktowania.", 13f, color = dim), lp(top = 4))
            live = tv("…", 18f).apply { minLines = 3 }
            col.addView(live, lp(top = 12))
            col.addView(primary("Zakończ") { d.dismiss() }, lp(top = 16))
        }
        dlg.setOnDismissListener {
            stopDictation()
            render()
        }
        dictating = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                live.text = (committed + " " + p).trim()
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!t.isNullOrEmpty()) {
                    onText(t)
                    committed = (committed + " " + t).trim()
                    live.text = committed
                }
                if (dictating) try { sr.startListening(intent) } catch (e: Exception) {}
            }

            override fun onError(error: Int) {
                if (!dictating) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    try { sr.startListening(intent) } catch (e: Exception) {}
                } else {
                    toast("Dyktowanie przerwane (kod $error)")
                    dlg.dismiss()
                }
            }
        })
        sr.startListening(intent)
    }

    // ------------------------------------------------------------ nagrywanie
    private fun startRecording(attachTo: String?) {
        if (RecorderService.running) {
            toast("Już nagrywam")
            return
        }
        RecorderService.attachTo = attachTo
        RecorderService.onStopped = { n -> openNote(n) }
        startForegroundService(Intent(this, RecorderService::class.java))
        if (screen == "edit") go("lib") else h.postDelayed({ render() }, 300)
    }

    private fun stopRecording() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACT_STOP))
    }

    // ------------------------------------------------------------ skanowanie
    private fun startScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                try {
                    startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0)
                } catch (e: Exception) {
                    toast("Nie mogę uruchomić skanera")
                }
            }
            .addOnFailureListener { e -> toast("Skaner niedostępny (${e.message}). Zaktualizuj Usługi Google Play.") }
    }

    private fun progress(title: String): Pair<Dialog, TextView> {
        var t: TextView? = null
        val d = sheet(title) { col, _ ->
            val v = tv("Chwileczkę…", 15f)
            t = v
            col.addView(v, lp(top = 10))
        }
        d.setCancelable(false)
        return Pair(d, t ?: tv(""))
    }

    private fun processScan(pdf: Uri, pages: Int, jpgs: List<Uri>) {
        val (d, label) = progress("Zapisuję skan")
        io.execute {
            val n = try {
                Scans.fromScanner(this, pdf, pages, jpgs) { m -> h.post { label.text = m } }
            } catch (e: Exception) {
                null
            }
            h.post {
                d.dismiss()
                if (n == null) toast("Nie udało się zapisać skanu") else {
                    Store.notes.add(n)
                    Store.touch(n)
                    Store.save()
                    toast("Zapisano: ${n.title}")
                    openNote(n)
                }
            }
        }
    }

    private fun imageNote(uri: Uri) {
        val (d, label) = progress("Odczytuję obraz")
        io.execute {
            val n = Note(type = "text")
            val f = File(Store.dir("images"), n.id + ".jpg")
            try {
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                h.post { label.text = "Odczytuję tekst…" }
                val bm = Scans.bitmap(f, 1800)
                n.photos.add(f.path)
                n.body = if (bm != null) Scans.recognize(bm) else ""
                n.title = if (n.body.isNotBlank()) Scans.suggestName(n.body) else "Obraz"
            } catch (e: Exception) {
            }
            h.post {
                d.dismiss()
                Store.notes.add(n)
                Store.touch(n)
                Store.save()
                openNote(n)
            }
        }
    }

    // ------------------------------------------------------------ blokada odciskiem
    private fun authenticate(title: String, ok: () -> Unit) {
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(title)
                .setDeviceCredentialAllowed(true)
                .build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = ok()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT || errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                        toast("Brak blokady ekranu w telefonie – notatka otwarta bez zabezpieczenia")
                        ok()
                    }
                }
            })
        } catch (e: Exception) {
            ok()
        }
    }

    // ------------------------------------------------------------ skan: dokument albo wizytówka
    private fun scanChoice() {
        sheet("Skanuj") { col, d ->
            col.addView(primary("Dokument → PDF") { d.dismiss(); startScan() }, lp(top = 10))
            col.addView(btn("Wizytówka → kontakt") { d.dismiss(); startCardScan() }, lp(top = 8))
            col.addView(tv("Wizytówka: aplikacja odczyta imię, firmę, telefon i e-mail i zaproponuje zapis w Kontaktach.", 12f, color = dim), lp(top = 10))
        }
    }

    private fun startCardScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                try {
                    startIntentSenderForResult(sender, REQ_CARD, null, 0, 0, 0)
                } catch (e: Exception) {
                    toast("Nie mogę uruchomić skanera")
                }
            }
            .addOnFailureListener { e -> toast("Skaner niedostępny (${e.message})") }
    }

    private fun processCard(img: Uri) {
        val (d, _) = progress("Odczytuję wizytówkę")
        io.execute {
            val f = File(Store.dir("photos"), "card_${System.currentTimeMillis()}.jpg")
            var text = ""
            try {
                contentResolver.openInputStream(img)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val bm = Scans.bitmap(f, 2000)
                if (bm != null) text = Scans.recognize(bm)
            } catch (e: Exception) {
            }
            val data = BizCard.parse(text)
            h.post {
                d.dismiss()
                cardSheet(data, f.path, text)
            }
        }
    }

    private fun cardSheet(data: BizCard.Data, photo: String, raw: String) {
        sheet("Wizytówka") { col, d ->
            col.addView(tv("Sprawdź i popraw odczytane dane:", 13f, color = dim), lp(top = 4))
            fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS): EditText {
                col.addView(tv(label, 13f, color = dim), lp(top = 10))
                val e = edit(value, label, type)
                col.addView(e, lp(top = 4))
                return e
            }
            val name = field("Imię i nazwisko", data.name)
            val company = field("Firma", data.company)
            val title = field("Stanowisko", data.title)
            val phone = field("Telefon", data.phone, InputType.TYPE_CLASS_PHONE)
            val email = field("E-mail", data.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val www = field("Strona", data.www, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val addr = field("Adres", data.address)
            fun saveNote(): Note {
                val n = Note(type = "card", title = name.text.toString().ifBlank { company.text.toString() }.ifBlank { "Wizytówka" })
                n.body = listOf(name, company, title, phone, email, www, addr).map { it.text.toString().trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                n.photos.add(photo)
                n.photoText = raw
                Store.notes.add(n)
                Store.touch(n)
                Store.save()
                return n
            }
            col.addView(primary("Zapisz w Kontaktach") {
                saveNote()
                val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, name.text.toString())
                    .putExtra(ContactsContract.Intents.Insert.COMPANY, company.text.toString())
                    .putExtra(ContactsContract.Intents.Insert.JOB_TITLE, title.text.toString())
                    .putExtra(ContactsContract.Intents.Insert.PHONE, phone.text.toString())
                    .putExtra(ContactsContract.Intents.Insert.EMAIL, email.text.toString())
                    .putExtra(ContactsContract.Intents.Insert.POSTAL, addr.text.toString())
                    .putExtra(ContactsContract.Intents.Insert.NOTES, www.text.toString())
                d.dismiss()
                try {
                    startActivity(i)
                } catch (e: Exception) {
                    toast("Brak aplikacji Kontakty")
                }
                render()
            }, lp(top = 16))
            col.addView(btn("Zapisz tylko jako notatkę") { val n = saveNote(); d.dismiss(); openNote(n) }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ pliki: zaproszenie do wspólnych notatek, kopia ZIP
    private fun handleFile(uri: Uri) {
        io.execute {
            var invite: JSONObject? = null
            var backup = false
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    java.util.zip.ZipInputStream(input).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            if (e.name == "wspolne.json") invite = JSONObject(String(z.readBytes(), Charsets.UTF_8))
                            if (e.name == "notatnik.json") backup = true
                        }
                    }
                }
            } catch (e: Exception) {
            }
            h.post {
                val inv = invite
                when {
                    inv != null && inv.optString("type") == "notatnik-shared" -> sheet("Wspólne notatki") { col, d ->
                        col.addView(tv("Dołączyć do wspólnych notatek (${inv.optString("repo")})? Wspólne listy pojawią się u Ciebie i będą się synchronizować.", 14f), lp(top = 6))
                        col.addView(primary("Dołącz") {
                            Shared.join(inv.optString("repo"), inv.optString("token"))
                            Shared.schedule(this)
                            d.dismiss()
                            runBg("Synchronizacja") { "Pobrano wspólnych notatek: " + Shared.sync() }
                        }, lp(top = 12))
                        col.addView(btn("Anuluj") { d.dismiss() }, lp(top = 8))
                    }
                    backup -> sheet("Kopia Notatnika") { col, d ->
                        col.addView(tv("Wczytać notatki z tej kopii? Istniejące zostaną, dojdą brakujące.", 14f), lp(top = 6))
                        col.addView(primary("Wczytaj") { d.dismiss(); runBg("Wczytuję kopię") { "Dodano lub zaktualizowano: " + Sync.restoreZip(this, uri) } }, lp(top = 12))
                    }
                    else -> toast("To nie jest plik Notatnika")
                }
            }
        }
    }

    // ------------------------------------------------------------ intencje
    private fun consumeIntent() {
        val i = pendingIntent ?: return
        pendingIntent = null
        handleInstall(i)
        when (i.action) {
            "pl.notatnik.NEW" -> newNote(i.getStringExtra("kind") ?: "text")
            Intent.ACTION_SEND -> {
                val type = i.type ?: ""
                @Suppress("DEPRECATION")
                val stream = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null && (type.contains("zip") || type == "application/octet-stream")) {
                    handleFile(stream)
                    return
                }
                val shared = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val link = Regex("https?://\\S+").find(shared)?.value
                if (link != null && !type.startsWith("image/")) {
                    sheet("Zapisać link") { col, d ->
                        col.addView(primary("🍳 Importuj przepis z tej strony") { d.dismiss(); recipeFromLink(link) }, lp(top = 10))
                        col.addView(btn("Zapisz jako notatkę") {
                            d.dismiss()
                            val n = Note(type = "text", title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "", body = shared)
                            Store.notes.add(n); Store.touch(n); Store.save(); openNote(n)
                        }, lp(top = 8))
                    }
                    return
                }
                if (type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (u != null) imageNote(u)
                } else {
                    val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val n = Note(type = "text", title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "", body = text)
                    Store.notes.add(n)
                    Store.touch(n)
                    Store.save()
                    openNote(n)
                }
            }
            Intent.ACTION_VIEW -> {
                val n = Store.note(i.getStringExtra("note"))
                if (n != null) openNote(n) else i.data?.let { if (it.scheme == "content" || it.scheme == "file") handleFile(it) }
            }
        }
    }

    // ------------------------------------------------------------ ustawienia
    private fun swatch(pr: Theme.Preset, selected: Boolean, onClick: () -> Unit): View {
        val (a, b) = Theme.colorsOf(this, pr)
        val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(4)); setOnClickListener { onClick() } }
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); if (selected) setStroke(dp(3), fg) }
        }
        ring.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { shape = GradientDrawable.OVAL }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        v.addView(ring, LinearLayout.LayoutParams(dp(50), dp(50)))
        v.addView(tv(pr.name, 11f, selected, if (selected) fg else dim).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 2))
        return v
    }

    private fun ago(t: Long): String = if (t <= 0) "nigdy" else dateFmt.format(Date(t))

    private fun runBg(label: String, job: () -> String) {
        val (d, l) = progress(label)
        io.execute {
            val msg = try {
                job()
            } catch (e: Exception) {
                "Błąd: " + (e.message ?: "nieznany")
            }
            h.post { d.dismiss(); l.text = msg; toast(msg); NotesWidget.updateAll(this); render() }
        }
    }

    private fun renderSettings() {
        val col = page()
        val top = hbox()
        top.addView(roundIcon(R.drawable.ic_back_arrow) { go("lib") }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = 0 })
        col.addView(top)
        col.addView(tv("Ustawienia", 30f, true), lp(top = 10))

        section(col, "Wygląd")
        var p = panel(col)
        seg(p, "Styl", listOf("Kolorowy" to !Store.eink, "E-ink (czarno-biały)" to Store.eink)) { Store.eink = it == 1; Store.save(); NotesWidget.updateAll(this); render() }
        if (Store.eink) seg(p, "Kolory e-ink", listOf("Czarne na białym" to !Store.invert, "Białe na czarnym" to Store.invert)) { Store.invert = it == 1; Store.save(); render() }
        else {
            p.addView(tv("Motyw kolorów", 15f), lp(top = 12))
            grid(p, 4, Theme.PRESETS.filter { it.id != "auto" || Theme.hasWallpaperColors() }.map { pr ->
                swatch(pr, Store.theme == pr.id) { Store.theme = pr.id; Store.save(); NotesWidget.updateAll(this); render() }
            }, 4)
            seg(p, "Tryb", Theme.MODES.map { (id, label) -> label to (Store.mode == id) }) { Store.mode = Theme.MODES[it].first; Store.save(); render() }
        }
        seg(p, "Układ", listOf("Siatka" to (Store.layout == "grid"), "Lista" to (Store.layout == "list"))) { Store.layout = if (it == 0) "grid" else "list"; Store.save(); render() }
        val starts = listOf("last" to "Ostatnio używane", "notes" to "Notatki", "recordings" to "Nagrania", "recipes" to "Przepisy", "scans" to "Skany")
        seg(p, "Na starcie otwieraj", starts.map { (k, l) -> l to (Store.startArea == k) }) { Store.startArea = starts[it].first; Store.save(); render() }

        section(col, "Widżety")
        p = panel(col)
        p.addView(tv("Na ekranie głównym przytrzymaj puste miejsce → Widżety → Notatnik. Są dwa: „Notatki” (zakładki z listami, odhaczanie, dodawanie) i „Szybka notatka” (5 przycisków). Rozmiar zmieniasz, przytrzymując widżet i rozciągając krawędzie. Działa w launcherach obsługujących widżety.", 13f, color = dim), lp(top = 10))
        val wids = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, NotesWidget::class.java))
        wids.forEachIndexed { k, id ->
            p.addView(btn("Ustaw widżet notatek ${k + 1}", size = 14f) {
                startActivity(Intent(this, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            }, lp(top = 8))
        }

        section(col, "Skany")
        p = panel(col)
        seg(p, "Zapisuj też strony jako JPG (Zdjęcia/Skany)", listOf("Tak" to Store.scanJpg, "Nie" to !Store.scanJpg)) { Store.scanJpg = it == 0; Store.save(); render() }
        p.addView(btn("Wczytaj stare skany z folderu (np. Dokumenty/Skany)", size = 14f) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SCANS_DIR) } catch (e: Exception) { toast("Nie mogę otworzyć wyboru folderu") }
        }, lp(top = 10))

        section(col, "Import z Google Keep")
        p = panel(col)
        p.addView(tv("1. Otwórz Google Takeout, odznacz wszystko, zaznacz tylko Keep i pobierz archiwum ZIP.\n2. Wybierz tutaj pobrany plik – notatki, listy, kolory, etykiety i zdjęcia przejdą do Notatnika.", 13f, color = dim), lp(top = 10))
        val kr = hbox()
        kr.addView(btn("Otwórz Takeout", size = 14f) { openUrl("https://takeout.google.com/settings/takeout/custom/keep") }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        kr.addView(btn("Wybierz ZIP z Keep", true, 14f) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_KEEP) } catch (e: Exception) { toast("Nie mogę otworzyć pliku") }
        })
        p.addView(kr, lp(top = 10))

        section(col, "Wspólne notatki (z żoną)")
        p = panel(col)
        if (Store.sharedRepo.isEmpty()) {
            p.addView(tv("Wspólne listy i notatki synchronizują się między telefonami (co minutę przy otwartej aplikacji i co ok. 15 min w tle). Działa przez prywatne repozytorium na Twoim GitHubie.", 13f, color = dim), lp(top = 10))
            if (Store.ghToken.isNotBlank()) p.addView(primary("Utwórz wspólną przestrzeń") {
                runBg("Tworzę wspólne notatki") { val r = Shared.createSpace(); Shared.schedule(this); "Gotowe: $r" }
            }, lp(top = 10))
            p.addView(btn("Mam zaproszenie – wczytaj plik", size = 14f) {
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_INVITE) } catch (e: Exception) { toast("Nie mogę otworzyć pliku") }
            }, lp(top = 8))
        } else {
            item(p, tv("Połączono", 15f, color = dim), tv(Store.sharedRepo, 13f, true))
            p.addView(tv("Ostatnia synchronizacja: " + ago(Store.sharedLast), 13f, color = dim), lp(top = 4))
            p.addView(tv("Żeby udostępnić listę: otwórz ją → menu ⋯ → „Wspólna z żoną”.", 13f, color = dim), lp(top = 6))
            p.addView(btn("Synchronizuj teraz", size = 14f) { runBg("Synchronizacja") { "Zmiany z serwera: " + Shared.sync() } }, lp(top = 8))
            if (Store.sharedToken.isBlank()) {
                p.addView(tv("Zaproszenie dla żony", 15f, true), lp(top = 16))
                p.addView(tv("1. Utwórz dla niej osobny token: Repository access → Only select repositories → notatki-wspolne, notatnik (i karty, jeśli używa Kart). Permissions → Contents: Read and write. Generate token, skopiuj.\n2. Wklej go poniżej i wyślij zaproszenie. Ten token daje dostęp tylko do wybranych repozytoriów, a w jej aplikacji służy też do aktualizacji.", 13f, color = dim), lp(top = 6))
                p.addView(btn("Otwórz GitHub – nowy token", size = 14f) { openUrl("https://github.com/settings/personal-access-tokens/new") }, lp(top = 8))
                val te = edit(Store.inviteToken, "github_pat_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                p.addView(te, lp(top = 8))
                p.addView(primary("Wyślij zaproszenie (WhatsApp / e-mail)") {
                    val t = te.text.toString().trim()
                    if (t.isEmpty()) { toast("Najpierw wklej token dla żony"); return@primary }
                    Store.inviteToken = t
                    Store.save()
                    val f = Shared.inviteFile(this)
                    val i = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, "pl.notatnik.files", f))
                        .putExtra(Intent.EXTRA_TEXT, "Zaproszenie do wspólnych notatek. Otwórz ten plik w aplikacji Notatnik.").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Wyślij zaproszenie"))
                }, lp(top = 8))
            }
            p.addView(btn("Odłącz wspólne notatki", size = 14f) {
                Store.sharedRepo = ""; Store.sharedToken = ""
                Store.notes.forEach { it.shared = false }
                Store.save(); Shared.schedule(this); render()
            }, lp(top = 12))
        }

        section(col, "Kopia na GitHubie (serwer)")
        p = panel(col)
        p.addView(tv("Tekst notatek, list i skanów trafia do prywatnego repozytorium „${Store.ghRepo}” na Twoim GitHubie. Używa tego samego tokenu co aktualizacje.", 13f, color = dim), lp(top = 10))
        if (Store.ghToken.isBlank()) {
            val e = edit("", "ghp_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            p.addView(e, lp(top = 8))
            p.addView(btn("Zapisz token", size = 14f) { Store.ghToken = e.text.toString().trim(); Store.save(); checkUpdate(true) }, lp(top = 8))
        } else {
            seg(p, "Automatycznie po zmianach", listOf("Tak" to Store.ghAuto, "Nie" to !Store.ghAuto)) { Store.ghAuto = it == 0; Store.save(); render() }
            p.addView(tv("Ostatnia kopia: " + ago(Store.ghLast), 13f, color = dim), lp(top = 6))
            val r = hbox()
            r.addView(btn("Zrób kopię teraz", true, 14f) { runBg("Kopia na GitHub") { "Zapisano w " + Sync.githubBackup() } }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            r.addView(btn("Przywróć", size = 14f) { runBg("Przywracanie z GitHuba") { "Przywrócono notatek: " + Sync.githubRestore() } })
            p.addView(r, lp(top = 8))
        }

        section(col, "Kopia na Dysku Google")
        p = panel(col)
        if (Store.driveUri.isEmpty()) {
            p.addView(tv("Wybierz miejsce na kopię: w oknie zapisu stuknij ☰ i wybierz Dysk Google. Notatnik będzie ten plik sam aktualizował (razem z nagraniami i skanami).", 13f, color = dim), lp(top = 10))
            p.addView(primary("Wybierz plik na Dysku Google") {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "notatnik-kopia.zip")
                try { startActivityForResult(i, REQ_DRIVE) } catch (e: Exception) { toast("Nie mogę otworzyć zapisu pliku") }
            }, lp(top = 10))
        } else {
            seg(p, "Automatycznie po zmianach", listOf("Tak" to Store.driveAuto, "Nie" to !Store.driveAuto)) { Store.driveAuto = it == 0; Store.save(); render() }
            p.addView(tv("Ostatnia kopia: " + ago(Store.driveLast), 13f, color = dim), lp(top = 6))
            val r = hbox()
            r.addView(btn("Zapisz teraz", true, 14f) { runBg("Kopia na Dysk Google") { Sync.driveBackup(this); "Zapisano na Dysku Google" } }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            r.addView(btn("Przywróć", size = 14f) { runBg("Przywracanie z Dysku") { "Przywrócono notatek: " + Sync.driveRestore(this) } }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            r.addView(btn("Zmień plik", size = 14f) { Store.driveUri = ""; Store.save(); render() })
            p.addView(HorizontalScrollView(this).apply { addView(r) }, lp(top = 8))
        }

        section(col, "Kopia w pliku")
        p = panel(col)
        val zr = hbox()
        zr.addView(btn("Zapisz ZIP", size = 14f) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "notatnik-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip")
            try { startActivityForResult(i, REQ_EXPORT) } catch (e: Exception) { toast("Nie mogę zapisać pliku") }
        }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        zr.addView(btn("Wczytaj ZIP", size = 14f) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT) } catch (e: Exception) { toast("Nie mogę otworzyć pliku") }
        })
        p.addView(zr, lp(top = 10))
        p.addView(tv("Dodatkowo Android sam robi kopię danych aplikacji na Dysk Google (kopia zapasowa telefonu).", 12f, color = dim), lp(top = 8))

        section(col, "Aktualizacje")
        p = panel(col)
        item(p, tv("Zainstalowana wersja", 15f), tv(versionName(), 15f, true))
        val st = tv(updMsg, 14f, color = dim)
        updStatus = st
        if (updMsg.isNotEmpty()) p.addView(st, lp(top = 4))
        val upd = update
        if (upd != null) p.addView(btn("Pobierz i zainstaluj 1.${upd.number}", true, 14f) { installUpdate() }, lp(top = 8))
        else p.addView(btn("Sprawdź aktualizacje", size = 14f) { checkUpdate(true) }, lp(top = 8))
        p.addView(btn("Instrukcja: jak zaktualizować", size = 14f) { go("help") }, lp(top = 8))
        col.addView(tv("Notatnik ${versionName()} · ${Store.notes.size} wpisów", 12f, color = dim).apply { gravity = Gravity.CENTER }, lp(top = 18))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Nie mogę otworzyć przeglądarki")
        }
    }

    // ------------------------------------------------------------ instrukcja aktualizacji
    private val UPDATE_CMD =
        "cd ~ && unzip -o \"\$(ls -t ~/storage/downloads/Notatnik*.zip | head -1)\" && cd Notatnik && git add -A && " +
            "git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push"

    private fun code(col: LinearLayout, cmd: String) {
        col.addView(tv(cmd, 13f).apply {
            typeface = Typeface.MONOSPACE
            background = if (Store.eink) round(bg, 8, 1, fg) else round(surface, 12, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
        }, lp(top = 8))
        col.addView(btn("Kopiuj polecenie", size = 14f) { copy(cmd, "Skopiowano – wklej w Termuksie") }, lp(WRAP, WRAP, top = 6))
    }

    private fun step(col: LinearLayout, n: Int, text: String) {
        val r = hbox().apply { gravity = Gravity.TOP }
        r.addView(tv("$n.", 15f, true), LinearLayout.LayoutParams(dp(26), WRAP))
        r.addView(tv(text, 15f), lp(0, WRAP, 1f))
        col.addView(r, lp(top = 10))
    }

    private fun renderHelp() {
        val col = page()
        val top = hbox()
        top.addView(roundIcon(R.drawable.ic_back_arrow) { go("settings") }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = 0 })
        col.addView(top)
        col.addView(tv("Jak zaktualizować Notatnik", 26f, true), lp(top = 10))
        step(col, 1, "Pobierz Notatnik.zip z rozmowy z Claude do folderu Pobrane. Nie otwieraj go.")
        step(col, 2, "Otwórz Termux. Jeśli na początku linii nie ma znaku \$, stuknij CTRL, a potem c.")
        col.addView(btn("Otwórz Termux", size = 14f) {
            val i = packageManager.getLaunchIntentForPackage("com.termux")
            if (i != null) startActivity(i) else toast("Termux nie jest zainstalowany")
        }, lp(WRAP, WRAP, top = 8))
        step(col, 3, "Skopiuj polecenie, wklej w Termuksie i stuknij Enter:")
        code(col, UPDATE_CMD)
        step(col, 4, "Po „main -> main” GitHub buduje aplikację ok. 3–5 minut.")
        col.addView(btn("Otwórz budowanie na GitHubie", size = 14f) { openUrl("https://github.com/${Updater.REPO}/actions") }, lp(WRAP, WRAP, top = 8))
        step(col, 5, "Przy zielonym ptaszku zamknij i otwórz Notatnik – pojawi się „Nowa wersja”. Przy czerwonym krzyżyku wyślij Claude zrzut linijek z „e:”.")
    }

    // ------------------------------------------------------------ aktualizacje
    private fun installedVersion(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun checkUpdate(manual: Boolean) {
        val token = Store.ghToken
        if (token.isBlank()) {
            if (manual) toast("Najpierw wklej token GitHub")
            return
        }
        if (manual) {
            updMsg = "Sprawdzam…"
            render()
        }
        Thread {
            val res: Pair<Updater.Info?, String> = try {
                val i = Updater.check(token)
                if (i != null && i.number > installedVersion()) Pair(i, "Dostępna wersja 1.${i.number}") else Pair(null, "Masz najnowszą wersję")
            } catch (e: Exception) {
                Pair(null, "Błąd: " + (e.message ?: "brak połączenia"))
            }
            h.post {
                update = res.first
                if (manual || res.first != null) {
                    updMsg = res.second
                    if (screen != "edit") render()
                }
            }
        }.start()
    }

    private fun installUpdate() {
        val info = update ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Zezwól Notatnikowi na instalowanie aplikacji, potem wróć i stuknij ponownie")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
            }
            return
        }
        updMsg = "Pobieram… 0%"
        render()
        val file = File(cacheDir, "update.apk")
        Thread {
            try {
                Updater.download(Store.ghToken, info, file) { pr -> h.post { updMsg = "Pobieram… $pr%"; updStatus?.text = updMsg } }
                installApk(file)
                h.post { updMsg = "Potwierdź instalację"; updStatus?.text = updMsg }
            } catch (e: Exception) {
                h.post { updMsg = "Błąd: " + (e.message ?: "pobieranie nieudane"); render() }
            }
        }.start()
    }

    private fun installApk(file: File) {
        val pi = packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = pi.openSession(id)
        try {
            session.openWrite("notatnik", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val i = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL)
            val pend = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pend.intentSender)
        } finally {
            session.close()
        }
    }

    private fun handleInstall(i: Intent) {
        if (i.action != ACTION_INSTALL) return
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> toast("Zaktualizowano")
            -999 -> {}
            else -> {
                updMsg = "Instalacja nieudana: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "nieznany błąd")
                render()
            }
        }
    }

    // ------------------------------------------------------------ uprawnienia i wyniki
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val f = afterMic
            afterMic = null
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f?.invoke()
            else toast("Bez dostępu do mikrofonu nie da się dyktować ani nagrywać")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_AUDIO -> {
                val uris = ArrayList<Uri>()
                data?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                var ok = 0
                for (u in uris) if (ImportAudioActivity.importAudio(this, u) != null) ok++
                toast(if (ok > 0) "Dodano do Nagrań: $ok" else "Nie udało się zaimportować")
                area = "recordings"; render()
            }
            REQ_SCAN -> {
                val res = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return
                val pdf = res.pdf ?: return
                processScan(pdf.uri, pdf.pageCount, res.pages?.map { it.imageUri } ?: emptyList())
            }
            REQ_PHOTO_CAM -> {
                val n = Store.note(photoFor) ?: return
                val f = camFile ?: return
                if (f.exists() && f.length() > 0) attachPhotos(n, emptyList(), f)
            }
            REQ_PHOTO_PICK -> {
                val n = Store.note(photoFor) ?: return
                val uris = ArrayList<Uri>()
                data?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) attachPhotos(n, uris, null)
            }
            REQ_CARD -> {
                val res = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return
                val img = res.pages?.firstOrNull()?.imageUri ?: return
                processCard(img)
            }
            REQ_PDF -> {
                val uris = ArrayList<Uri>()
                data?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                if (uris.isEmpty()) return
                val (d, label) = progress("Import PDF")
                io.execute {
                    val n = try {
                        Scans.importFiles(this, uris.map { Pair(it, Scans.displayName(this, it)) }) { m -> h.post { label.text = m } }
                    } catch (e: Exception) {
                        -1
                    }
                    h.post { d.dismiss(); toast(if (n >= 0) "Zaimportowano dokumentów: $n" else "Nie udało się wczytać plików"); area = "scans"; render() }
                }
            }
            REQ_INVITE -> {
                val uri = data?.data ?: return
                handleFile(uri)
            }
            REQ_KEEP -> {
                val uri = data?.data ?: return
                runBg("Import z Google Keep") { "Zaimportowano notatek: " + Sync.importKeep(this, uri) }
            }
            REQ_EXPORT -> {
                val uri = data?.data ?: return
                runBg("Zapisuję kopię") {
                    contentResolver.openOutputStream(uri, "wt")?.use { Sync.zip(it, true) } ?: throw java.io.IOException("Brak dostępu")
                    "Kopia zapisana"
                }
            }
            REQ_IMPORT -> {
                val uri = data?.data ?: return
                runBg("Wczytuję kopię") { "Dodano lub zaktualizowano: " + Sync.restoreZip(this, uri) }
            }
            REQ_DRIVE -> {
                val uri = data?.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) {
                }
                Store.driveUri = uri.toString()
                Store.save()
                runBg("Pierwsza kopia na Dysk") { Sync.driveBackup(this); "Kopia na Dysku Google gotowa" }
            }
            REQ_SCANS_DIR -> {
                val uri = data?.data ?: return
                val (d, label) = progress("Import skanów")
                io.execute {
                    val n = try {
                        Scans.importFolder(this, uri) { m -> h.post { label.text = m } }
                    } catch (e: Exception) {
                        -1
                    }
                    h.post { d.dismiss(); toast(if (n >= 0) "Zaimportowano skanów: $n" else "Nie udało się odczytać folderu"); render() }
                }
            }
        }
    }

    // ------------------------------------------------------------ nagrania spotkań
    private fun openMeeting() {
        startActivity(Intent(this, MeetingActivity::class.java))
    }

    /** Ikonka „Spotkanie” na pulpicie (skrót do okienka nagrywania). */
    private fun pinMeetingShortcut() {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Dodaj widżet „Spotkanie”: przytrzymaj pulpit → Widżety → Notatnik"); return
        }
        val info = android.content.pm.ShortcutInfo.Builder(this, "meeting")
            .setShortLabel("Spotkanie")
            .setLongLabel("Nagraj spotkanie")
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MeetingActivity::class.java).setAction("pl.notatnik.MEETING"))
            .build()
        sm.requestPinShortcut(info, null)
        toast("Potwierdź dodanie skrótu na pulpicie. Możesz też dodać widżet „Spotkanie”.")
    }

    /** Informacje o spotkaniu w notatce: data, adresaci, znaczniki ★, status przetwarzania. */
    private fun meetingInfo(col: LinearLayout, n: Note) {
        val m = MeetMeta.of(n)
        val p = panel(col)
        val df = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("pl"))
        p.addView(tv("📅 " + df.format(Date(if (m.startedAt > 0) m.startedAt else n.created)) + " · " + hms(n.audioMs), 14f, true), lp(top = 2))
        p.addView(tv(MeetCfg.templateName(m.template) + " · " + MeetCfg.langName(m.lang) + (if (m.offlineOnly) " · tylko offline" else "") +
            (if (m.source == "import") " · zaimportowane" else ""), 12f, color = dim), lp(top = 2))
        if (m.calendar.isNotBlank()) p.addView(tv("Z kalendarza: " + m.calendar, 12f, color = dim), lp(top = 2))
        if (m.sendEmail && m.recipients.isNotEmpty()) p.addView(tv("✉ " + (if (m.emailed) "Wysłano do: " else "Podsumowanie pójdzie do: ") +
            m.recipients.joinToString(", "), 12f, color = dim), lp(top = 4))
        val busy = m.status == "processing" && (ProcessService.busyId == n.id || m.stage.isNotBlank())
        p.addView(tv(when {
            m.status == "done" -> "✓ Przetworzone" + if (m.summaryBy == "offline") " (podsumowanie offline)" else " (podsumowanie Groq)"
            busy -> "⏳ " + m.stage.ifBlank { "Przetwarzanie" } + " – " + m.progress + "%"
            m.status == "error" -> "⚠ Błąd przetwarzania: " + m.error
            else -> "Czeka na przetworzenie"
        }, 13f, true, if (m.status == "error") 0xFFDC2626.toInt() else accent), lp(top = 8))
        if (m.status == "done" && m.error.isNotBlank()) p.addView(tv("⚠ " + m.error, 12f, false, 0xFFD97706.toInt()).apply { setTextIsSelectable(true) }, lp(top = 4))
        if (m.status == "error") p.addView(tv("Zaznacz i skopiuj komunikat, jeśli chcesz go przesłać.", 11f, color = dim), lp(top = 2))
        val acts = hbox()
        if (!busy) acts.addView(btn(if (m.status == "done") "Przetwórz ponownie" else "Przetwórz teraz", m.status != "done", 13f) {
            if (!Models.ready(this, MeetCfg.model)) toast("Pobieram modele rozpoznawania mowy (ok. ${Models.totalMb(MeetCfg.model)} MB) – najlepiej na Wi-Fi")
            val mm = MeetMeta.of(n); mm.status = "pending"; mm.emailed = false; mm.stage = "W kolejce"; mm.progress = 0
            n.meta = mm.toJson(); Store.touch(n); Store.save()
            ProcessService.start(this, n.id); render()
        }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        if (m.status == "done" && n.body.isNotBlank()) acts.addView(btn("✉ Wyślij e-mail", size = 13f) { emailSummary(n) }, lp(WRAP, WRAP))
        if (acts.childCount > 0) p.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(acts) }, lp(top = 8))
        if (m.marks.isNotEmpty()) {
            p.addView(tv("★ Ważne momenty – stuknij, żeby odsłuchać", 13f, true), lp(top = 10))
            for (t in m.marks) p.addView(btn("★ " + MeetingService.stamp(t), size = 13f) { playFrom(n, t) }, lp(WRAP, WRAP, top = 4))
        }
    }

    private fun playFrom(n: Note, ms: Long) {
        try {
            val mp = player ?: MediaPlayer().apply { setDataSource(n.audio); prepare() }.also { player = it }
            mp.seekTo((ms - 3000).coerceAtLeast(0).toInt())
            mp.start()
        } catch (e: Exception) {
            toast("Nie mogę odtworzyć nagrania")
        }
    }

    /** Ustawienia nagrań: e-mail, poczta SMTP, Groq, słownik, przechowywanie. */
    private fun meetingSettings() {
        MeetCfg.load(this)
        sheet("Ustawienia nagrań") { col, d ->
            col.addView(tv("Domyślny e-mail na podsumowania", 15f, true), lp(top = 4))
            val email = edit(MeetCfg.email, "np. bartosz@firma.pl", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            col.addView(email, lp(top = 6))

            col.addView(tv("Poczta wychodząca (SMTP)", 15f, true), lp(top = 16))
            col.addView(tv("Podsumowania wysyłają się same z Twojej skrzynki. Dla Gmaila użyj „hasła do aplikacji” (Konto Google → Bezpieczeństwo).", 12f, color = dim), lp(top = 2))
            val host = edit(MeetCfg.smtpHost, "Serwer, np. smtp.gmail.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val port = edit(MeetCfg.smtpPort.toString(), "Port", InputType.TYPE_CLASS_NUMBER)
            val user = edit(MeetCfg.smtpUser, "Login (adres e-mail)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val pass = edit(MeetCfg.smtpPass, "Hasło / hasło do aplikacji", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            var tls = MeetCfg.smtpTls
            val presets = hbox()
            fun preset(name: String, h: String, p: Int, t: String) = presets.addView(btn(name, size = 12f) {
                host.setText(h); port.setText(p.toString()); tls = t; toast("Wpisano ustawienia: $name")
            }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
            preset("Gmail", "smtp.gmail.com", 465, "ssl")
            preset("Outlook", "smtp.office365.com", 587, "starttls")
            preset("Onet", "smtp.poczta.onet.pl", 465, "ssl")
            preset("WP", "smtp.wp.pl", 465, "ssl")
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) }, lp(top = 8))
            col.addView(host, lp(top = 6)); col.addView(port, lp(top = 6)); col.addView(user, lp(top = 6)); col.addView(pass, lp(top = 6))
            fun store() {
                MeetCfg.email = email.text.toString().trim()
                MeetCfg.smtpHost = host.text.toString().trim()
                MeetCfg.smtpPort = port.text.toString().trim().toIntOrNull() ?: 465
                MeetCfg.smtpUser = user.text.toString().trim()
                MeetCfg.smtpPass = pass.text.toString()
                MeetCfg.smtpTls = if (MeetCfg.smtpPort == 587) "starttls" else tls
            }
            col.addView(btn("Wyślij e-mail testowy", size = 13f) {
                store(); MeetCfg.save(this)
                toast("Wysyłam…")
                Thread {
                    val err = try {
                        Mailer.send(listOf(MeetCfg.email.ifBlank { MeetCfg.smtpUser }), "Notatnik – test poczty",
                            "To jest wiadomość testowa z Notatnika. Podsumowania spotkań będą przychodzić w ten sposób.")
                        null
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    h.post { toast(if (err == null) "Wysłano – sprawdź skrzynkę" else "Błąd: " + err.take(120)) }
                }.start()
            }, lp(WRAP, WRAP, top = 8))

            col.addView(tv("Klucz Groq (podsumowania online)", 15f, true), lp(top = 16))
            col.addView(tv("Zostaw puste – używany jest klucz wbudowany. Własny klucz: console.groq.com → API Keys. Gdy limit się skończy albo nie ma internetu, podsumowanie zrobi się offline.", 12f, color = dim), lp(top = 2))
            val groq = edit(MeetCfg.groqKey, "Klucz wbudowany (wklej własny, żeby podmienić)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            col.addView(groq, lp(top = 6))

            col.addView(tv("Słownik nazw", 15f, true), lp(top = 16))
            col.addView(tv("Nazwy firm, produktów i nazwiska, które mają być zapisane poprawnie (oddziel przecinkami).", 12f, color = dim), lp(top = 2))
            val dict = edit(MeetCfg.dict, "np. Logi-Pack, Wedel, TimeGate, Krzysztof Bieniek",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 2 }
            col.addView(dict, lp(top = 6))

            var keep = MeetCfg.keepDays
            var charging = MeetCfg.onlyCharging
            var model = MeetCfg.model
            val opts = vbox()
            fun drawOpts() {
                opts.removeAllViews()
                seg(opts, "Rozpoznawanie mowy (Whisper, w telefonie)", listOf("Szybszy (~380 MB)" to (model == "small"), "Dokładniejszy (~1 GB)" to (model == "medium"))) {
                    model = if (it == 0) "small" else "medium"; drawOpts()
                }
                opts.addView(tv(if (Models.ready(this, model)) "✓ Modele pobrane" else "Modele pobiorą się przy pierwszym przetwarzaniu (najlepiej na Wi-Fi).", 12f, color = dim), lp(top = 4))
                seg(opts, "Usuwaj dźwięk po przetworzeniu (tekst zostaje)", listOf("Nigdy" to (keep == 0), "Po 30 dniach" to (keep == 30), "Po 90 dniach" to (keep == 90))) {
                    keep = listOf(0, 30, 90)[it]; drawOpts()
                }
                seg(opts, "Przetwarzaj długie nagrania", listOf("Od razu" to !charging, "Tylko przy ładowaniu" to charging)) {
                    charging = it == 1; drawOpts()
                }
            }
            drawOpts()
            col.addView(opts)
            val dl = tv("", 12f, color = dim)
            col.addView(btn("Pobierz modele teraz", size = 13f) {
                MeetCfg.model = model; MeetCfg.save(this)
                dl.text = "Pobieram…"
                Thread {
                    val err = try {
                        Models.ensure(this, model) { p, t -> h.post { dl.text = "$t – $p%" } }; null
                    } catch (e: Exception) { e.message }
                    h.post { dl.text = err?.let { "Błąd: $it" } ?: "✓ Modele pobrane" }
                }.start()
            }, lp(WRAP, WRAP, top = 8))
            col.addView(dl, lp(top = 4))

            col.addView(tv("Zapamiętane głosy", 15f, true), lp(top = 16))
            val voices = Voices.all(this).keys.toList()
            if (voices.isEmpty()) col.addView(tv("Brak. Po przetworzeniu nagrania stuknij osobę w transkrypcji, wpisz imię i zaznacz „Zapamiętaj głos”.", 12f, color = dim), lp(top = 2))
            for (v in voices) {
                val r = hbox()
                r.addView(tv("👤 $v", 15f), lp(0, WRAP, 1f))
                r.addView(btn("Usuń", size = 12f) { Voices.remove(this, v); d.dismiss(); meetingSettings() })
                col.addView(r, lp(top = 6))
            }

            col.addView(primary("Zapisz") {
                store()
                MeetCfg.groqKey = groq.text.toString().trim()
                MeetCfg.dict = dict.text.toString().trim()
                MeetCfg.keepDays = keep
                MeetCfg.onlyCharging = charging
                MeetCfg.model = model
                MeetCfg.save(this)
                d.dismiss()
                toast("Zapisano ustawienia nagrań")
            }, lp(top = 18))
        }
    }

    private var showTranscript = false

    /** Pod podsumowaniem: zadania (z przypomnieniami) i klikalna transkrypcja z mówcami. */
    private fun meetingTail(col: LinearLayout, n: Note) {
        val m = MeetMeta.of(n)
        if (n.items.isNotEmpty()) {
            section(col, "Zadania", 16)
            val p = panel(col)
            for (it in n.items.filter { !it.del }) {
                val r = hbox()
                r.addView(tv((if (it.done) "☑ " else "☐ ") + it.text, 15f, false, if (it.done) dim else fg).apply {
                    setOnClickListener { _ -> it.done = !it.done; it.upd = System.currentTimeMillis(); Store.touch(n); render() }
                }, lp(0, WRAP, 1f))
                r.addView(btn("⏰", size = 13f) { taskReminder(n, it) })
                p.addView(r, lp(top = 6))
            }
            p.addView(tv("⏰ tworzy przypomnienie z zadania.", 11f, color = dim), lp(top = 6))
        }
        if (m.segs.length() == 0) return
        section(col, "Transkrypcja", 16)
        col.addView(btn(if (showTranscript) "Ukryj transkrypcję" else "Pokaż transkrypcję (${m.segs.length()} wypowiedzi)", size = 13f) {
            showTranscript = !showTranscript; render()
        }, lp(WRAP, WRAP, top = 4))
        if (!showTranscript) return
        // mówcy – stuknij, żeby nadać imię i zapamiętać głos
        val ks = (0 until m.segs.length()).map { m.segs.getJSONObject(it).optInt("k") }.distinct()
        val sp = hbox()
        for (k in ks) sp.addView(btn("👤 " + m.speaker(k), size = 12f) { speakerSheet(n, k) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sp) }, lp(top = 8))
        col.addView(tv("Stuknij osobę, żeby wpisać imię i zapamiętać głos. Stuknij zdanie, żeby odsłuchać od tego miejsca.", 11f, color = dim), lp(top = 4))
        val p = panel(col)
        for (i in 0 until m.segs.length()) {
            val o = m.segs.getJSONObject(i)
            val start = o.optLong("s")
            val line = tv("", 14f).apply {
                text = android.text.SpannableStringBuilder().apply {
                    append(MeetingService.stamp(start) + "  ")
                    setSpan(android.text.style.ForegroundColorSpan(accent), 0, length, 0)
                    val st = length
                    append(m.speaker(o.optInt("k")) + ": ")
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, length, 0)
                    append(o.optString("t"))
                }
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { playFrom(n, start + 3000) }
            }
            p.addView(line)
        }
    }

    private fun taskReminder(src: Note, it: Item) {
        val t = Note(type = "text", title = it.text.take(90), body = "Zadanie ze spotkania: " + src.title)
        Store.notes.add(t)
        Store.touch(t)
        Store.save()
        toast("Utworzono notatkę z zadaniem – ustaw termin")
        reminderSheet(t)
    }

    /** Nadanie imienia mówcy (w całej notatce) i zapamiętanie jego głosu na przyszłość. */
    private fun speakerSheet(n: Note, k: Int) {
        val m = MeetMeta.of(n)
        val old = m.speaker(k)
        val count = (0 until m.segs.length()).count { m.segs.getJSONObject(it).optInt("k") == k }
        sheet("Kto to jest?") { col, d ->
            col.addView(tv("$old · wypowiedzi: $count", 13f, color = dim), lp(top = 2))
            val e = edit(if (old.startsWith("Osoba ")) "" else old, "Imię i nazwisko")
            col.addView(e, lp(top = 8))
            val known = Voices.all(this).keys
            if (known.isNotEmpty()) {
                val r = hbox()
                known.forEach { name -> r.addView(btn(name, size = 12f) { e.setText(name) }, lp(WRAP, WRAP).apply { rightMargin = dp(6) }) }
                col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 8))
            }
            var remember = true
            val box = vbox()
            fun draw() {
                box.removeAllViews()
                seg(box, "Zapamiętaj głos (następnym razem rozpozna tę osobę sam)", listOf("Tak" to remember, "Nie" to !remember)) { remember = it == 0; draw() }
            }
            draw()
            col.addView(box)
            col.addView(primary("Zapisz") {
                val name = e.text.toString().trim()
                if (name.isEmpty()) { toast("Wpisz imię"); return@primary }
                m.speakers.put(k.toString(), name)
                val rx = Regex("\\b" + Regex.escape(old) + "\\b")
                n.body = n.body.replace(rx, name)
                n.items.forEach { it.text = it.text.replace(rx, name) }
                if (remember) m.cents.optJSONArray(k.toString())?.let { a ->
                    Voices.remember(this, name, FloatArray(a.length()) { a.optDouble(it).toFloat() })
                }
                n.meta = m.toJson()
                Store.touch(n); Store.save()
                d.dismiss(); render()
                toast(if (remember) "Zapisano – głos $name zapamiętany" else "Zapisano")
            }, lp(top = 14))
        }
    }

    private fun emailSummary(n: Note) {
        val m = MeetMeta.of(n)
        val to = if (m.recipients.isNotEmpty()) m.recipients else listOf(MeetCfg.email).filter { it.isNotBlank() }
        if (to.isEmpty()) { toast("Brak adresata – ustaw domyślny e-mail w ustawieniach Nagrań"); return }
        if (!MeetCfg.mailReady) { toast("Skonfiguruj pocztę: Nagrania → Ustawienia"); return }
        toast("Wysyłam do: " + to.joinToString(", "))
        Thread {
            val err = try {
                Mailer.send(to, (if (m.lang == "en") "Meeting summary: " else "Podsumowanie: ") + n.title, n.body)
                null
            } catch (e: Exception) {
                e.message ?: "błąd"
            }
            h.post {
                if (err == null) { m.emailed = true; n.meta = m.toJson(); Store.touch(n); Store.save(); toast("Wysłano"); render() }
                else toast("Błąd wysyłki: " + err.take(120))
            }
        }.start()
    }
}
