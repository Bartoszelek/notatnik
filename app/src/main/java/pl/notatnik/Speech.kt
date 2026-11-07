package pl.notatnik

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Modele do pobrania przy pierwszym użyciu (zapisywane w pamięci aplikacji). */
object Models {
    class Spec(val name: String, val urls: List<String>, val approxMb: Int)

    private const val HF = "https://huggingface.co/csukuangfj"

    fun whisper(size: String): List<Spec> {
        val s = if (size == "medium") "medium" else "small"
        return listOf(
            Spec("$s-encoder.int8.onnx", listOf("$HF/sherpa-onnx-whisper-$s/resolve/main/$s-encoder.int8.onnx"), if (s == "small") 112 else 320),
            Spec("$s-decoder.int8.onnx", listOf("$HF/sherpa-onnx-whisper-$s/resolve/main/$s-decoder.int8.onnx"), if (s == "small") 262 else 650),
            Spec("$s-tokens.txt", listOf("$HF/sherpa-onnx-whisper-$s/resolve/main/$s-tokens.txt"), 1)
        )
    }

    val vad = Spec("silero_vad.onnx", listOf(
        "$HF/vad/resolve/main/silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    ), 2)

    val speaker = Spec("speaker.onnx", listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    ), 30)

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, s: Spec) = File(dir(c), s.name)

    fun all(size: String) = whisper(size) + listOf(vad, speaker)

    fun ready(c: Context, size: String) = all(size).all { file(c, it).let { f -> f.exists() && f.length() > 0 } }

    fun totalMb(size: String) = all(size).sumOf { it.approxMb }

    /** Pobiera brakujące modele; progress(0..100, opis). */
    fun ensure(c: Context, size: String, progress: (Int, String) -> Unit) {
        val todo = all(size).filter { !file(c, it).exists() || file(c, it).length() == 0L }
        val total = todo.sumOf { it.approxMb }.coerceAtLeast(1)
        var done = 0
        for (s in todo) {
            var ok = false
            var err = ""
            for (u in s.urls) {
                try {
                    download(u, file(c, s)) { mb -> progress(((done + mb.coerceAtMost(s.approxMb)) * 100 / total).coerceIn(0, 99), "Pobieram model ${s.name}") }
                    ok = true; break
                } catch (e: Exception) {
                    err = e.message ?: ""
                }
            }
            if (!ok) throw IllegalStateException("Nie mogę pobrać modelu ${s.name}: $err")
            done += s.approxMb
        }
        progress(100, "Modele gotowe")
    }

    private fun download(url: String, dest: File, mb: (Int) -> Unit) {
        val tmp = File(dest.path + ".part")
        var u = url
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Notatnik")
            val code = c.responseCode
            if (code in 300..399 && hops < 8) {
                u = URL(URL(u), c.getHeaderField("Location")).toString(); hops++; c.disconnect(); continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        c.inputStream.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var n: Int
                var total = 0L
                var lastMb = -1
                while (inp.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n)
                    total += n
                    val m = (total / 1_048_576).toInt()
                    if (m != lastMb) { lastMb = m; mb(m) }
                }
            }
        }
        if (tmp.length() < 1000) throw IllegalStateException("Pusty plik")
        tmp.renameTo(dest)
    }
}

/** Dekodowanie dowolnego nagrania do WAV 16 kHz mono oraz kompresja WAV → M4A. */
object AudioConv {
    /** Dowolny format (m4a, mp3, ogg, opus, wav…) → WAV 16 kHz mono 16 bit. */
    fun toWav16k(src: String, dst: File, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) throw IllegalStateException("Brak ścieżki dźwiękowej")
        ex.selectTrack(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var written = 0L
        var pos = 0.0          // pozycja w próbkach wejściowych dla resamplera
        var prev = 0f
        var inIndex = 0L       // licznik próbek wejściowych (mono)
        RandomAccessFile(dst, "rw").use { out ->
            out.setLength(0)
            out.write(Wav.header(0))
            val ob = ByteArray(8192)
            var op = 0
            fun put(v: Float) {
                val s = (v.coerceIn(-1f, 1f) * 32767).toInt()
                ob[op++] = (s and 0xff).toByte(); ob[op++] = ((s shr 8) and 0xff).toByte()
                if (op >= ob.size) { out.write(ob, 0, op); written += op; op = 0 }
            }
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) progress((ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 100))
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val bb = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    bb.position(info.offset); bb.limit(info.offset + info.size)
                    val sb = bb.asShortBuffer()
                    val frames = sb.remaining() / ch.coerceAtLeast(1)
                    val step = inRate.toDouble() / Wav.RATE
                    for (k in 0 until frames) {
                        var sum = 0f
                        for (cIdx in 0 until ch) sum += sb.get(k * ch + cIdx) / 32768f
                        val cur = sum / ch
                        // wyjściowe próbki przypadające między prev (inIndex-1) a cur (inIndex)
                        while (pos <= inIndex) {
                            val frac = (pos - (inIndex - 1)).toFloat().coerceIn(0f, 1f)
                            put(prev + (cur - prev) * frac)
                            pos += step
                        }
                        prev = cur
                        inIndex++
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (op > 0) { out.write(ob, 0, op); written += op }
            out.seek(0)
            out.write(Wav.header(written))
        }
        try { codec.stop(); codec.release() } catch (e: Exception) {}
        ex.release()
    }

