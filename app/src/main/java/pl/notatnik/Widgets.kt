package pl.notatnik

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Kolory widżetu: tło, tekst, przygaszony tekst. */
private fun widgetColors(ctx: Context, c: WidgetCfg): Triple<Int, Int, Int> = when (c.bg) {
    "light" -> Triple(Color.WHITE, 0xFF111827.toInt(), 0xFF6B7280.toInt())
    "theme" -> {
        Theme.apply(ctx, Store.theme, Store.mode)
        Triple(Theme.blend(Theme.accent, Color.BLACK, 0.25f), Color.WHITE, 0xCCFFFFFF.toInt())
    }
    else -> Triple(Color.BLACK, Color.WHITE, 0xB3FFFFFF.toInt())
}

/** Widżet z notatkami: zakładki, lista do odhaczania, dodawanie. */
class NotesWidget : AppWidgetProvider() {
    companion object {
        const val ACT_TAB = "pl.notatnik.W_TAB"
        const val ACT_ITEM = "pl.notatnik.W_ITEM"
        private val TABS = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)

        const val TODAY = "@today"

        fun usable(n: Note?) = n != null && n.deleted == 0L && !n.archived && !n.template

        /** Zakładki widżetu: id notatek albo „@today” (przypomnienia na dziś i jutro). */
        fun tabs(c: WidgetCfg): List<String> = c.notes.filter { it == TODAY || usable(Store.note(it)) }

        fun tabTitle(id: String): String {
            if (id == TODAY) return "Dziś"
            val n = Store.note(id) ?: return "Notatka"
            return n.title.ifEmpty { if (n.isList) "Lista" else "Notatka" }
        }

