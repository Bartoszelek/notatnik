package pl.notatnik

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Import przepisu ze strony. Trzy źródła, łączone tak, żeby wyszło najpełniej:
 * 1) dane schema.org (JSON-LD), 2) mikrodane (itemprop), 3) budowa strony:
 *    „Składniki” + lista, „Krok 1: …” + opis albo „Przygotowanie” + lista, porcje, czasy, porady.
 */
object RecipeImport {
    class Result(
        val title: String, val ingredients: List<String>, val steps: List<String>, val servings: Int,
        val time: String, val image: String, val url: String, val tips: List<String>
    )

    private class Part(
        var title: String = "", var ingredients: List<String> = emptyList(), var steps: List<String> = emptyList(),
        var servings: Int = 0, var time: String = "", var image: String = "", var tips: List<String> = emptyList()
    )

    private const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val I = setOf(RegexOption.IGNORE_CASE)
    private val ING = Regex("^(składniki|skladniki|ingredients|potrzebne składniki|lista składników|składniki na)\\b.{0,40}$", I)
    private val STEP_HEAD = Regex("^(przygotowanie|sposób przygotowania|sposob przygotowania|wykonanie|przepis krok po kroku|instrukcje|instructions|directions|method|jak zrobić|jak przygotować|przygotowanie krok po kroku)\\b.{0,50}$", I)
    private val KROK = Regex("^\\s*krok\\s*(\\d+)\\s*[:.)\\-–]?\\s*(.*)$", I)
    private val KROK_START = Regex("^\\s*krok\\s*\\d+", I)
    private val TIPS = Regex("^(uwagi|porady|wskazówki|wskazowki|rady|tips|notes|dodatkowe informacje)\\b", I)
    private val SKIP_TAGS = setOf("figure", "picture", "img", "button", "svg", "script", "style", "noscript", "form", "input")

    fun clean(s: String) = s.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    fun load(url: String): Result {
        val doc = Jsoup.connect(url).userAgent(UA).header("Accept-Language", "pl,en;q=0.8")
            .timeout(20_000).followRedirects(true).maxBodySize(0).get()
        val ld = fromJsonLd(doc)
        val md = fromMicrodata(doc)
        val hx = fromHtml(doc)

        val ingredients = listOf(ld.ingredients, md.ingredients, hx.ingredients).firstOrNull { it.size >= 2 }
            ?: listOf(ld.ingredients, md.ingredients, hx.ingredients).firstOrNull { it.isNotEmpty() } ?: emptyList()
        val steps = when {
            hx.steps.size >= 2 && hx.steps.size >= ld.steps.size -> hx.steps
            ld.steps.isNotEmpty() -> ld.steps
            md.steps.isNotEmpty() -> md.steps
            else -> hx.steps
        }
        val og = { p: String -> doc.selectFirst("meta[property=\"$p\"]")?.attr("content")?.trim() ?: "" }
        val siteTitle = doc.title().split(" | ", " - ", " – ").firstOrNull()?.trim() ?: ""
        val title = listOf(ld.title, md.title, og("og:title"), doc.selectFirst("h1")?.text() ?: "", siteTitle)
            .map { clean(it) }.firstOrNull { it.isNotEmpty() } ?: "Przepis"
        val image = listOf(ld.image, md.image, og("og:image"), hx.image).firstOrNull { it.startsWith("http") } ?: ""
        val servings = listOf(ld.servings, md.servings, hx.servings).firstOrNull { it > 0 } ?: 0
        val time = listOf(hx.time, ld.time, md.time).firstOrNull { it.isNotEmpty() } ?: ""
        return Result(title, ingredients.distinct().take(80), steps.take(40), servings, time, image, url, (hx.tips + ld.tips).distinct().take(12))
    }

    // ------------------------------------------------------------ JSON-LD
    private fun isRecipe(o: JSONObject): Boolean {
        val t = o.opt("@type")
        return (t is String && t.equals("Recipe", true)) || (t is JSONArray && (0 until t.length()).any { t.optString(it).equals("Recipe", true) })
    }

    private fun findRecipe(x: Any?): JSONObject? {
        when (x) {
            is JSONObject -> {
                if (isRecipe(x)) return x
                for (k in listOf("@graph", "mainEntity", "mainEntityOfPage", "itemListElement")) findRecipe(x.opt(k))?.let { return it }
            }
            is JSONArray -> for (k in 0 until x.length()) findRecipe(x.opt(k))?.let { return it }
        }
        return null
    }

    private fun textOf(html: String): String = clean(Jsoup.parse(html).text())

    private fun ldSteps(x: Any?, out: MutableList<String>) {
        when (x) {
            is String -> {
                if (x.contains("<")) {
                    val d = Jsoup.parse(x)
                    val items = d.select("li, p").map { clean(it.text()) }.filter { it.isNotEmpty() }
                    if (items.isNotEmpty()) out.addAll(items) else out.add(textOf(x))
                } else x.split(Regex("\\r?\\n+")).map { clean(it) }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            }
            is JSONArray -> for (k in 0 until x.length()) ldSteps(x.opt(k), out)
            is JSONObject -> {
                val type = x.optString("@type")
                if (type.equals("HowToSection", true) || x.has("itemListElement")) {
                    ldSteps(x.opt("itemListElement"), out)
                } else {
                    val text = textOf(x.optString("text"))
                    val name = textOf(x.optString("name"))
                    when {
                        text.isEmpty() && name.isNotEmpty() -> out.add(name)
                        name.isNotEmpty() && !text.startsWith(name.take(20)) && name.length < 80 -> out.add("**$name** — $text")
                        text.isNotEmpty() -> out.add(text)
                    }
                }
            }
        }
    }