    /** WAV 16 kHz mono → M4A (AAC ok. 32 kb/s) – ok. 10 razy mniej miejsca. */
    fun wavToM4a(src: File, dst: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.RATE, 1)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val mux = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        val info = MediaCodec.BufferInfo()
        RandomAccessFile(src, "r").use { inp ->
            inp.seek(44)
            val total = (inp.length() - 44).coerceAtLeast(0)
            var read = 0L
            var inDone = false
            var outDone = false
            val tmp = ByteArray(8192)
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        buf.clear()
                        val n = inp.read(tmp, 0, minOf(tmp.size, buf.capacity()))
                        val pts = (read / 2) * 1_000_000L / Wav.RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            buf.put(tmp, 0, n)
                            codec.queueInputBuffer(ii, 0, n, pts, 0)
                            read += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = mux.addTrack(codec.outputFormat); mux.start()
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && trackIdx >= 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        mux.writeSampleData(trackIdx, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (total == 0L) Unit
        }
        try { codec.stop(); codec.release() } catch (e: Exception) {}
        try { mux.stop(); mux.release() } catch (e: Exception) {}
    }
}

/** Wypowiedź w transkrypcji. */
class Seg(val start: Long, val end: Long, var spk: Int, var text: String, val emb: FloatArray?) {
    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).put("k", spk).put("t", text)

    companion object {
        fun fromJson(o: JSONObject) = Seg(o.optLong("s"), o.optLong("e"), o.optInt("k"), o.optString("t"), null)
    }
}

/** Zapamiętane głosy (imię → odcisk głosu). */
object Voices {
    private const val P = "voices"

    fun all(c: Context): Map<String, FloatArray> {
        val s = c.getSharedPreferences(P, Context.MODE_PRIVATE).getString("v", "{}") ?: "{}"
        val o = try { JSONObject(s) } catch (e: Exception) { JSONObject() }
        val m = LinkedHashMap<String, FloatArray>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.optJSONArray(k) ?: continue
            m[k] = FloatArray(a.length()) { a.optDouble(it).toFloat() }
        }
        return m
    }

    private fun write(c: Context, m: Map<String, FloatArray>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray(v.map { it.toDouble() }))
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("v", o.toString()).apply()
    }

    /** Dodaje lub uśrednia głos danej osoby. */
    fun remember(c: Context, name: String, emb: FloatArray) {
        val m = all(c).toMutableMap()
        val old = m[name]
        m[name] = if (old != null && old.size == emb.size) Spk.norm(FloatArray(emb.size) { old[it] + emb[it] }) else Spk.norm(emb)
        write(c, m)
    }

    fun remove(c: Context, name: String) {
        val m = all(c).toMutableMap(); m.remove(name); write(c, m)
    }
}

/** Rozpoznawanie mówców: odciski głosu wypowiedzi + grupowanie + dopasowanie do zapamiętanych głosów. */
object Spk {
    fun norm(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    class Result(val names: Map<Int, String>, val centroids: Map<Int, FloatArray>)

    /**
     * Przypisuje każdej wypowiedzi numer mówcy. Najpierw zapamiętane głosy (imiona),
     * potem nieznane głosy grupowane jako „Osoba 1”, „Osoba 2”…
     */
    fun assign(segs: List<Seg>, voices: Map<String, FloatArray>): Result {
        val known = voices.entries.toList()
        val cents = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        val names = HashMap<Int, String>()
        known.forEachIndexed { i, (n, v) -> cents.add(v.copyOf()); counts.add(0); names[i] = n }
        val knownCount = known.size
        var unknownNo = 0
        var last = -1
        for (s in segs) {
            val e = s.emb
            if (e == null) {
                s.spk = if (last >= 0) last else 0; continue
            }
            val v = norm(e)
            var best = -1
            var bestSim = 0f
            for (i in cents.indices) {
                val sim = cos(v, norm(cents[i]))
                val thr = if (i < knownCount) 0.45f else 0.55f
                if (sim > thr && sim > bestSim) { best = i; bestSim = sim }
            }
            if (best < 0) {
                cents.add(v.copyOf()); counts.add(1)
                best = cents.size - 1
                unknownNo++
                names[best] = "Osoba $unknownNo"
            } else if (best >= knownCount) {
                val c = cents[best]
                for (i in c.indices) c[i] += v[i]
                counts[best] = counts[best] + 1
            }
            s.spk = best
            last = best
        }
        val used = segs.map { it.spk }.toSet()
        val cm = HashMap<Int, FloatArray>()
        for (i in used) if (i in cents.indices) cm[i] = norm(cents[i])
        return Result(names.filterKeys { it in used }, cm)
    }
}

/** Poprawki nazw własnych ze słownika (np. „logi pak” → „Logi-Pack”). */
object DictFix {
    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    private fun key(s: String) = Store.norm(s).replace(Regex("[^a-z0-9]"), "")