        fun cfg(id: Int): WidgetCfg = Store.widgets.getOrPut(id) {
            WidgetCfg(notes = Store.notes.filter { usable(it) && !it.locked && (it.isList || it.type == "text") }
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
                .take(4).map { it.id }.toMutableList())
        }

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotesWidget::class.java))
                for (id in ids) update(ctx, mgr, id)
                if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.wlist)
                QuickWidget.updateAll(ctx)
            } catch (e: Exception) {
            }
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            Store.init(ctx)
            val c = cfg(id)
            c.notes.removeAll { it != TODAY && Store.note(it) == null }
            val tabs = tabs(c)
            if (c.current >= tabs.size) c.current = 0
            val curId = tabs.getOrNull(c.current)
            val cur = if (curId == null || curId == TODAY) null else Store.note(curId)
            val (bg, fg, dim) = widgetColors(ctx, c)
            val v = RemoteViews(ctx.packageName, R.layout.widget_notes)
            v.setInt(R.id.wbg, "setColorFilter", bg)
            v.setInt(R.id.wbg, "setImageAlpha", c.alpha * 255 / 100)
            v.setViewVisibility(R.id.whead, if (c.title) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.wtitle, if (curId != null) tabTitle(curId) else "Notatki")
            v.setTextColor(R.id.wtitle, fg)
            v.setInt(R.id.wadd, "setColorFilter", fg)
            v.setInt(R.id.wset, "setColorFilter", fg)
            v.setTextColor(R.id.wempty, dim)
            val showTabs = c.tabs && tabs.size > 1
            v.setViewVisibility(R.id.wtabs, if (showTabs) View.VISIBLE else View.GONE)
            TABS.forEachIndexed { k, tid ->
                val tabId = tabs.getOrNull(k)
                if (tabId == null || !showTabs) {
                    v.setViewVisibility(tid, View.GONE)
                } else {
                    val sel = k == c.current
                    v.setViewVisibility(tid, View.VISIBLE)
                    v.setTextViewText(tid, tabTitle(tabId))
                    val light = c.bg == "light"
                    v.setInt(tid, "setBackgroundResource", when {
                        sel && light -> R.drawable.tab_sel_dark
                        sel -> R.drawable.tab_sel
                        light -> R.drawable.tab_bg_dark
                        else -> R.drawable.tab_bg
                    })
                    v.setTextColor(tid, if (sel) (if (light) Color.WHITE else Color.BLACK) else fg)
                    v.setOnClickPendingIntent(
                        tid, PendingIntent.getBroadcast(
                            ctx, id * 10 + k, Intent(ctx, NotesWidget::class.java).setAction(ACT_TAB)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("tab", k),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }
            val svc = Intent(ctx, NotesWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("notatnik://widget/$id"))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.wlist, svc)
            v.setEmptyView(R.id.wlist, R.id.wempty)
            v.setPendingIntentTemplate(
                R.id.wlist, PendingIntent.getBroadcast(
                    ctx, 50_000 + id, Intent(ctx, NotesWidget::class.java).setAction(ACT_ITEM)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            v.setOnClickPendingIntent(
                R.id.wadd, PendingIntent.getActivity(
                    ctx, 60_000 + id, Intent(ctx, QuickAddActivity::class.java).putExtra("note", cur?.id)
                        .setData(Uri.parse("notatnik://add/$id")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            v.setOnClickPendingIntent(
                R.id.wtitle, PendingIntent.getActivity(
                    ctx, 70_000 + id, Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("note", cur?.id)
                        .setData(Uri.parse("notatnik://open/$id")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            v.setOnClickPendingIntent(
                R.id.wset, PendingIntent.getActivity(
                    ctx, 80_000 + id, Intent(ctx, WidgetConfigActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("notatnik://cfg/$id")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            mgr.updateAppWidget(id, v)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.wlist)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        Store.init(ctx)
        for (id in ids) Store.widgets.remove(id)
        Store.save()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        Store.init(ctx)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val mgr = AppWidgetManager.getInstance(ctx)
        when (intent.action) {
            ACT_TAB -> {
                cfg(id).current = intent.getIntExtra("tab", 0)
                Store.save()
                update(ctx, mgr, id)
                mgr.notifyAppWidgetViewDataChanged(id, R.id.wlist)
            }
            ACT_ITEM -> {
                val n = Store.note(intent.getStringExtra("note")) ?: return
                val idx = intent.getIntExtra("idx", -1)
                if (n.isList && idx in n.items.indices && !n.locked) {
                    n.items[idx].done = !n.items[idx].done
                    n.items[idx].touch()
                    Store.touch(n)
                    Store.save()
                    updateAll(ctx)
                    if (n.shared) {
                        val pending = goAsync()
                        Shared.syncAsync(ctx) { pending.finish() }
                    }
                } else {
                    val open = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("note", n.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(open)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }
}

class NotesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Row(val text: String, val done: Boolean, val idx: Int, val check: Boolean, val note: String = "")

    private class Factory(val ctx: Context, val id: Int) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()
        private var noteId: String = ""
        private var cfg = WidgetCfg()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            Store.init(ctx)
            cfg = NotesWidget.cfg(id)
            val tabs = NotesWidget.tabs(cfg)
            val curId = tabs.getOrNull(cfg.current) ?: tabs.firstOrNull()
            if (curId == NotesWidget.TODAY) {
                noteId = ""
                rows = today()
                return
            }
            val n = Store.note(curId)
            noteId = n?.id ?: ""
            rows = when {
                n == null -> emptyList()
                n.locked -> listOf(Row("🔒 Notatka zablokowana – otwórz w aplikacji", false, -1, false))
                n.isList -> n.items.withIndex().filter { !it.value.done && !it.value.del }.map { Row(it.value.text, false, it.index, true) } +
                    n.items.withIndex().filter { it.value.done && !it.value.del }.map { Row(it.value.text, true, it.index, true) }
                n.type == "scan" -> listOf(Row("PDF · ${n.pages} str.", false, -1, false)) +
                    n.ocr.lines().filter { it.isNotBlank() }.take(20).map { Row(it, false, -1, false) }
                n.type == "audio" -> listOf(Row("Nagranie " + (n.audioMs / 60000) + ":" + "%02d".format(n.audioMs / 1000 % 60), false, -1, false)) +
                    n.body.lines().filter { it.isNotBlank() }.map { Row(it, false, -1, false) }
                else -> n.body.lines().filter { it.isNotBlank() }.map { Row(it, false, -1, false) }
            }
        }

        /** Przypomnienia na dziś i jutro (z powtarzanymi). */
        private fun today(): List<Row> {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            val end = cal.timeInMillis
            val ev = ArrayList<Pair<Long, Note>>()
            for (n in Store.notes) {
                if (n.reminder <= 0 || n.deleted > 0 || n.archived) continue
                var t = n.reminder
                var k = 0
                while (t in now..end && k < 30) {
                    ev.add(Pair(t, n)); k++
                    if (n.repeat.isEmpty()) break
                    t = Reminders.next(n, t)
                }
            }
            if (ev.isEmpty()) return listOf(Row("Brak przypomnień na dziś i jutro", false, -1, false))
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl"))
            val todayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val dk = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            return ev.sortedBy { it.first }.map { (t, n) ->
                val day = if (dk.format(java.util.Date(t)) == todayKey) "" else "jutro "
                Row(day + df.format(java.util.Date(t)) + "  " + n.title.ifEmpty { "Notatka" }, false, -1, false, n.id)
            }
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val r = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_row)
            val (_, fg, dim) = widgetColors(ctx, cfg)
            val v = RemoteViews(ctx.packageName, R.layout.widget_row)
            v.setViewVisibility(R.id.rcheck, if (r.check) View.VISIBLE else View.GONE)
            v.setImageViewResource(R.id.rcheck, if (r.done) R.drawable.ic_box_on else R.drawable.ic_box)
            v.setInt(R.id.rcheck, "setColorFilter", if (r.done) dim else fg)
            v.setTextViewText(R.id.rtext, r.text)
            v.setTextColor(R.id.rtext, if (r.done) dim else fg)
            v.setTextViewTextSize(R.id.rtext, TypedValue.COMPLEX_UNIT_SP, cfg.text.toFloat())
            v.setOnClickFillInIntent(R.id.rroot, Intent().putExtra("note", r.note.ifEmpty { noteId }).putExtra("idx", r.idx))
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}

/** Widżet szybkiego dodawania: Notatka, Lista, Dyktuj, Nagraj, Skanuj. */
class QuickWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, QuickWidget::class.java))
            if (ids.isEmpty()) return
            Store.init(ctx)
            Theme.apply(ctx, Store.theme, Store.mode)
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
            v.setInt(R.id.qbg, "setColorFilter", Theme.blend(Theme.accent, Color.BLACK, 0.2f))
            v.setInt(R.id.qbg, "setImageAlpha", 230)
            val kinds = listOf("text", "list", "dictate", "record", "scan")
            val btns = intArrayOf(R.id.q0, R.id.q1, R.id.q2, R.id.q3, R.id.q4)
            val icons = intArrayOf(R.id.qi0, R.id.qi1, R.id.qi2, R.id.qi3, R.id.qi4)
            kinds.forEachIndexed { k, kind ->
                v.setInt(icons[k], "setColorFilter", Color.WHITE)
                v.setOnClickPendingIntent(
                    btns[k], PendingIntent.getActivity(
                        ctx, 90_000 + k, Intent(ctx, MainActivity::class.java).setAction("pl.notatnik.NEW").putExtra("kind", kind)
                            .setData(Uri.parse("notatnik://new/$kind")),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            mgr.updateAppWidget(ids, v)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)
}

/** Ustawienia widżetu: które notatki jako zakładki, przezroczystość, tło, tekst. */
class WidgetConfigActivity : Activity() {
    private var wid = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var col: LinearLayout
    private lateinit var cfg: WidgetCfg

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        if (wid == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val existing = Store.widgets[wid]
        cfg = if (existing != null) WidgetCfg.fromJson(existing.toJson()) else NotesWidget.cfg(wid).also { Store.widgets.remove(wid) }
        Theme.apply(this, Store.theme, Store.mode)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        val sv = ScrollView(this)
        sv.setBackgroundColor(Theme.bg)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(28)) }
        sv.addView(col)
        sv.fitsSystemWindows = true
        setContentView(sv)
        render()
    }

    private fun tv(t: String, size: Float, bold: Boolean = false, color: Int = Theme.fg) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(t: String, sel: Boolean, onClick: () -> Unit) = tv(t, 14f, true, if (sel) Color.WHITE else Theme.fg).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(if (sel) Theme.accent else Theme.surface); if (!sel) setStroke(1, Theme.line)
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun row(label: String, opts: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
        col.addView(tv(label, 15f), lp(16))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        opts.forEachIndexed { k, (t, s) ->
            r.addView(chip(t, s) { pick(k); render() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8); topMargin = dp(6) })
        }
        col.addView(r)
    }

    private fun render() {
        col.removeAllViews()
        col.addView(tv("Widżet notatek", 26f, true))
        col.addView(tv("Rozmiar zmienisz na ekranie głównym: przytrzymaj widżet i przeciągnij jego krawędzie.", 13f, false, Theme.dim), lp(6))

        // podgląd
        val (bgc, fg, _) = widgetColors(this, cfg)
        val prev = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.accent, Theme.accent2)).apply { cornerRadius = dp(22).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(cfg.alpha * 255 / 100, Color.red(bgc), Color.green(bgc), Color.blue(bgc))) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val first = cfg.notes.mapNotNull { Store.note(it) }.firstOrNull { NotesWidget.usable(it) }
        if (cfg.title) inner.addView(tv(first?.title?.ifEmpty { null } ?: "Notatki", 18f, true, fg))
        val sample = first?.let { n -> if (n.isList) n.items.take(3).map { (if (it.done) "☑ " else "☐ ") + it.text } else n.body.lines().take(3) } ?: listOf("☐ Przykładowa pozycja")
        sample.forEach { inner.addView(tv(it, cfg.text.toFloat(), false, fg), lp(4)) }
        prev.addView(inner)
        col.addView(prev, lp(14))

        col.addView(tv("Zakładki w widżecie (max 5)", 15f, true), lp(20))
        val todaySel = NotesWidget.TODAY in cfg.notes
        col.addView(chip((if (todaySel) "✓  " else "") + "Dziś – przypomnienia na dziś i jutro", todaySel) {
            if (todaySel) cfg.notes.remove(NotesWidget.TODAY) else if (cfg.notes.size < 5) cfg.notes.add(NotesWidget.TODAY)
            render()
        }, lp(6))
        val candidates = Store.notes.filter { NotesWidget.usable(it) && !it.locked && it.type != "scan" }
            .sortedWith(compareByDescending<Note> { it.id in cfg.notes }.thenByDescending { it.pinned }.thenByDescending { it.updated })
        if (candidates.isEmpty()) col.addView(tv("Najpierw dodaj notatkę albo listę w aplikacji.", 14f, false, Theme.dim), lp(6))
        for (n in candidates.take(30)) {
            val sel = n.id in cfg.notes
            col.addView(chip((if (sel) "✓  " else "") + n.title.ifEmpty { if (n.isList) "Lista bez tytułu" else n.body.take(30).ifEmpty { "Notatka" } }, sel) {
                if (sel) cfg.notes.remove(n.id) else if (cfg.notes.size < 5) cfg.notes.add(n.id)
                render()
            }, lp(6))
        }

        col.addView(tv("Przezroczystość tła: ${100 - cfg.alpha}%", 15f, true), lp(20))
        col.addView(SeekBar(this).apply {
            max = 100
            progress = 100 - cfg.alpha
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) cfg.alpha = 100 - p
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) = render()
            })
        }, lp(6))
        row("Tło", listOf("Ciemne" to (cfg.bg == "dark"), "Jasne" to (cfg.bg == "light"), "Kolor motywu" to (cfg.bg == "theme"))) {
            cfg.bg = listOf("dark", "light", "theme")[it]
        }
        row("Wielkość tekstu", listOf("Mały" to (cfg.text == 13), "Średni" to (cfg.text == 15), "Duży" to (cfg.text == 18))) {
            cfg.text = listOf(13, 15, 18)[it]
        }
        row("Zakładki u góry", listOf("Pokaż" to cfg.tabs, "Ukryj" to !cfg.tabs)) { cfg.tabs = it == 0 }
        row("Tytuł i przyciski", listOf("Pokaż" to cfg.title, "Ukryj" to !cfg.title)) { cfg.title = it == 0 }

        col.addView(tv("Zapisz", 16f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Theme.accent) }
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setOnClickListener {
                Store.widgets[wid] = cfg
                Store.save()
                val mgr = AppWidgetManager.getInstance(this@WidgetConfigActivity)
                NotesWidget.update(this@WidgetConfigActivity, mgr, wid)
                mgr.notifyAppWidgetViewDataChanged(wid, R.id.wlist)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
                finish()
            }
        }, lp(24))
    }
}
