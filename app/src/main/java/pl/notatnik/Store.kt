package pl.notatnik

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.UUID

/** Pozycja listy. id/upd/del służą do łączenia zmian na wspólnych listach. */
class Item(
    var text: String,
    var done: Boolean = false,
    var id: String = UUID.randomUUID().toString().take(10),
    var upd: Long = System.currentTimeMillis(),
    var del: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("t", text).put("d", done).put("i", id).put("u", upd).put("x", del)
    fun touch() {
        upd = System.currentTimeMillis()
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(
            o.optString("t"), o.optBoolean("d"), o.optString("i").ifEmpty { UUID.randomUUID().toString().take(10) },
            o.optLong("u", 0L), o.optBoolean("x")
        )
    }
}

/** Wpis w notatniku. type: text, list, audio, scan. */
class Note(
    var id: String = UUID.randomUUID().toString().take(12),
    var type: String = "text",
    var title: String = "",
    var body: String = "",
    val items: MutableList<Item> = mutableListOf(),
    var color: Int = 0,
    var pinned: Boolean = false,
    var archived: Boolean = false,
    val labels: MutableList<String> = mutableListOf(),
    var reminder: Long = 0,
    var created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
    var audio: String = "",
    var audioMs: Long = 0,
    var pdf: String = "",
    var pages: Int = 0,
    var ocr: String = "",
    var thumb: String = "",
    var image: String = "",
    var repeat: String = "",
    var repDays: Int = 0,
    var repEvery: Int = 1,
    var repUnit: String = "d",
    val photos: MutableList<String> = mutableListOf(),
    var photoText: String = "",
    var deleted: Long = 0,
    var shared: Boolean = false,
    var sharedSha: String = "",
    var syncedAt: Long = 0,
    var template: Boolean = false,
    var locked: Boolean = false,
    var servings: Int = 0,
    var cookTime: String = "",
    var source: String = "",
    var tips: String = "",
    var meta: String = ""
) {
    val isList get() = type == "list"
    val isRecipe get() = type == "recipe"
    val isScanArea get() = type == "scan" || type == "card"

    /** Pozycje bez usuniętych (usunięte zostają jako „ślad” na wspólnych listach). */
    val live: List<Item> get() = items.filter { !it.del }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("title", title).put("body", body)
        .put("items", JSONArray(items.map { it.toJson() })).put("color", color).put("pinned", pinned)
        .put("archived", archived).put("labels", JSONArray(labels)).put("reminder", reminder)
        .put("created", created).put("updated", updated).put("audio", audio).put("audioMs", audioMs)
        .put("pdf", pdf).put("pages", pages).put("ocr", ocr).put("thumb", thumb).put("image", image)
        .put("repeat", repeat).put("repDays", repDays).put("repEvery", repEvery).put("repUnit", repUnit)
        .put("photos", JSONArray(photos)).put("photoText", photoText).put("deleted", deleted).put("shared", shared)
        .put("sharedSha", sharedSha).put("syncedAt", syncedAt).put("template", template).put("locked", locked)
        .put("servings", servings).put("cookTime", cookTime).put("source", source).put("tips", tips).put("meta", meta)

    /** Tekst do wyszukiwania. */
    fun searchText(): String = if (locked) title else
        title + " " + body + " " + live.joinToString(" ") { it.text } + " " + ocr + " " + photoText + " " + tips + " " + labels.joinToString(" ")

    companion object {
        fun fromJson(o: JSONObject): Note {
            val n = Note(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString().take(12) },
                type = o.optString("type", "text"), title = o.optString("title"), body = o.optString("body"),
                color = o.optInt("color"), pinned = o.optBoolean("pinned"), archived = o.optBoolean("archived"),
                reminder = o.optLong("reminder"), created = o.optLong("created", System.currentTimeMillis()),
                updated = o.optLong("updated", System.currentTimeMillis()), audio = o.optString("audio"),
                audioMs = o.optLong("audioMs"), pdf = o.optString("pdf"), pages = o.optInt("pages"),
                ocr = o.optString("ocr"), thumb = o.optString("thumb"), image = o.optString("image"),
                repeat = o.optString("repeat"), repDays = o.optInt("repDays"), repEvery = o.optInt("repEvery", 1),
                repUnit = o.optString("repUnit", "d"), photoText = o.optString("photoText"), deleted = o.optLong("deleted"),
                shared = o.optBoolean("shared"), sharedSha = o.optString("sharedSha"), syncedAt = o.optLong("syncedAt"),
                template = o.optBoolean("template"), locked = o.optBoolean("locked"), servings = o.optInt("servings"),
                cookTime = o.optString("cookTime"), source = o.optString("source"), tips = o.optString("tips"),
                meta = o.optString("meta")
            )
            o.optJSONArray("photos")?.let { a -> for (k in 0 until a.length()) n.photos.add(a.getString(k)) }
            if (n.image.isNotEmpty() && n.type != "scan" && n.photos.isEmpty()) {
                n.photos.add(n.image)
                n.image = ""
            }
            o.optJSONArray("items")?.let { a -> for (k in 0 until a.length()) n.items.add(Item.fromJson(a.getJSONObject(k))) }
            o.optJSONArray("labels")?.let { a -> for (k in 0 until a.length()) n.labels.add(a.getString(k)) }
            return n
        }
    }
}

