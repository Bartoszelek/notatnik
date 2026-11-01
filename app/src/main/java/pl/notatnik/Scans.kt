package pl.notatnik

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Skany: odczyt tekstu, nazwa z treści, zapis PDF (w aplikacji i w Dokumenty/Skany), import starych skanów. */
object Scans {
    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val KINDS = listOf(
        "faktura" to "Faktura", "paragon" to "Paragon", "rachunek" to "Rachunek", "umowa" to "Umowa",
        "polisa" to "Polisa", "protokol" to "Protokół", "zaswiadczenie" to "Zaświadczenie", "wezwanie" to "Wezwanie",
        "deklaracja" to "Deklaracja", "potwierdzenie" to "Potwierdzenie", "zamowienie" to "Zamówienie",
        "oferta" to "Oferta", "rezerwacja" to "Rezerwacja", "recepta" to "Recepta", "skierowanie" to "Skierowanie"
    )

    fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "Skan" }

    fun bitmap(file: File, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun recognize(bm: Bitmap): String = try {
        Tasks.await(ocr.process(InputImage.fromBitmap(bm, 0))).text
    } catch (e: Exception) {
        ""
    }

    /** Proponowana nazwa: rodzaj dokumentu + nadawca + data, np. „Faktura ORLEN 23.10.2026”. */
    fun suggestName(text: String): String {
        val n = Store.norm(text)
        val kind = KINDS.firstOrNull { n.contains(it.first) }?.second
        val lines = text.lines().map { it.trim() }.filter { it.length in 3..40 }
        val org = lines.firstOrNull { l ->
            val letters = l.count { it.isLetter() }
            letters >= 3 && (l.contains("sp. z o.o", true) || l.contains(" S.A", true) || l.contains("S.C.", true) ||
                (letters >= 4 && l.filter { it.isLetter() }.all { it.isUpperCase() }))
        }?.replace(Regex("(?i)\\s*sp\\.? ?z ?o\\.?o\\.?.*"), "")?.replace(Regex("(?i)\\s*s\\.a\\..*"), "")?.trim()
        val date = Regex("\\b(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](20\\d{2})\\b").find(text)?.let {
            "%02d.%02d.%s".format(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3])
        } ?: Regex("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b").find(text)?.let { "${it.groupValues[3]}.${it.groupValues[2]}.${it.groupValues[1]}" }
        ?: SimpleDateFormat("dd.MM.yyyy", Locale("pl")).format(Date())
        val head = listOfNotNull(kind, org?.take(30)).joinToString(" ")
        return safe(if (head.isEmpty()) "Skan $date" else "$head $date")
    }

    private fun copy(ctx: Context, src: Uri, dest: File): Boolean = try {
        ctx.contentResolver.openInputStream(src)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } } != null
    } catch (e: Exception) {
        false
    }

    private fun saveThumb(bm: Bitmap, id: String): String {
        val f = File(Store.dir("thumbs"), "$id.jpg")
        val sc = 420f / maxOf(bm.width, bm.height)
        val t = if (sc < 1f) Bitmap.createScaledBitmap(bm, (bm.width * sc).toInt(), (bm.height * sc).toInt(), true) else bm
        f.outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return f.path
    }

    /** Kopia w Dokumenty/Skany (widoczna w Moich plikach). */
    fun publish(ctx: Context, file: File, name: String): Uri? = try {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Skany")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        uri
    } catch (e: Exception) {
        null
    }

    private fun publishJpg(ctx: Context, src: Uri, name: String) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Skany")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { o -> ctx.contentResolver.openInputStream(src)?.use { it.copyTo(o) } }
        } catch (e: Exception) {
        }
    }

    /** Wynik skanera: PDF + zdjęcia stron. Tworzy notatkę (bez zapisu do listy). */
    fun fromScanner(ctx: Context, pdf: Uri, pageCount: Int, jpgs: List<Uri>, status: (String) -> Unit): Note {
        val n = Note(type = "scan")
        val file = File(Store.dir("scans"), "${n.id}.pdf")
        copy(ctx, pdf, file)
        val texts = ArrayList<String>()
        jpgs.take(30).forEachIndexed { k, u ->
            status("Odczytuję tekst… strona ${k + 1}/${jpgs.size}")
            val tmp = File(ctx.cacheDir, "page.jpg")
            if (copy(ctx, u, tmp)) {
                val bm = bitmap(tmp, 1800)
                if (bm != null) {
                    if (k == 0) n.thumb = saveThumb(bm, n.id)
                    texts.add(recognize(bm))
                }
            }
        }
        if (n.thumb.isEmpty()) render(file, 0, 600)?.let { n.thumb = saveThumb(it, n.id) }
        n.ocr = texts.joinToString("\n\n")
        n.title = suggestName(n.ocr)
        n.pdf = file.path
        n.pages = pageCount
        status("Zapisuję w Dokumenty/Skany…")
        publish(ctx, file, n.title)
        if (Store.scanJpg) jpgs.forEachIndexed { k, u -> publishJpg(ctx, u, n.title + " " + (k + 1)) }
        return n
    }

    /** Renderuje stronę PDF do obrazka. */
    fun render(file: File, page: Int, width: Int): Bitmap? {
        return try {
            renderInner(file, page, width)
        } catch (e: Exception) {
            null
        }
    }

    private fun renderInner(file: File, page: Int, width: Int): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (page >= r.pageCount) return null
                return r.openPage(page).use { p ->
                    val h = (width.toFloat() * p.height / p.width).toInt()
                    val bm = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bm.eraseColor(Color.WHITE)
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bm
                }
            }
        }
    }

    fun pageCount(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    } catch (e: Exception) {
        0
    }

    /** Import PDF-ów z wybranego folderu (np. Dokumenty/Skany ze starego skanera). */
    fun importFolder(ctx: Context, tree: Uri, status: (String) -> Unit): Int {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val found = ArrayList<Pair<Uri, String>>()
        ctx.contentResolver.query(
            q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: ""
                if (name.endsWith(".pdf", true)) found.add(Pair(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name))
            }
        }
        var n = 0
        for ((k, pair) in found.withIndex()) {
            val title = pair.second.removeSuffix(".pdf").removeSuffix(".PDF")
            if (Store.notes.any { it.type == "scan" && it.title == title }) continue
            status("Importuję skany… ${k + 1}/${found.size}")
            val note = Note(type = "scan", title = title)
            val file = File(Store.dir("scans"), "${note.id}.pdf")
            if (!copy(ctx, pair.first, file)) continue
            note.pdf = file.path
            note.pages = pageCount(file)
            val texts = ArrayList<String>()
            for (p in 0 until minOf(note.pages, 10)) {
                val bm = render(file, p, 1400) ?: continue
                if (p == 0) note.thumb = saveThumb(bm, note.id)
                texts.add(recognize(bm))
            }
            note.ocr = texts.joinToString("\n\n")
            note.created = System.currentTimeMillis()
            Store.notes.add(note)
            n++
        }
        Store.save()
        return n
    }
}