    private fun minutes(iso: String): Int {
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?", I).find(iso) ?: return 0
        return (m.groupValues[1].toIntOrNull() ?: 0) * 1440 + (m.groupValues[2].toIntOrNull() ?: 0) * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
    }

    fun fmtMinutes(m: Int): String = when {
        m <= 0 -> ""
        m >= 60 && m % 60 == 0 -> "${m / 60} h"
        m >= 60 -> "${m / 60} h ${m % 60} min"
        else -> "$m min"
    }

    private fun fromJsonLd(doc: Document): Part {
        val p = Part()
        for (s in doc.select("script[type=\"application/ld+json\"]")) {
            val raw = s.data().trim().replace(Regex("[\\u0000-\\u001F]"), " ")
            val parsed: Any? = try {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            } catch (e: Exception) {
                null
            }
            val r = findRecipe(parsed) ?: continue
            p.title = textOf(r.optString("name"))
            val ing = r.optJSONArray("recipeIngredient") ?: r.optJSONArray("ingredients")
            if (ing != null) p.ingredients = (0 until ing.length()).map { textOf(ing.optString(it)) }.filter { it.isNotEmpty() }
            val st = ArrayList<String>()
            ldSteps(r.opt("recipeInstructions"), st)
            p.steps = st.filter { it.isNotEmpty() }
            val y = r.opt("recipeYield")
            val ys = if (y is JSONArray) (0 until y.length()).joinToString(" ") { y.optString(it) } else y?.toString() ?: ""
            p.servings = Regex("\\d+").find(ys)?.value?.toIntOrNull() ?: 0
            val total = minutes(r.optString("totalTime"))
            p.time = fmtMinutes(if (total > 0) total else minutes(r.optString("prepTime")) + minutes(r.optString("cookTime")))
            val img = r.opt("image")
            p.image = when (img) {
                is String -> img
                is JSONArray -> img.opt(0).let { if (it is JSONObject) it.optString("url") else it?.toString() ?: "" }
                is JSONObject -> img.optString("url")
                else -> ""
            }
            break
        }
        return p
    }

    // ------------------------------------------------------------ mikrodane
    private fun fromMicrodata(doc: Document): Part {
        val p = Part()
        val root = doc.selectFirst("[itemtype*=Recipe]") ?: return p
        p.title = clean(root.selectFirst("[itemprop=name]")?.text() ?: "")
        p.ingredients = root.select("[itemprop=recipeIngredient], [itemprop=ingredients]").map { clean(it.text()) }.filter { it.isNotEmpty() }
        val ins = root.select("[itemprop=recipeInstructions]")
        p.steps = if (ins.size == 1 && ins[0].select("li, p").size > 1) ins[0].select("li, p").map { clean(it.text()) }.filter { it.isNotEmpty() }
        else ins.map { clean(it.text()) }.filter { it.isNotEmpty() }
        p.servings = Regex("\\d+").find(root.selectFirst("[itemprop=recipeYield]")?.text() ?: "")?.value?.toIntOrNull() ?: 0
        val img = root.selectFirst("[itemprop=image]")
        p.image = img?.attr("abs:src")?.ifEmpty { img.attr("abs:content") } ?: ""
        return p
    }

    // ------------------------------------------------------------ budowa strony
    private fun shortText(e: Element): String {
        val t = clean(e.text())
        return if (t.length <= 60) t else ""
    }

