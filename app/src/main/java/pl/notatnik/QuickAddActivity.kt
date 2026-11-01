package pl.notatnik

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Okienko „Dodaj” z widżetu: pozycja do listy albo linijka do notatki. Można też podyktować. */
class QuickAddActivity : Activity() {
    private lateinit var input: EditText
    private var note: Note? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Theme.apply(this, Store.theme, Store.mode)
        note = Store.note(intent.getStringExtra("note"))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(Theme.surface); cornerRadius = dp(22).toFloat() }
        }
        val n = note
        col.addView(TextView(this).apply {
            text = if (n == null) "Nowa lista" else "Dodaj do: " + n.title.ifEmpty { if (n.isList) "Lista" else "Notatka" }
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.fg)
        })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = if (n?.isList != false) "Nowa pozycja (każda linijka osobno)" else "Tekst do dopisania"
            setTextColor(Theme.fg)
            setHintTextColor(Theme.dim)
            background = GradientDrawable().apply { setColor(Theme.bg); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, a, _ ->
                if (a == EditorInfo.IME_ACTION_DONE) {
                    add(false); true
                } else false
            }
        }
        r.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.accent) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dictate() }
        }
        r.addView(mic, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(10) })
        col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun b(t: String, primary: Boolean, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else Theme.fg)
            background = GradientDrawable().apply { setColor(if (primary) Theme.accent else Theme.bg); cornerRadius = dp(14).toFloat() }
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { f() }
        }
        btns.addView(b("Zamknij", false) { finish() })
        btns.addView(b("Dodaj kolejną", false) { add(false) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        btns.addView(b("Dodaj", true) { add(true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        col.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        setContentView(col)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    private fun add(close: Boolean) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            if (close) finish()
            return
        }
        val n = note ?: Note(type = "list", title = "Lista").also {
            Store.notes.add(it)
            note = it
        }
        if (n.isList) text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { n.items.add(0, Item(it)) }
        else n.body = (n.body.trimEnd() + "\n" + text).trim()
        Store.touch(n)
        Store.save()
        NotesWidget.updateAll(this)
        if (n.shared) Shared.syncAsync(this)
        input.setText("")
        if (close) finish() else Toast.makeText(this, "Dodano", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun dictate() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Mów…")
        try {
            startActivityForResult(i, 1)
        } catch (e: Exception) {
            Toast.makeText(this, "Rozpoznawanie mowy niedostępne", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            val cur = input.text.toString().trim()
            input.setText(if (cur.isEmpty()) t else "$cur\n$t")
            input.setSelection(input.text.length)
        }
    }
}
