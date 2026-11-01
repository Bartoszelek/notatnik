package pl.notatnik

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Wspólne notatki (np. lista zakupów z żoną) przez prywatne repozytorium GitHub „notatki-wspolne”.
 * Każda wspólna notatka to plik notes/<id>.json. Zmiany pozycji list są łączone (kto później, ten wygrywa – osobno dla każdej pozycji).
 */
object Shared {
    const val REPO = "notatki-wspolne"
    private val PERSONAL = listOf("reminder", "repeat", "repDays", "repEvery", "repUnit", "photos", "photoText", "audio", "audioMs",
        "pdf", "pages", "ocr", "thumb", "image", "locked", "pinned", "sharedSha", "syncedAt", "deleted", "archived", "template")

    fun token() = Store.sharedToken.ifBlank { Store.ghToken }
    fun active() = Store.sharedRepo.isNotEmpty() && token().isNotBlank()
    private fun api(m: String, p: String, b: String? = null, raw: Boolean = false) = Sync.api(m, p, b, raw, token())

    /** Tworzy prywatne repozytorium na wspólne notatki (na koncie właściciela). */
    fun createSpace(): String {
        val (uc, ub) = Sync.api("GET", "/user")
        if (uc != 200) throw IOException("Token GitHub jest nieprawidłowy (kod $uc)")
        val user = JSONObject(ub).getString("login")
        val (rc, _) = Sync.api("GET", "/repos/$user/$REPO")
        if (rc == 404) {
            val (cc, cb) = Sync.api("POST", "/user/repos", JSONObject().put("name", REPO).put("private", true)
                .put("description", "Wspólne notatki z aplikacji Notatnik").put("auto_init", true).toString())
            if (cc !in 200..299) throw IOException("Nie mogę utworzyć repozytorium ($cc): " + cb.take(100))
        }
        Store.sharedRepo = "$user/$REPO"
        Store.save()
        return Store.sharedRepo
    }

