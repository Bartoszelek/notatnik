package pl.notatnik

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Kopie zapasowe (ZIP, GitHub, Dysk Google) i import z Google Keep. */
object Sync {
    private val FILE_FIELDS = listOf("audio", "pdf", "thumb", "image")

    // ------------------------------------------------------------ ZIP
    fun zip(out: OutputStream, media: Boolean) {
        ZipOutputStream(out).use { z ->
            val arr = JSONArray()
            val used = HashSet<String>()
            for (n in Store.notes) {
                val o = n.toJson()
                val ph = JSONArray()
                for (p in n.photos) {
                    val f = File(p)
                    if (media && f.exists()) {
                        val entry = "files/photos/" + f.name
                        if (used.add(entry)) {
                            z.putNextEntry(ZipEntry(entry))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                        ph.put(entry)
                    }
                }
                o.put("photos", ph)
                for (k in FILE_FIELDS) {
                    val p = o.optString(k)
                    val f = File(p)
                    if (media && p.isNotEmpty() && f.exists()) {
                        val entry = "files/" + f.parentFile?.name + "/" + f.name
                        if (used.add(entry)) {
                            z.putNextEntry(ZipEntry(entry))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                        o.put(k, entry)
                    } else if (!media) o.put(k, "")
                }
                arr.put(o)
            }
            z.putNextEntry(ZipEntry("notatnik.json"))
            z.write(JSONObject().put("version", 1).put("notes", arr).toString().toByteArray())
            z.closeEntry()
        }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) {
                    val b = ByteArrayOutputStream()
                    z.copyTo(b)
                    files[e.name] = b.toByteArray()
                }
            }
        }
        return files
    }

    private fun parseNotes(json: String, files: Map<String, ByteArray>): List<Note> {
        val a = JSONObject(json).optJSONArray("notes") ?: return emptyList()
        val out = ArrayList<Note>()
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            for (f in FILE_FIELDS) {
                val p = o.optString(f)
                if (p.startsWith("files/")) {
                    val data = files[p]
                    if (data != null) {
                        val dest = File(Store.dir(p.split("/").getOrElse(1) { "files" }), File(p).name)
                        dest.writeBytes(data)
                        o.put(f, dest.path)
                    } else o.put(f, "")
                } else if (p.isNotEmpty() && !File(p).exists()) o.put(f, "")
            }
            val ph = JSONArray()
            o.optJSONArray("photos")?.let { a2 ->
                for (j in 0 until a2.length()) {
                    val p = a2.optString(j)
                    val data = files[p]
                    if (p.startsWith("files/") && data != null) {
                        val dest = File(Store.dir("photos"), File(p).name)
                        dest.writeBytes(data)
                        ph.put(dest.path)
                    } else if (File(p).exists()) ph.put(p)
                }
            }
            o.put("photos", ph)
            out.add(Note.fromJson(o))
        }
        return out
    }

    fun restoreZip(ctx: Context, uri: Uri): Int {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Nie mogę odczytać pliku")
        val files = readZip(bytes)
        val json = files["notatnik.json"] ?: throw IOException("To nie jest kopia Notatnika")
        return Store.merge(parseNotes(String(json, Charsets.UTF_8), files))
    }

    // ------------------------------------------------------------ Dysk Google (plik wybrany przez użytkownika)
    fun driveBackup(ctx: Context) {
        if (Store.driveUri.isEmpty()) throw IOException("Nie wybrano pliku na Dysku Google")
        val out = ctx.contentResolver.openOutputStream(Uri.parse(Store.driveUri), "wt") ?: throw IOException("Brak dostępu do pliku na Dysku")
        out.use { zip(it, true) }
        Store.driveLast = System.currentTimeMillis()
        Store.save()
    }

    fun driveRestore(ctx: Context): Int {
        if (Store.driveUri.isEmpty()) throw IOException("Nie wybrano pliku na Dysku Google")
        return restoreZip(ctx, Uri.parse(Store.driveUri))
    }

    // ------------------------------------------------------------ GitHub (prywatne repozytorium)
    fun api(method: String, path: String, body: String? = null, raw: Boolean = false, token: String = Store.ghToken): Pair<Int, String> {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Notatnik")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = try {
            (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        } catch (e: Exception) {
            ""
        }
        return Pair(code, text)
    }

    private fun login(): String {
        val (code, body) = api("GET", "/user")
        if (code == 401) throw IOException("Token GitHub jest nieprawidłowy")
        if (code != 200) throw IOException("GitHub odpowiedział kodem $code")
        return JSONObject(body).getString("login")
    }

    fun githubBackup(): String {
        if (Store.ghToken.isBlank()) throw IOException("Najpierw wklej token GitHub")
        val user = login()
        val repo = Store.ghRepo.ifBlank { "notatki-kopia" }
        val (rc, _) = api("GET", "/repos/$user/$repo")
        if (rc == 404) {
            val (cc, cb) = api("POST", "/user/repos", JSONObject().put("name", repo).put("private", true)
                .put("description", "Kopia notatek z aplikacji Notatnik").put("auto_init", true).toString())
            if (cc !in 200..299) throw IOException("Nie mogę utworzyć repozytorium ($cc): " + cb.take(120))
        }
        val path = "/repos/$user/$repo/contents/notatnik.json"
        val (gc, gb) = api("GET", path)
        val sha = if (gc == 200) JSONObject(gb).optString("sha") else ""
        val bos = ByteArrayOutputStream()
        val arr = JSONArray()
        for (n in Store.notes) {
            val o = n.toJson()
            for (k in FILE_FIELDS) o.put(k, "")
            o.put("photos", JSONArray())
            arr.put(o)
        }
        bos.write(JSONObject().put("version", 1).put("notes", arr).toString(1).toByteArray())
        val content = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val req = JSONObject().put("message", "Kopia notatek").put("content", content)
        if (sha.isNotEmpty()) req.put("sha", sha)
        val (pc, pb) = api("PUT", path, req.toString())
        if (pc !in 200..299) throw IOException("Zapis na GitHub nieudany ($pc): " + pb.take(120))
        Store.ghLast = System.currentTimeMillis()
        Store.save()
        return "$user/$repo"
    }

    fun githubRestore(): Int {
        if (Store.ghToken.isBlank()) throw IOException("Najpierw wklej token GitHub")
        val user = login()
        val (code, body) = api("GET", "/repos/$user/${Store.ghRepo}/contents/notatnik.json", raw = true)
        if (code == 404) throw IOException("Na GitHubie nie ma jeszcze kopii")
        if (code != 200) throw IOException("GitHub odpowiedział kodem $code")
        return Store.merge(parseNotes(body, emptyMap()))
    }

    /** Automatyczne kopie po zmianach (wywoływać w tle). */
    fun auto(ctx: Context) {
        val day = 6 * 3600_000L
        val now = System.currentTimeMillis()
        if (Store.ghAuto && Store.ghToken.isNotBlank() && Store.changed > Store.ghLast) {
            try {
                githubBackup()
            } catch (e: Exception) {
            }
        }
        if (Store.driveAuto && Store.driveUri.isNotEmpty() && Store.changed > Store.driveLast && now - Store.driveLast > day / 6) {
            try {
                driveBackup(ctx)
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ Google Keep (eksport z Google Takeout)
    private val KEEP_COLORS = listOf("DEFAULT", "RED", "ORANGE", "YELLOW", "GREEN", "TEAL", "BLUE", "CERULEAN", "PURPLE", "PINK", "BROWN", "GRAY")

    fun importKeep(ctx: Context, uri: Uri): Int {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Nie mogę odczytać pliku")
        val files = readZip(bytes)
        val byName = files.mapKeys { File(it.key).name }
        var n = 0
        for ((name, data) in files) {
            if (!name.endsWith(".json") || !name.contains("Keep/")) continue
            val o = try {
                JSONObject(String(data, Charsets.UTF_8))
            } catch (e: Exception) {
                continue
            }
            if (o.optBoolean("isTrashed")) continue
            if (!o.has("textContent") && !o.has("listContent") && !o.has("title")) continue
            val note = Note(type = if (o.has("listContent")) "list" else "text")
            note.title = o.optString("title")
            note.body = o.optString("textContent")
            o.optJSONArray("listContent")?.let { a ->
                for (k in 0 until a.length()) {
                    val it = a.getJSONObject(k)
                    note.items.add(Item(it.optString("text"), it.optBoolean("isChecked")))
                }
            }
            note.pinned = o.optBoolean("isPinned")
            note.archived = o.optBoolean("isArchived")
            o.optJSONArray("labels")?.let { a -> for (k in 0 until a.length()) note.labels.add(a.getJSONObject(k).optString("name")) }
            note.color = KEEP_COLORS.indexOf(o.optString("color", "DEFAULT")).coerceAtLeast(0)
            o.optLong("createdTimestampUsec").takeIf { it > 0 }?.let { note.created = it / 1000 }
            o.optLong("userEditedTimestampUsec").takeIf { it > 0 }?.let { note.updated = it / 1000 }
            o.optJSONArray("attachments")?.let { a ->
                for (k in 0 until a.length()) {
                    val att = a.getJSONObject(k)
                    if (!att.optString("mimetype").startsWith("image")) continue
                    val img = byName[File(att.optString("filePath")).name] ?: continue
                    val dest = File(Store.dir("images"), note.id + "_" + File(att.optString("filePath")).name)
                    dest.writeBytes(img)
                    note.photos.add(dest.path)
                }
            }
            val dup = Store.notes.any { it.title == note.title && it.body == note.body && it.created == note.created }
            if (dup) continue
            Store.notes.add(note)
            n++
        }
        Store.save()
        return n
    }
}