    fun terms(dict: String): List<String> = dict.split(',', ';', '\n').map { it.trim() }.filter { it.length >= 3 }

    fun apply(text: String, dict: String): String {
        val terms = terms(dict)
        if (terms.isEmpty() || text.isBlank()) return text
        val words = text.split(" ").toMutableList()
        for (term in terms) {
            val tk = key(term)
            if (tk.length < 3) continue
            val n = term.split(Regex("[\\s-]+")).size.coerceIn(1, 4)
            var i = 0
            while (i < words.size) {
                for (w in n downTo 1) {
                    if (i + w > words.size) continue
                    val chunk = words.subList(i, i + w).joinToString(" ")
                    val ck = key(chunk)
                    if (ck.isEmpty()) continue
                    val maxD = when { tk.length <= 4 -> 0; tk.length <= 7 -> 1; else -> 2 }
                    if (ck != tk && lev(ck, tk) <= maxD) {
                        val tail = chunk.takeLastWhile { !it.isLetterOrDigit() }
                        for (r in 0 until w) words.removeAt(i)
                        words.add(i, term + tail)
                        break
                    }
                }
                i++
            }
        }
        return words.joinToString(" ")
    }
}

/** Silnik rozpoznawania: VAD → Whisper → odcisk głosu, dla całego pliku WAV 16 kHz. */
class Transcriber(private val c: Context, size: String, lang: String) {
    private val asr: OfflineRecognizer
    private val vad: Vad
    private val spk: SpeakerEmbeddingExtractor?

    init {
        val s = if (size == "medium") "medium" else "small"
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val wcfg = OfflineWhisperModelConfig(
            encoder = File(Models.dir(c), "$s-encoder.int8.onnx").path,
            decoder = File(Models.dir(c), "$s-decoder.int8.onnx").path,
            language = if (lang == "auto") "" else lang,
            task = "transcribe",
            tailPaddings = 1000
        )
        val mcfg = OfflineModelConfig(whisper = wcfg, tokens = File(Models.dir(c), "$s-tokens.txt").path, numThreads = threads, debug = false, provider = "cpu")
        asr = OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = Wav.RATE, featureDim = 80), modelConfig = mcfg))
        vad = Vad(null, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = Models.file(c, Models.vad).path, threshold = 0.5f, minSilenceDuration = 0.5f,
                minSpeechDuration = 0.3f, windowSize = 512, maxSpeechDuration = 20f
            ),
            sampleRate = Wav.RATE, numThreads = 1, provider = "cpu", debug = false
        ))
        spk = try {
            SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = Models.file(c, Models.speaker).path, numThreads = 2, debug = false, provider = "cpu"))
        } catch (e: Throwable) {
            null
        }
    }

    private fun text(samples: FloatArray): String {
        val st = asr.createStream()
        st.acceptWaveform(samples, Wav.RATE)
        asr.decode(st)
        val t = asr.getResult(st).text.trim()
        st.release()
        return t
    }

    fun embedding(samples: FloatArray): FloatArray? {
        val x = spk ?: return null
        if (samples.size < Wav.RATE) return null // poniżej 1 s – zbyt mało na odcisk głosu
        return try {
            val st = x.createStream()
            st.acceptWaveform(samples, Wav.RATE)
            st.inputFinished()
            val e = if (x.isReady(st)) x.compute(st) else null
            st.release()
            e
        } catch (e: Throwable) {
            null
        }
    }

    /** Przetwarza cały plik; progress(0..100). Zwraca wypowiedzi z tekstem i odciskami głosu. */
    fun run(wav: File, progress: (Int) -> Unit): List<Seg> {
        val out = ArrayList<Seg>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                vad.pop()
                val t = text(s.samples)
                if (t.isBlank() || isNoise(t)) continue
                val start = s.start.toLong() * 1000 / Wav.RATE
                val end = start + s.samples.size.toLong() * 1000 / Wav.RATE
                out.add(Seg(start, end, 0, t, embedding(s.samples)))
            }
        }
        RandomAccessFile(wav, "r").use { f ->
            f.seek(44)
            val total = (f.length() - 44).coerceAtLeast(1)
            val bytes = ByteArray(512 * 2 * 20) // ok. 0,64 s
            var read = 0L
            while (true) {
                val n = f.read(bytes)
                if (n <= 0) break
                read += n
                val sb = ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val fl = FloatArray(sb.remaining()) { sb.get(it) / 32768f }
                vad.acceptWaveform(fl)
                drain()
                progress((read * 100 / total).toInt())
            }
        }
        vad.flush()
        drain()
        return out
    }

    /** Typowe „halucynacje” Whispera na ciszy. */
    private fun isNoise(t: String): Boolean {
        val k = Store.norm(t)
        return k.length < 2 || listOf("napisy stworzone przez", "dziekuje za uwage", "subtitles by", "thank you for watching",
            "zapraszam na kolejny", "napisy wykonane").any { k.contains(it) }
    }

    fun release() {
        try { asr.release() } catch (e: Throwable) {}
        try { vad.release() } catch (e: Throwable) {}
        try { spk?.release() } catch (e: Throwable) {}
    }
}