/** Ustawienia jednego widżetu z notatkami. */
class WidgetCfg(
    var notes: MutableList<String> = mutableListOf(),
    var current: Int = 0,
    var alpha: Int = 70,
    var bg: String = "dark",
    var text: Int = 15,
    var tabs: Boolean = true,
    var title: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("notes", JSONArray(notes)).put("current", current).put("alpha", alpha)
        .put("bg", bg).put("text", text).put("tabs", tabs).put("title", title)

    companion object {
        fun fromJson(o: JSONObject): WidgetCfg {
            val w = WidgetCfg(
                current = o.optInt("current"), alpha = o.optInt("alpha", 70), bg = o.optString("bg", "dark"),
                text = o.optInt("text", 15), tabs = o.optBoolean("tabs", true), title = o.optBoolean("title", true)
            )
            o.optJSONArray("notes")?.let { a -> for (k in 0 until a.length()) w.notes.add(a.getString(k)) }
            return w
        }
    }
}

object Store {
    private var dir: File? = null
    val notes = mutableListOf<Note>()
    val widgets = HashMap<Int, WidgetCfg>()

    var eink = false
    var invert = false
    var theme = "indigo"
    var mode = "system"
    var layout = "grid"
    var ghToken = ""
    var ghRepo = "notatki-kopia"
    var ghAuto = true
    var ghLast = 0L
    var driveUri = ""
    var driveAuto = true
    var driveLast = 0L
    var changed = 0L
    var scanJpg = false
    var importedScans = false
    var sharedRepo = ""
    var sharedToken = ""
    var inviteToken = ""
    var sharedLast = 0L
    var unlockedUntil = 0L

