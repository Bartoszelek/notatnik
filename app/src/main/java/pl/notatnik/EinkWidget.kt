package pl.notatnik

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * „Notatnik na cały ekran” z launchera e-ink jako widżet: kategorie u góry (np. Zakupy, Pamiętaj) i „+”,
 * lista do odhaczania albo zwykły tekst, na dole „Dodaj” i „Usuń odhaczone”. Tło: papier, biel albo czerń.
 */
class EinkWidget : AppWidgetProvider() {
    companion object {
        const val ACT_TAB = "pl.notatnik.EK_TAB"
        const val ACT_ITEM = "pl.notatnik.EK_ITEM"
        const val ACT_CLEAR = "pl.notatnik.EK_CLEAR"

        private fun dark(c: WidgetCfg) = c.bg == "dark"

        /** Ustawienia widżetu; przy pierwszym dodaniu kategorie „Zakupy” (lista) i „Pamiętaj” (tekst). */
        fun cfg(id: Int): WidgetCfg = Store.widgets.getOrPut(id) {
            fun find(title: String, type: String): Note {
                Store.notes.firstOrNull { it.title.equals(title, true) && it.deleted == 0L && !it.archived }?.let { return it }
                val n = Note(type = type, title = title)
                Store.notes.add(0, n)
                return n
            }
            val ids = mutableListOf(find("Zakupy", "list").id, find("Pamiętaj", "text").id)
            Store.save()
            WidgetCfg(notes = ids, bg = "paper", text = 18, alpha = 100)
        }

        fun tabs(c: WidgetCfg): List<Note> = c.notes.mapNotNull { Store.note(it) }.filter { NotesWidget.usable(it) }

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, EinkWidget::class.java))
                for (id in ids) update(ctx, mgr, id)
                if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.ek_list)
            } catch (e: Exception) {
            }
        }

        private fun pi(ctx: Context, code: Int, i: Intent) =
            PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            Store.init(ctx)
            val c = cfg(id)
            val tabs = tabs(c)
            if (c.current >= tabs.size) c.current = 0
            val cur = tabs.getOrNull(c.current)
            val dk = dark(c)
            val fg = if (dk) Color.WHITE else Color.BLACK
            val v = RemoteViews(ctx.packageName, R.layout.widget_eink)
            v.setInt(R.id.ek_root, "setBackgroundResource", when (c.bg) { "dark" -> R.drawable.ek_page_dark; "light" -> R.drawable.ek_page_white; else -> R.drawable.ek_page_paper })
            v.setInt(R.id.ek_line, "setBackgroundColor", if (dk) 0x33FFFFFF else 0x33000000)

            // kategorie jako pigułki: wybrana wypełniona, reszta obramowana; na końcu „+”
            v.removeAllViews(R.id.ek_chips)
            tabs.take(6).forEachIndexed { k, n ->
                val sel = k == c.current
                val chip = RemoteViews(ctx.packageName, R.layout.widget_eink_chip)
                chip.setTextViewText(R.id.ek_chip, n.title.ifEmpty { if (n.isList) "Lista" else "Notatka" })
                chip.setInt(R.id.ek_chip, "setBackgroundResource", when {
                    sel && dk -> R.drawable.ek_chip_sel_d; sel -> R.drawable.ek_chip_sel; dk -> R.drawable.ek_chip_d; else -> R.drawable.ek_chip
                })
                chip.setTextColor(R.id.ek_chip, if (sel) (if (dk) Color.BLACK else Color.WHITE) else fg)
                chip.setOnClickPendingIntent(R.id.ek_chip, pi(ctx, id * 10 + k, Intent(ctx, EinkWidget::class.java).setAction(ACT_TAB)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("tab", k)))
                v.addView(R.id.ek_chips, chip)
            }
            val plus = RemoteViews(ctx.packageName, R.layout.widget_eink_chip)
            plus.setTextViewText(R.id.ek_chip, "+")
            plus.setInt(R.id.ek_chip, "setBackgroundResource", if (dk) R.drawable.ek_chip_d else R.drawable.ek_chip)
            plus.setTextColor(R.id.ek_chip, fg)
            plus.setOnClickPendingIntent(R.id.ek_chip, PendingIntent.getActivity(ctx, 61_000 + id,
                Intent(ctx, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("notatnik://ekcfg/$id")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            v.addView(R.id.ek_chips, plus)

            // lista lub tekst
            val svc = Intent(ctx, EinkWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("notatnik://ek/$id"))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.ek_list, svc)
            v.setEmptyView(R.id.ek_list, R.id.ek_empty)
            v.setTextColor(R.id.ek_empty, if (dk) 0x99FFFFFF.toInt() else 0x99000000.toInt())
            v.setPendingIntentTemplate(R.id.ek_list, PendingIntent.getBroadcast(ctx, 52_000 + id,
                Intent(ctx, EinkWidget::class.java).setAction(ACT_ITEM).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            // dół: „Dodaj” (szybkie dopisanie) i „Usuń odhaczone” (tylko listy)
            for (b in intArrayOf(R.id.ek_add, R.id.ek_clear)) {
                v.setInt(b, "setBackgroundResource", if (dk) R.drawable.ek_chip_d else R.drawable.ek_chip)
                v.setTextColor(b, fg)
            }
            v.setOnClickPendingIntent(R.id.ek_add, PendingIntent.getActivity(ctx, 62_000 + id,
                if (cur != null && !cur.isList) Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("note", cur.id).setData(Uri.parse("notatnik://ekopen/$id"))
                else Intent(ctx, QuickAddActivity::class.java).putExtra("note", cur?.id).setData(Uri.parse("notatnik://ekadd/$id")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            v.setTextViewText(R.id.ek_add, if (cur != null && !cur.isList) "✎ Edytuj" else "＋ Dodaj")
            val hasDone = cur?.isList == true && cur.items.any { it.done && !it.del }
            v.setViewVisibility(R.id.ek_clear, if (hasDone) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.ek_clear, pi(ctx, 63_000 + id, Intent(ctx, EinkWidget::class.java).setAction(ACT_CLEAR)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)))
            mgr.updateAppWidget(id, v)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.ek_list)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        Store.init(ctx)
        for (id in ids) Store.widgets.remove(id)
        Store.save()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action !in listOf(ACT_TAB, ACT_ITEM, ACT_CLEAR)) return
        Store.init(ctx)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val mgr = AppWidgetManager.getInstance(ctx)
        val c = if (id >= 0) cfg(id) else return
        val cur = tabs(c).getOrNull(c.current)
        when (intent.action) {
            ACT_TAB -> { c.current = intent.getIntExtra("tab", 0); Store.save(); update(ctx, mgr, id); mgr.notifyAppWidgetViewDataChanged(id, R.id.ek_list) }
            ACT_ITEM -> {
                val idx = intent.getIntExtra("idx", -1)
                if (cur != null && cur.isList && idx in cur.items.indices && !cur.locked) {
                    cur.items[idx].done = !cur.items[idx].done
                    cur.items[idx].touch()
                    Store.touch(cur); Store.save()
                    NotesWidget.updateAll(ctx)
                    if (cur.shared) { val p = goAsync(); Shared.syncAsync(ctx) { p.finish() } }
                } else if (cur != null) {
                    try { ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("note", cur.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                }
            }
            ACT_CLEAR -> if (cur != null && cur.isList && !cur.locked) {
                // „usunięte” zostają jako ślad – tak działają wspólne listy w Notatniku
                cur.items.filter { it.done && !it.del }.forEach { it.del = true; it.touch() }
                Store.touch(cur); Store.save()
                NotesWidget.updateAll(ctx)
                if (cur.shared) { val p = goAsync(); Shared.syncAsync(ctx) { p.finish() } }
            }
        }
    }
}

class EinkWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = F(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Row(val text: String, val done: Boolean, val idx: Int, val check: Boolean)

    private class F(val ctx: Context, val id: Int) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()
        private var cfg = WidgetCfg()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            Store.init(ctx)
            cfg = EinkWidget.cfg(id)
            val n = EinkWidget.tabs(cfg).getOrNull(cfg.current)
            rows = when {
                n == null -> emptyList()
                n.locked -> listOf(Row("🔒 Notatka zablokowana – otwórz w aplikacji", false, -1, false))
                n.isList -> n.items.withIndex().filter { !it.value.done && !it.value.del }.map { Row(it.value.text, false, it.index, true) } +
                    n.items.withIndex().filter { it.value.done && !it.value.del }.map { Row(it.value.text, true, it.index, true) }
                else -> n.body.lines().map { Row(it, false, -1, false) }.dropLastWhile { it.text.isBlank() }
            }
        }

        override fun getCount() = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val r = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_eink_row)
            val dk = cfg.bg == "dark"
            val fg = if (dk) Color.WHITE else Color.BLACK
            val dim = if (dk) 0x80FFFFFF.toInt() else 0x80000000.toInt()
            val v = RemoteViews(ctx.packageName, R.layout.widget_eink_row)
            v.setViewVisibility(R.id.ek_box, if (r.check) View.VISIBLE else View.GONE)
            v.setImageViewResource(R.id.ek_box, if (r.done) R.drawable.ic_box_on else R.drawable.ic_box)
            v.setInt(R.id.ek_box, "setColorFilter", if (r.done) dim else fg)
            v.setTextViewText(R.id.ek_text, r.text)
            v.setTextColor(R.id.ek_text, if (r.done) dim else fg)
            // przekreślenie odhaczonych (jak w launcherze)
            v.setInt(R.id.ek_text, "setPaintFlags", if (r.done) android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG else android.graphics.Paint.ANTI_ALIAS_FLAG)
            v.setTextViewTextSize(R.id.ek_text, TypedValue.COMPLEX_UNIT_SP, cfg.text.toFloat().coerceAtLeast(15f) + if (r.check) 0f else -1f)
            v.setViewVisibility(R.id.ek_div, if (r.check) View.VISIBLE else View.GONE)
            v.setInt(R.id.ek_div, "setBackgroundColor", if (dk) 0x26FFFFFF else 0x1F000000)
            v.setOnClickFillInIntent(R.id.ek_row, Intent().putExtra("idx", r.idx))
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
