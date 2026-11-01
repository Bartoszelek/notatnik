package pl.notatnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dyktafon w tle (z powiadomieniem i przyciskiem „Zatrzymaj”). */
class RecorderService : Service() {
    companion object {
        const val ACT_STOP = "pl.notatnik.REC_STOP"
        @Volatile var running = false
        @Volatile var startedAt = 0L
        var attachTo: String? = null
        var onStopped: ((Note) -> Unit)? = null
    }

    private var rec: MediaRecorder? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        if (intent?.action == ACT_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("rec", "Nagrywanie", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 5, Intent(this, RecorderService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "rec")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_mic))
            .setContentTitle("Nagrywanie…")
            .setContentText("Stuknij „Zatrzymaj”, żeby zapisać nagranie")
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Zatrzymaj", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(7, n)
        val f = File(Store.dir("audio"), "rec_${System.currentTimeMillis()}.m4a")
        file = f
        try {
            val r = newRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            rec = r
            running = true
            startedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopRecording() {
        val r = rec ?: return
        val ms = System.currentTimeMillis() - startedAt
        try {
            r.stop()
        } catch (e: Exception) {
        }
        r.release()
        rec = null
        running = false
        val f = file ?: return
        if (!f.exists() || f.length() < 1000) return
        val target = Store.note(attachTo)
        val note = target ?: Note(
            type = "audio",
            title = "Nagranie " + SimpleDateFormat("d.MM, HH:mm", Locale("pl")).format(Date())
        ).also { Store.notes.add(it) }
        note.audio = f.path
        note.audioMs = ms
        Store.touch(note)
        Store.save()
        attachTo = null
        NotesWidget.updateAll(this)
        Handler(Looper.getMainLooper()).post { onStopped?.invoke(note) }
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