    /** Kolory notatek (jak w Keep): jasne i ciemne odpowiedniki. */
    val COLORS_LIGHT = intArrayOf(
        0, 0xFFFAAFA8.toInt(), 0xFFF39F76.toInt(), 0xFFFFF8B8.toInt(), 0xFFE2F6D3.toInt(), 0xFFB4DDD3.toInt(),
        0xFFD4E4ED.toInt(), 0xFFAECCDC.toInt(), 0xFFD3BFDB.toInt(), 0xFFF6E2DD.toInt(), 0xFFE9E3D4.toInt(), 0xFFEFEFF1.toInt()
    )
    val COLORS_DARK = intArrayOf(
        0, 0xFF77172E.toInt(), 0xFF692B17.toInt(), 0xFF7C4A03.toInt(), 0xFF264D3B.toInt(), 0xFF0C625D.toInt(),
        0xFF256377.toInt(), 0xFF284255.toInt(), 0xFF472E5B.toInt(), 0xFF6C394F.toInt(), 0xFF4B443A.toInt(), 0xFF232427.toInt()
    )
    val COLOR_NAMES = listOf("Domyślny", "Czerwony", "Pomarańczowy", "Żółty", "Zielony", "Morski", "Niebieski", "Granatowy", "Fioletowy", "Różowy", "Brązowy", "Szary")

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        dir = ctx.applicationContext.filesDir
        load()
    }

    fun dir(name: String): File = File(dir, name).apply { mkdirs() }

    fun note(id: String?): Note? = notes.firstOrNull { it.id == id }

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace("ł", "l")

    /** Obszar wpisu: notes, recipes, scans albo recordings. */
    fun area(n: Note) = when {
        n.isRecipe -> "recipes"
        n.isScanArea -> "scans"
        n.type == "meeting" || n.type == "audio" -> "recordings"
        else -> "notes"
    }

    fun labels(area: String = "notes"): List<String> = notes.filter { it.deleted == 0L && area(it) == area && !it.template }
        .flatMap { it.labels }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /** Usunięcie pozycji listy (na wspólnej liście zostaje ślad, żeby zniknęła też u drugiej osoby). */
    fun removeItem(n: Note, it: Item) {
        if (n.shared) {
            it.del = true
            it.touch()
        } else n.items.remove(it)
        touch(n)
    }

    /** Kosz: notatki usunięte ponad 30 dni temu znikają na zawsze. */
    fun purgeTrash() {
        val limit = System.currentTimeMillis() - 30L * 86_400_000L
        val old = notes.filter { it.deleted in 1 until limit }
        for (n in old) {
            (listOf(n.audio, n.pdf, n.thumb, n.image) + n.photos).filter { it.isNotEmpty() }.forEach { File(it).delete() }
            notes.remove(n)
        }
        if (old.isNotEmpty()) save()
    }

    fun touch(n: Note) {
        n.updated = System.currentTimeMillis()
        changed = n.updated
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        o.put("eink", eink).put("invert", invert).put("theme", theme).put("mode", mode).put("layout", layout)
            .put("ghToken", ghToken).put("ghRepo", ghRepo).put("ghAuto", ghAuto).put("ghLast", ghLast)
            .put("driveUri", driveUri).put("driveAuto", driveAuto).put("driveLast", driveLast).put("changed", changed)
            .put("scanJpg", scanJpg).put("importedScans", importedScans).put("sharedRepo", sharedRepo)
            .put("sharedToken", sharedToken).put("inviteToken", inviteToken).put("sharedLast", sharedLast)
        o.put("notes", JSONArray(notes.map { it.toJson() }))
        val w = JSONObject()
        for ((k, v) in widgets) w.put(k.toString(), v.toJson())
        o.put("widgets", w)
        try {
            val t = File(dir, "data.json.tmp")
            t.writeText(o.toString())
            if (!t.renameTo(File(dir, "data.json"))) {
                File(dir, "data.json").writeText(o.toString())
                t.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun load() {
        try {
            val f = File(dir, "data.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            eink = o.optBoolean("eink")
            invert = o.optBoolean("invert")
            theme = o.optString("theme", "indigo")
            mode = o.optString("mode", "system")
            layout = o.optString("layout", "grid")
            ghToken = o.optString("ghToken")
            ghRepo = o.optString("ghRepo", "notatki-kopia")
            ghAuto = o.optBoolean("ghAuto", true)
            ghLast = o.optLong("ghLast")
            driveUri = o.optString("driveUri")
            driveAuto = o.optBoolean("driveAuto", true)
            driveLast = o.optLong("driveLast")
            changed = o.optLong("changed")
            scanJpg = o.optBoolean("scanJpg")
            importedScans = o.optBoolean("importedScans")
            sharedRepo = o.optString("sharedRepo")
            sharedToken = o.optString("sharedToken")
            inviteToken = o.optString("inviteToken")
            sharedLast = o.optLong("sharedLast")
            notes.clear()
            o.optJSONArray("notes")?.let { a -> for (k in 0 until a.length()) notes.add(Note.fromJson(a.getJSONObject(k))) }
            widgets.clear()
            o.optJSONObject("widgets")?.let { w ->
                for (k in w.keys()) k.toIntOrNull()?.let { id -> widgets[id] = WidgetCfg.fromJson(w.getJSONObject(k)) }
            }
        } catch (e: Exception) {
        }
    }

    /** Dołącza notatki z kopii (bez duplikatów po id – nowsza wersja wygrywa). Zwraca liczbę dodanych/zmienionych. */
    fun merge(list: List<Note>): Int {
        var n = 0
        for (x in list) {
            val cur = note(x.id)
            if (cur == null) {
                notes.add(x); n++
            } else if (x.updated > cur.updated) {
                notes[notes.indexOf(cur)] = x; n++
            }
        }
        if (n > 0) save()
        return n
    }
}