    /** Plik zaproszenia do wysłania (np. WhatsAppem) – zawiera adres repozytorium i ograniczony token. */
    fun inviteFile(ctx: Context): File {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "Notatnik-wspolne.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("wspolne.json"))
            z.write(JSONObject().put("type", "notatnik-shared").put("repo", Store.sharedRepo).put("token", Store.inviteToken).toString().toByteArray())
            z.closeEntry()
        }
        return f
    }

    fun join(repo: String, token: String) {
        Store.sharedRepo = repo
        Store.sharedToken = token
        if (Store.ghToken.isBlank()) Store.ghToken = token
        Store.save()
    }

    private fun sharedJson(n: Note): JSONObject {
        val o = n.toJson()
        for (k in PERSONAL) o.remove(k)
        return o
    }

    /** Łączy zdalną wersję z lokalną. Zwraca true, gdy lokalnie są zmiany, których nie ma zdalnie. */
    private fun merge(a: Note, b: Note): Boolean {
        var localNewer = a.updated > b.updated
        if (b.updated > a.updated) {
            a.title = b.title
            a.body = b.body
            a.type = b.type
            a.color = b.color
            a.labels.clear(); a.labels.addAll(b.labels)
            a.servings = b.servings
            a.cookTime = b.cookTime
            a.source = b.source
        }
        val mine = a.items.associateBy { it.id }
        val theirs = b.items.associateBy { it.id }
        for (bi in b.items) {
            val ai = mine[bi.id]
            if (ai == null) a.items.add(bi)
            else if (bi.upd > ai.upd) {
                ai.text = bi.text; ai.done = bi.done; ai.del = bi.del; ai.upd = bi.upd
            } else if (ai.upd > bi.upd) localNewer = true
        }
        if (a.items.any { it.id !in theirs }) localNewer = true
        if (b.updated > a.updated) {
            val order = b.items.map { it.id }
            a.items.sortBy { val i = order.indexOf(it.id); if (i < 0) Int.MAX_VALUE else i }
        }
        a.updated = maxOf(a.updated, b.updated)
        return localNewer
    }

    /** Synchronizacja w obie strony. Zwraca liczbę zmian przyjętych z serwera. */
    @Synchronized
    fun sync(): Int {
        if (!active()) return 0
        val repo = Store.sharedRepo
        val (lc, lb) = api("GET", "/repos/$repo/contents/notes")
        val remote = HashMap<String, String>()
        if (lc == 200) {
            val a = JSONArray(lb)
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val name = o.optString("name")
                if (name.endsWith(".json")) remote[name.removeSuffix(".json")] = o.optString("sha")
            }
        } else if (lc == 401 || lc == 403) throw IOException("Brak dostępu do wspólnych notatek (kod $lc) – sprawdź token")
        else if (lc != 404) throw IOException("Wspólne notatki: GitHub odpowiedział kodem $lc")
        val now = System.currentTimeMillis()
        var changes = 0
        val needUpload = HashSet<String>()
        for ((id, sha) in remote) {
            val local = Store.note(id)
            if (local != null && local.sharedSha == sha) continue
            val (gc, gb) = api("GET", "/repos/$repo/contents/notes/$id.json", raw = true)
            if (gc != 200) continue
            val o = try {
                JSONObject(gb)
            } catch (e: Exception) {
                continue
            }
            if (o.optBoolean("gone")) {
                if (local != null && local.shared) {
                    local.shared = false
                    if (local.deleted == 0L) local.deleted = now
                    changes++
                }
                continue
            }
            val rn = Note.fromJson(o)
            rn.shared = true
            if (local == null) {
                rn.sharedSha = sha
                rn.syncedAt = rn.updated
                Store.notes.add(rn)
                changes++
            } else if (local.deleted == 0L) {
                val mineNewer = merge(local, rn)
                local.shared = true
                local.sharedSha = sha
                if (mineNewer) needUpload.add(id) else local.syncedAt = local.updated
                changes++
            }
        }
        for (n in Store.notes.filter { it.shared }) {
            val gone = n.deleted > 0
            val known = remote.containsKey(n.id)
            if (!gone && known && n.updated <= n.syncedAt && n.id !in needUpload) continue
            if (gone && !known) {
                n.shared = false
                continue
            }
            val content = if (gone) JSONObject().put("gone", true) else sharedJson(n)
            val req = JSONObject().put("message", if (gone) "Usunięto" else "Zmiana")
                .put("content", Base64.encodeToString(content.toString().toByteArray(), Base64.NO_WRAP))
            val sha = remote[n.id] ?: n.sharedSha.ifEmpty { null }
            if (sha != null) req.put("sha", sha)
            val (pc, pb) = api("PUT", "/repos/$repo/contents/notes/${n.id}.json", req.toString())
            if (pc in 200..299) {
                n.sharedSha = JSONObject(pb).optJSONObject("content")?.optString("sha") ?: ""
                n.syncedAt = n.updated
                if (gone) n.shared = false
            }
        }
        Store.sharedLast = now
        Store.save()
        return changes
    }

    /** Przestań współdzielić: notatka zostaje u Ciebie, u drugiej osoby trafia do kosza. */
    fun unshare(n: Note) {
        val repo = Store.sharedRepo
        val sha = n.sharedSha
        n.shared = false
        Store.save()
        if (!active() || sha.isEmpty()) return
        val req = JSONObject().put("message", "Koniec współdzielenia").put("sha", sha)
            .put("content", Base64.encodeToString(JSONObject().put("gone", true).toString().toByteArray(), Base64.NO_WRAP))
        api("PUT", "/repos/$repo/contents/notes/${n.id}.json", req.toString())
    }

    /** Cykliczna synchronizacja w tle (co ok. 15 minut), żeby widżet był aktualny. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 4242, Intent(ctx, SharedSyncReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (active()) am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 60_000L, AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi)
    }

    /** Synchronizacja w tle z odświeżeniem widżetów. */
    fun syncAsync(ctx: Context, done: (() -> Unit)? = null) {
        if (!active()) return
        val app = ctx.applicationContext
        Thread {
            try {
                sync()
            } catch (e: Exception) {
            }
            NotesWidget.updateAll(app)
            done?.invoke()
        }.start()
    }
}

class SharedSyncReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val pending = goAsync()
        Shared.syncAsync(ctx) { pending.finish() }
    }
}
