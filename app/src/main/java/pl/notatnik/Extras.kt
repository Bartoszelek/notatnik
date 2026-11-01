package pl.notatnik

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Proste formatowanie: **pogrubienie**, # nagłówek, • punkty, 1. numeracja. */
object Fmt {
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

    /** Styl na żywo w polu edycji (znaczniki zostają, ale są przygaszone). */
    fun style(e: Editable, dim: Int) {
        for (sp in e.getSpans(0, e.length, StyleSpan::class.java)) e.removeSpan(sp)
        for (sp in e.getSpans(0, e.length, RelativeSizeSpan::class.java)) e.removeSpan(sp)
        for (sp in e.getSpans(0, e.length, ForegroundColorSpan::class.java)) e.removeSpan(sp)
        val s = e.toString()
        for (m in BOLD.findAll(s)) {
            e.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            e.setSpan(ForegroundColorSpan(dim), m.range.first, m.range.first + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            e.setSpan(ForegroundColorSpan(dim), m.range.last - 1, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var pos = 0
        for (line in s.split("\n")) {
            if (line.startsWith("# ")) {
                e.setSpan(StyleSpan(Typeface.BOLD), pos, pos + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                e.setSpan(RelativeSizeSpan(1.3f), pos, pos + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                e.setSpan(ForegroundColorSpan(dim), pos, pos + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            pos += line.length + 1
        }
    }

    /** Tekst do podglądu na kafelku: bez znaczników, z pogrubieniami i nagłówkami. */
    fun render(text: String): CharSequence {
        val out = SpannableStringBuilder()
        text.split("\n").forEachIndexed { k, raw ->
            if (k > 0) out.append("\n")
            val head = raw.startsWith("# ")
            val line = if (head) raw.removePrefix("# ") else raw
            val start = out.length
            var last = 0
            for (m in BOLD.findAll(line)) {
                out.append(line, last, m.range.first)
                val b = out.length
                out.append(m.groupValues[1])
                out.setSpan(StyleSpan(Typeface.BOLD), b, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                last = m.range.last + 1
            }
            out.append(line, last, line.length)
            if (head) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.15f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}

/** Lista zakupów: działy sklepu i podpowiedzi z historii. */
object Shop {
    private val CATS: List<Pair<String, List<String>>> = listOf(
        "Owoce i warzywa" to listOf("jabl", "banan", "gruszk", "pomidor", "ogór", "ogor", "ziemniak", "marchew", "cebul", "czosnek", "sałat", "salat",
            "papryk", "cytryn", "pomarańcz", "pomarancz", "winogr", "trusk", "malin", "borówk", "borowk", "brokuł", "brokul", "kalafior", "kapust",
            "pietruszk", "koper", "szczypior", "rzodkiew", "awokado", "grzyb", "pieczark", "cukini", "bakłażan", "dyni", "seler", "por ", "kiwi", "mango", "ananas", "rukol", "szpinak", "imbir", "limonk"),
        "Pieczywo" to listOf("chleb", "bułk", "bulk", "bagiet", "rogal", "tortill", "pita", "chałk", "pieczyw", "grahamk", "kajzer"),
        "Nabiał i jajka" to listOf("mleko", "ser ", "serek", "jogurt", "kefir", "maślank", "maslank", "śmietan", "smietan", "masło", "maslo", "twaróg", "twarog", "jaj", "mozzarell", "parmez", "feta", "skyr"),
        "Mięso i ryby" to listOf("kurczak", "pierś", "piers", "mięso", "mieso", "wołow", "wolow", "wieprz", "schab", "boczek", "szynk", "kiełbas", "kielbas", "parówk", "parowk", "mielon", "indyk", "łosoś", "losos", "ryb", "dorsz", "tuńczyk", "tunczyk", "krewet", "salami", "pasztet"),
        "Mrożonki" to listOf("mrożon", "mrozon", "lody", "pizza", "frytk", "pierogi"),
        "Spiżarnia" to listOf("makaron", "ryż", "ryz", "kasza", "mąka", "maka", "cukier", "sól", "sol ", "olej", "oliw", "ocet", "płatki", "platki", "musli", "konserw", "fasol", "ciecierzyc", "soczewic", "pomidory w puszce", "passat", "koncentrat", "kukurydz", "groszek", "drożdż", "drozdz", "proszek do pieczenia", "bułka tarta"),
        "Przyprawy i sosy" to listOf("pieprz", "papryka słodka", "oregano", "bazyli", "curry", "przypraw", "ketchup", "majonez", "musztard", "sos ", "miód", "miod", "dżem", "dzem", "nutell", "cynamon", "wanili"),
        "Słodycze i przekąski" to listOf("czekolad", "ciastk", "chips", "paluszk", "baton", "cukierk", "żelk", "zelk", "orzech", "krakers", "wafel", "rodzynk"),
        "Napoje" to listOf("woda", "sok", "kawa", "herbat", "cola", "napój", "napoj", "piwo", "wino", "lemoniad", "energetyk", "kakao"),
        "Chemia i kosmetyki" to listOf("papier toalet", "ręcznik", "recznik", "płyn", "plyn", "proszek", "kapsułk", "kapsulk", "mydło", "mydlo", "szampon", "pasta do zęb", "pasta do zeb", "szczoteczk", "dezodorant", "worki", "gąbk", "gabk", "folia", "chusteczk", "pieluch", "żel pod", "zel pod", "tabletki do zmyw", "odplamiacz", "domestos", "ludwik"),
        "Dla zwierząt" to listOf("karma", "żwirek", "zwirek", "dla psa", "dla kota", "przysmak")
    )

    fun category(text: String): Int {
        val t = " " + text.lowercase(Locale("pl")) + " "
        CATS.forEachIndexed { i, (_, keys) -> if (keys.any { t.contains(it) }) return i }
        return CATS.size
    }

    fun categoryName(i: Int) = CATS.getOrNull(i)?.first ?: "Inne"

    /** Sortuje niezaznaczone pozycje według działów sklepu (zaznaczone zostają na końcu). */
    fun sort(n: Note) {
        val open = n.items.filter { !it.done && !it.del }.sortedBy { category(it.text) }
        val rest = n.items.filter { it.done || it.del }
        n.items.clear()
        n.items.addAll(open)
        n.items.addAll(rest)
        open.forEach { it.touch() }
    }

    /** Podpowiedzi z wcześniejszych list (najczęstsze najpierw). */
    fun suggest(prefix: String, current: Note): List<String> {
        val p = Store.norm(prefix.trim())
        if (p.length < 2) return emptyList()
        val have = current.live.filter { !it.done }.map { Store.norm(it.text) }.toSet()
        val freq = HashMap<String, Pair<String, Int>>()
        for (n in Store.notes) if (n.isList || n.isRecipe) for (it in n.items) {
            val k = Store.norm(it.text.trim())
            if (k.isEmpty() || k in have || !k.contains(p)) continue
            val cur = freq[k]
            freq[k] = Pair(cur?.first ?: it.text.trim(), (cur?.second ?: 0) + 1)
        }
        return freq.values.sortedWith(compareByDescending<Pair<String, Int>> { if (Store.norm(it.first).startsWith(p)) 1 else 0 }.thenByDescending { it.second })
            .map { it.first }.take(6)
    }
}

/** Przeliczanie ilości w składnikach przy zmianie liczby porcji (wszystkie liczby w linijce, poza cm, °C, %). */
object Scale {
    private val NUM = Regex("(?<![\\d,./])(\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+[.,]\\d+|\\d+|½|¼|¾|⅓|⅔)(?!\\d|[.,/]\\d)(?!\\s*(?:cm|mm|°|%|stopni|st\\.|x\\s*\\d))")
    private val WORDS = listOf("półtorej" to 1.5, "półtora" to 1.5, "pół" to 0.5)

    private fun parse(s: String): Double? {
        val t = s.trim()
        return when {
            t == "½" -> 0.5
            t == "¼" -> 0.25
            t == "¾" -> 0.75
            t == "⅓" -> 1.0 / 3
            t == "⅔" -> 2.0 / 3
            t.contains(" ") && t.contains("/") -> {
                val parts = t.split(Regex("\\s+"), 2)
                val f = parts[1].split("/")
                parts[0].toDouble() + f[0].trim().toDouble() / f[1].trim().toDouble()
            }
            t.contains("/") -> {
                val f = t.split("/")
                f[0].trim().toDouble() / f[1].trim().toDouble()
            }
            else -> t.replace(",", ".").toDoubleOrNull()
        }
    }

    private fun fmt(v: Double): String {
        val frac = v - Math.floor(v)
        val whole = Math.floor(v).toLong()
        fun withWhole(sym: String) = if (whole > 0) "$whole $sym" else sym
        return when {
            v >= 20 -> Math.round(v).toString()
            Math.abs(frac) < 0.04 -> whole.toString()
            Math.abs(frac - 1) < 0.04 -> (whole + 1).toString()
            Math.abs(frac - 0.5) < 0.04 -> withWhole("½")
            Math.abs(frac - 0.25) < 0.04 -> withWhole("¼")
            Math.abs(frac - 0.75) < 0.04 -> withWhole("¾")
            Math.abs(frac - 1.0 / 3) < 0.04 -> withWhole("⅓")
            Math.abs(frac - 2.0 / 3) < 0.04 -> withWhole("⅔")
            else -> String.format(Locale("pl"), "%.1f", v).removeSuffix(",0")
        }
    }

    fun line(text: String, factor: Double): String {
        if (factor == 1.0) return text
        val lower = text.lowercase(Locale("pl"))
        // słowa na początku: „pół łyżeczki”, „półtorej szklanki”
        for ((w, v) in WORDS) {
            if (lower.startsWith("$w ")) {
                val rest = text.substring(w.length)
                return fmt(v * factor) + NUM.replace(rest) { m -> scaleMatch(m.value, factor) }
            }
        }
        return NUM.replace(text) { m -> scaleMatch(m.value, factor) }
    }

    private fun scaleMatch(v: String, factor: Double): String {
        val d = try {
            parse(v)
        } catch (e: Exception) {
            null
        }
        return if (d == null) v else fmt(d * factor)
    }
}

/** Wizytówka: wyciąga dane kontaktowe z odczytanego tekstu. */
object BizCard {
    class Data(var name: String = "", var company: String = "", var title: String = "", var phone: String = "",
               var email: String = "", var www: String = "", var address: String = "")

    private val TITLES = listOf("dyrektor", "kierownik", "prezes", "manager", "menedżer", "specjalista", "właściciel", "wlasciciel",
        "handlowiec", "doradca", "konsultant", "inżynier", "inzynier", "księgow", "ksiegow", "przedstawiciel", "koordynator", "ceo", "cto", "sales", "account", "director", "head of", "owner")

    fun parse(text: String): Data {
        val d = Data()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        d.email = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+").find(text)?.value ?: ""
        d.phone = Regex("(\\+?\\d[\\d ()-]{7,}\\d)").findAll(text).map { it.value.trim() }
            .firstOrNull { it.count { c -> c.isDigit() } in 9..13 } ?: ""
        d.www = Regex("(?i)\\b(www\\.[\\w.-]+\\.[a-z]{2,}|https?://\\S+)").find(text)?.value ?: ""
        d.company = lines.firstOrNull { l ->
            l.contains("sp. z o.o", true) || l.contains(" S.A", true) || l.contains("s.c.", true) || l.contains("sp.j", true) || l.contains(" sp. k", true)
        } ?: ""
        d.title = lines.firstOrNull { l -> TITLES.any { l.lowercase(Locale("pl")).contains(it) } && l.length < 60 } ?: ""
        d.address = lines.firstOrNull { l -> Regex("\\d{2}-\\d{3}").containsMatchIn(l) || l.lowercase().startsWith("ul.") } ?: ""
        d.name = lines.firstOrNull { l ->
            val w = l.split(Regex("\\s+"))
            w.size in 2..3 && w.all { it.isNotEmpty() && it[0].isUpperCase() && it.drop(1).all { c -> c.isLetter() || c == '-' } } &&
                l != d.company && l != d.title && !l.contains("@")
        } ?: ""
        if (d.company.isEmpty()) d.company = lines.firstOrNull { l ->
            l != d.name && l != d.title && l.length in 3..40 && l.count { it.isLetter() } >= 3 &&
                l.filter { it.isLetter() }.all { it.isUpperCase() }
        } ?: ""
        return d
    }
}