    private fun fromHtml(doc: Document): Part {
        val p = Part()
        val body = doc.body() ?: return p
        body.select("script, style, noscript, nav, header, footer, aside, form, iframe, [class*=comment], [id*=comment], [class*=related], [class*=newsletter], [class*=cookie]").remove()
        val all = body.allElements

        // składniki: po nagłówku „Składniki” – pozycje list aż do kroków/przygotowania/kolejnego nagłówka
        val ingIdx = all.indexOfFirst { e -> e.tagName() !in SKIP_TAGS && shortText(e).let { it.isNotEmpty() && ING.matches(it.trimEnd(':')) } && e.children().size <= 3 }
        if (ingIdx >= 0) {
            val out = ArrayList<String>()
            for (k in ingIdx + 1 until minOf(all.size, ingIdx + 600)) {
                val e = all[k]
                val st = shortText(e)
                if (st.isNotEmpty() && (STEP_HEAD.matches(st) || KROK_START.containsMatchIn(st))) break
                if (out.isNotEmpty() && e.tagName() in setOf("h1", "h2") ) break
                if (e.tagName() == "li" && e.select("li").size == 1) {
                    val t = clean(e.text())
                    if (t.length in 2..220) out.add(t)
                }
            }
            if (out.isEmpty()) {
                // linijki z kropkami/br zamiast listy
                val next = all[ingIdx].nextElementSibling()
                next?.wholeText()?.split(Regex("\\n|•"))?.map { clean(it) }?.filter { it.length in 2..200 }?.let { out.addAll(it.take(60)) }
            }
            p.ingredients = out.distinct()
        }

        // kroki „Krok 1: tytuł” + opis
        val krokMatches = all.filter { e -> e.tagName() !in SKIP_TAGS && clean(e.text()).let { it.length <= 140 && KROK_START.containsMatchIn(it) } }
        val deepest = krokMatches.filter { a -> krokMatches.none { b -> b !== a && b.parents().contains(a) } }
        if (deepest.size >= 2) {
            val steps = ArrayList<String>()
            val seen = HashSet<Int>()
            for (m in deepest) {
                val km = KROK.find(clean(m.text())) ?: continue
                val no = km.groupValues[1].toIntOrNull() ?: continue
                if (!seen.add(no)) continue
                val title = km.groupValues[2].trim().trimEnd(':')
                val body2 = stepBody(m)
                steps.add(when {
                    title.isNotEmpty() && body2.isNotEmpty() -> "**$title** — $body2"
                    body2.isNotEmpty() -> body2
                    else -> title
                })
            }
            p.steps = steps.filter { it.isNotEmpty() }
        }

        // „Przygotowanie” + lista albo akapity
        if (p.steps.size < 2) {
            val sIdx = all.indexOfFirst { e -> e.tagName() !in SKIP_TAGS && shortText(e).let { it.isNotEmpty() && STEP_HEAD.matches(it.trimEnd(':')) } && e.children().size <= 3 }
            if (sIdx >= 0) {
                val li = ArrayList<String>()
                val paras = ArrayList<String>()
                for (k in sIdx + 1 until minOf(all.size, sIdx + 800)) {
                    val e = all[k]
                    val st = shortText(e)
                    if (st.isNotEmpty() && TIPS.containsMatchIn(st)) break
                    if ((li.isNotEmpty() || paras.size > 1) && e.tagName() in setOf("h1", "h2", "h3") ) break
                    if (e.tagName() == "li" && e.select("li").size == 1) clean(e.text()).let { if (it.length > 3) li.add(it) }
                    if (e.tagName() == "p") clean(e.text()).let { if (it.length > 25) paras.add(it) }
                }
                p.steps = (if (li.size >= 2) li else paras).take(40)
            }
        }

        // porady
        val tipEls = all.filter { e ->
            e.tagName() !in SKIP_TAGS && e.tagName() != "a" && e.parents().none { it.tagName() == "a" } &&
                clean(e.ownText()).let { it.length >= 6 && TIPS.containsMatchIn(it) }
        }.take(6)
        for (tipEl in tipEls) {
            val parts = tipEl.wholeText().split(Regex("•|\\n")).map { clean(it) }.filter { it.length > 15 }
            val list = (if (parts.size > 1) parts.drop(if (TIPS.containsMatchIn(parts[0]) && parts[0].length < 90) 1 else 0) else
                tipEl.nextElementSibling()?.select("li")?.map { clean(it.text()) } ?: emptyList()).filter { it.length > 15 }
            if (list.isNotEmpty()) {
                p.tips = list.take(12)
                break
            }
        }

        // porcje i czasy z tekstu strony
        val text = clean(body.text())
        p.servings = Regex("(liczba porcji|ilość porcji|ilosc porcji|porcje|porcji|porcja|serves|servings|wystarczy dla)\\s*:?\\s*(\\d+)", I)
            .find(text)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val times = Regex("czas ([\\p{L} ]{3,25}?)\\s*:\\s*(\\d+)\\s*(minut\\w*|min\\.?|godzin\\w*|godz\\.?|h)\\b", I).findAll(text)
            .map { m ->
                val what = m.groupValues[1].trim().lowercase().replace(Regex("nia$"), "nie")
                val unit = if (m.groupValues[3].startsWith("g") || m.groupValues[3] == "h") "h" else "min"
                "$what ${m.groupValues[2]} $unit"
            }.distinct().take(3).toList()
        p.time = times.joinToString(" · ")
        return p
    }

    /** Opis kroku: akapity po elemencie „Krok N” aż do następnego kroku (bez zdjęć). */
    private fun stepBody(m: Element): String {
        var anchor: Element? = m
        repeat(3) {
            val a = anchor ?: return ""
            val sb = StringBuilder()
            var sib = a.nextElementSibling()
            var n = 0
            while (sib != null && n < 15) {
                val t = clean(sib.text())
                if (KROK_START.containsMatchIn(t) || sib.tagName() == "hr") break
                if (sib.tagName() !in SKIP_TAGS && t.isNotEmpty() && sib.select("img").isEmpty()) sb.append(t).append(' ')
                sib = sib.nextElementSibling()
                n++
            }
            if (sb.isNotBlank()) return clean(sb.toString())
            anchor = a.parent()
        }
        return ""
    }

    fun downloadImage(url: String, dest: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
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
