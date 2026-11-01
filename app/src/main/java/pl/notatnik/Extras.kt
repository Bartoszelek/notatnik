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

/** Przeliczanie ilości w składnikach przy zmianie liczby porcji. */
object Scale {
    private val NUM = Regex("^(\\d+[.,]\\d+|\\d+\\s*/\\s*\\d+|\\d+\\s+\\d+/\\d+|\\d+|½|¼|¾)")

    private fun parse(s: String): Double? {
        val t = s.trim()
        return when {
            t == "½" -> 0.5
            t == "¼" -> 0.25
            t == "¾" -> 0.75
            t.contains(" ") && t.contains("/") -> {
                val (w, f) = t.split(Regex("\\s+"), 2)
                val (a, b) = f.split("/")
                w.toDouble() + a.trim().toDouble() / b.trim().toDouble()
            }
            t.contains("/") -> {
                val (a, b) = t.split("/")
                a.trim().toDouble() / b.trim().toDouble()
            }
            else -> t.replace(",", ".").toDoubleOrNull()
        }
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return when {
            Math.abs(r - Math.round(r)) < 0.01 -> Math.round(r).toString()
            Math.abs(r - 0.5) < 0.01 -> "½"
            Math.abs(r - 0.25) < 0.01 -> "¼"
            Math.abs(r - 0.75) < 0.01 -> "¾"
            else -> String.format(Locale("pl"), "%.2f", r).trimEnd('0').trimEnd(',')
        }
    }

    fun line(text: String, factor: Double): String {
        if (factor == 1.0) return text
        val m = NUM.find(text.trim()) ?: return text
        val v = (try {
            parse(m.value)
        } catch (e: Exception) {
            null
        }) ?: return text
        return fmt(v * factor) + text.trim().substring(m.value.length)
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

/** Import przepisu ze strony internetowej (dane schema.org Recipe, jak w większości serwisów kulinarnych). */
object RecipeImport {
    class Result(val title: String, val ingredients: List<String>, val steps: List<String>, val servings: Int,
                 val time: String, val image: String, val url: String)

    private fun fetch(url: String): String {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "pl,en;q=0.8")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location") ?: return "").toString()
                c.disconnect()
            } else {
                if (code != 200) throw java.io.IOException("Strona odpowiedziała kodem $code")
                return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
        return ""
    }

    private fun isRecipe(o: JSONObject): Boolean {
        val t = o.opt("@type")
        return t == "Recipe" || (t is JSONArray && (0 until t.length()).any { t.optString(it) == "Recipe" })
    }

    private fun findRecipe(x: Any?): JSONObject? {
        when (x) {
            is JSONObject -> {
                if (isRecipe(x)) return x
                findRecipe(x.opt("@graph"))?.let { return it }
                findRecipe(x.opt("mainEntity"))?.let { return it }
            }
            is JSONArray -> for (k in 0 until x.length()) findRecipe(x.opt(k))?.let { return it }
        }
        return null
    }

    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    private fun steps(x: Any?, out: MutableList<String>) {
        when (x) {
            is String -> x.split(Regex("\\n+|(?<=\\.)\\s{2,}")).map { clean(it) }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            is JSONArray -> for (k in 0 until x.length()) steps(x.opt(k), out)
            is JSONObject -> {
                if (x.has("itemListElement")) steps(x.opt("itemListElement"), out)
                else x.optString("text").let { if (it.isNotBlank()) out.add(clean(it)) }
            }
        }
    }

    private fun duration(iso: String): String {
        val m = Regex("P(?:\\d+D)?T?(?:(\\d+)H)?(?:(\\d+)M)?").find(iso) ?: return ""
        val hh = m.groupValues[1].toIntOrNull() ?: 0
        val mm = m.groupValues[2].toIntOrNull() ?: 0
        return when {
            hh > 0 && mm > 0 -> "$hh h $mm min"
            hh > 0 -> "$hh h"
            mm > 0 -> "$mm min"
            else -> ""
        }
    }

    fun load(url: String): Result {
        val html = fetch(url)
        val blocks = Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { it.groupValues[1].trim() }
        var r: JSONObject? = null
        for (b in blocks) {
            val parsed: Any? = try {
                if (b.startsWith("[")) JSONArray(b) else JSONObject(b)
            } catch (e: Exception) {
                null
            }
            r = findRecipe(parsed)
            if (r != null) break
        }
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)?.let { clean(it) } ?: "Przepis"
        val rec = r ?: return Result(title, emptyList(), emptyList(), 0, "", "", url)
        val ing = rec.optJSONArray("recipeIngredient")?.let { a -> (0 until a.length()).map { clean(a.optString(it)) }.filter { it.isNotEmpty() } } ?: emptyList()
        val st = ArrayList<String>()
        steps(rec.opt("recipeInstructions"), st)
        val yieldAny = rec.opt("recipeYield")
        val yieldStr = if (yieldAny is JSONArray) yieldAny.optString(0) else yieldAny?.toString() ?: ""
        val serv = Regex("\\d+").find(yieldStr)?.value?.toIntOrNull() ?: 0
        val time = duration(rec.optString("totalTime").ifEmpty { rec.optString("cookTime") })
        val imgAny = rec.opt("image")
        val img = when (imgAny) {
            is String -> imgAny
            is JSONArray -> imgAny.opt(0).let { if (it is JSONObject) it.optString("url") else it?.toString() ?: "" }
            is JSONObject -> imgAny.optString("url")
            else -> ""
        }
        return Result(clean(rec.optString("name")).ifEmpty { title }, ing, st, serv, time, img, url)
    }

    fun downloadImage(url: String, dest: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Notatnik")
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        if (c.responseCode == 200) {
            c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            true
        } else false
    } catch (e: Exception) {
        false
    }
}
