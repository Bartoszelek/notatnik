package pl.notatnik

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gotowe podsumowanie spotkania. */
class Summary(
    val points: List<String>,
    val decisions: List<String>,
    val tasks: List<String>,        // „Kto – co – do kiedy”
    val questions: List<String>,
    val by: String                  // groq albo offline
)

/**
 * Podsumowania: online przez Groq (darmowy klucz, wbudowany domyślnie – można podmienić w ustawieniach),
 * a gdy limit się skończy, nie ma internetu lub wybrano „tylko offline” – uproszczone podsumowanie w telefonie.
 */
object Summarizer {
    // Klucz wbudowany w częściach (łatwa podmiana: Nagrania → Ustawienia → Klucz Groq).
    private val BUILTIN = listOf("gsk_BZv5WUS8cUi", "AYI8r0aOeWGdyb3FY", "SV0SVffZ5qZ0xUcVIuTFURPj").joinToString("")

    fun key(): String = MeetCfg.groqKey.ifBlank { BUILTIN }

    private const val API = "https://api.groq.com/openai/v1/chat/completions"
    private val MODELS = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant")

    var lastError = ""

    fun summarize(transcript: String, marks: List<String>, template: String, lang: String, offlineOnly: Boolean, dict: String): Summary {
        lastError = ""
        if (!offlineOnly && key().isNotBlank()) {
            try {
                return online(transcript, marks, template, lang, dict)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return offline(transcript, marks, lang)
    }

    // ------------------------------------------------------------ online (Groq)
    private fun instructions(template: String, en: Boolean): String {
        val kind = when (template) {
            "client" -> if (en) "a conversation with a client: focus on the client's needs, agreements, prices/terms, objections and next steps"
            else "rozmowę z klientem: skup się na potrzebach klienta, ustaleniach, cenach i warunkach, zastrzeżeniach i kolejnych krokach"
            "shift" -> if (en) "a shift briefing in a production/logistics plant: focus on production issues, safety, staffing, equipment and tasks for the shift"
            else "odprawę zmiany w zakładzie produkcyjno-logistycznym: skup się na problemach produkcji, BHP, obsadzie, sprzęcie i zadaniach na zmianę"
            else -> if (en) "a business meeting: focus on key points, decisions and action items" else "spotkanie biznesowe: skup się na najważniejszych punktach, decyzjach i zadaniach"
        }
        return if (en)
            "You summarize $kind. Answer ONLY with JSON: {\"points\":[...],\"decisions\":[...],\"tasks\":[...],\"questions\":[...]}. " +
                "points: 3-10 most important points, concise. tasks: each as \"Who – what – deadline\" (use \"?\" if unknown). " +
                "questions: open issues. Use speaker names from the transcript. Write in English. Do not invent facts."
        else
            "Podsumowujesz $kind. Odpowiedz WYŁĄCZNIE JSON-em: {\"points\":[...],\"decisions\":[...],\"tasks\":[...],\"questions\":[...]}. " +
                "points: 3–10 najważniejszych punktów, zwięźle. tasks: każde jako „Kto – co – do kiedy” (\"?\" gdy nie wiadomo). " +
                "questions: otwarte kwestie. Używaj imion mówców z transkrypcji. Pisz po polsku. Niczego nie wymyślaj."
    }

    private fun call(system: String, user: String, json: Boolean): String {
        var err = ""
        for (model in MODELS) {
            var attempt = 0
            while (attempt < 4) {
                attempt++
                val c = URL(API).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 20_000
                c.readTimeout = 120_000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer " + key())
                c.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("model", model).put("temperature", 0.2)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                if (json) body.put("response_format", JSONObject().put("type", "json_object"))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
                if (code == 200) {
                    return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                }
                err = "Groq $model: kod $code ${text.take(160)}"
                if (code == 429) {
                    // limit na minutę – odczekaj i spróbuj ponownie
                    val wait = (c.getHeaderField("retry-after")?.toDoubleOrNull() ?: 20.0).coerceIn(2.0, 60.0)
                    Thread.sleep((wait * 1000).toLong())
                    continue
                }
                break // inny błąd (np. model wycofany) – następny model
            }
        }
        throw IllegalStateException(err)
    }

    private fun list(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.opt(it) }.map {
            if (it is JSONObject) listOf("who", "what", "due").mapNotNull { f -> it.optString(f).takeIf { s -> s.isNotBlank() } }.joinToString(" – ")
            else it.toString()
        }.filter { it.isNotBlank() }
    }

    private fun online(transcript: String, marks: List<String>, template: String, lang: String, dict: String): Summary {
        val en = lang == "en"
        val sys = instructions(template, en)
        val extra = buildString {
            if (marks.isNotEmpty()) append((if (en) "Moments marked as IMPORTANT (put them first):\n" else "Momenty oznaczone jako WAŻNE (umieść je na początku):\n") + marks.joinToString("\n") + "\n\n")
            val terms = DictFix.terms(dict)
            if (terms.isNotEmpty()) append((if (en) "Correct spelling of names: " else "Poprawna pisownia nazw: ") + terms.joinToString(", ") + "\n\n")
        }
        // długie spotkania: najpierw streszczenia części, potem całość (limity darmowego planu)
        val limit = 14_000
        val material = if (transcript.length <= limit) transcript else {
            val parts = ArrayList<String>()
            var i = 0
            while (i < transcript.length) {
                var j = (i + limit).coerceAtMost(transcript.length)
                if (j < transcript.length) transcript.lastIndexOf('\n', j).takeIf { it > i + limit / 2 }?.let { j = it }
                parts.add(transcript.substring(i, j)); i = j
            }
            parts.mapIndexed { idx, p ->
                call(if (en) "Summarize this part of a meeting transcript in detailed bullet points, keeping names, numbers, decisions and tasks."
                else "Streść tę część transkrypcji spotkania w szczegółowych punktach, zachowując imiona, liczby, decyzje i zadania.",
                    "Część ${idx + 1}/${parts.size}:\n$p", false)
            }.joinToString("\n\n")
        }
        val res = call(sys, extra + (if (en) "Transcript:\n" else "Transkrypcja:\n") + material, true)
        val o = JSONObject(res.substring(res.indexOf('{').coerceAtLeast(0), res.lastIndexOf('}') + 1))
        return Summary(list(o, "points"), list(o, "decisions"), list(o, "tasks"), list(o, "questions"), "groq")
    }

    // ------------------------------------------------------------ offline (uproszczone)
    private val TASK = Regex("(?i)\\b(zrobi|zrobię|przygotuj|przygotuje|wyśle|wyślę|sprawdzi|sprawdzę|zadzwoni|zadzwonię|zamówi|ustali|dopilnuje|trzeba|musimy|musi|należy|will|need to|has to|should|send|prepare|check|call)\\b")
    private val DEC = Regex("(?i)\\b(ustaliliśmy|ustalamy|decyzja|zdecydowaliśmy|postanowiliśmy|zgoda|akceptujemy|robimy tak|decided|agreed|decision)\\b")
    private val DUE = Regex("(?i)\\b(do (poniedziałku|wtorku|środy|czwartku|piątku|soboty|niedzieli|jutra|końca \\w+)|\\d{1,2}\\.\\d{1,2}|by (monday|tuesday|wednesday|thursday|friday|tomorrow|end of \\w+))\\b")
    private val Q = Regex("\\?\\s*$")

    private fun offline(transcript: String, marks: List<String>, lang: String): Summary {
        val lines = transcript.lines().map { it.trim() }.filter { it.isNotBlank() }
        val tasks = lines.filter { TASK.containsMatchIn(it) && (DUE.containsMatchIn(it) || it.length < 220) }.take(12)
        val decisions = lines.filter { DEC.containsMatchIn(it) }.take(8)
        val questions = lines.filter { Q.containsMatchIn(it) }.take(6)
        // najdłuższe wypowiedzi zwykle niosą najwięcej treści
        val points = (marks + lines.sortedByDescending { it.length }.take(6)).distinct().take(10)
        return Summary(points, decisions, tasks, questions, "offline")
    }

    /** Tekst podsumowania do notatki i e-maila. */
    fun format(s: Summary, title: String, date: String, lang: String, marks: List<String>): String {
        val en = lang == "en"
        return buildString {
            append("# ").append(title).append("\n").append(date).append("\n")
            if (s.by == "offline") append(if (en) "(offline summary – simplified)\n" else "(podsumowanie offline – uproszczone)\n")
            if (marks.isNotEmpty()) {
                append("\n# ").append(if (en) "★ Marked as important" else "★ Oznaczone jako ważne").append("\n")
                marks.forEach { append("• ").append(it).append("\n") }
            }
            fun sec(h: String, l: List<String>) {
                if (l.isEmpty()) return
                append("\n# ").append(h).append("\n")
                l.forEach { append("• ").append(it).append("\n") }
            }
            sec(if (en) "Key points" else "Najważniejsze punkty", s.points)
            sec(if (en) "Decisions" else "Decyzje", s.decisions)
            sec(if (en) "Tasks (who – what – when)" else "Zadania (kto – co – do kiedy)", s.tasks)
            sec(if (en) "Open questions" else "Otwarte kwestie", s.questions)
        }.trim()
    }
}
