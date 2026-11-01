package pl.notatnik

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.util.Calendar
import java.util.Locale

/**
 * Przypomnienia: jednorazowe, „za X czasu”, codziennie, w wybrane dni tygodnia, co N godzin/dni/tygodni,
 * co miesiąc i co rok. Działają po restarcie telefonu. W powiadomieniu: Gotowe i Odłóż 10 min.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    const val ACT_FIRE = "pl.notatnik.REMIND"
    const val ACT_DONE = "pl.notatnik.REMIND_DONE"
    const val ACT_SNOOZE = "pl.notatnik.REMIND_SNOOZE"
    val DAY_NAMES = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")

    /** Bit dnia tygodnia: 0 = poniedziałek … 6 = niedziela. */
    fun dayBit(c: Calendar): Int = when (c.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6
    }

    /** Następny termin po [after] dla przypomnienia powtarzanego (0 = brak). */
    fun next(n: Note, after: Long = System.currentTimeMillis()): Long {
        if (n.repeat.isEmpty() || n.reminder <= 0) return 0
        val c = Calendar.getInstance()
        c.timeInMillis = n.reminder
        var guard = 0
        while (c.timeInMillis <= after && guard < 100_000) {
            guard++
            when (n.repeat) {
                "daily" -> c.add(Calendar.DAY_OF_YEAR, 1)
                "weekdays" -> {
                    if (n.repDays == 0) return 0
                    do {
                        c.add(Calendar.DAY_OF_YEAR, 1)
                    } while (n.repDays and (1 shl dayBit(c)) == 0)
                }
                "every" -> when (n.repUnit) {
                    "h" -> c.add(Calendar.HOUR_OF_DAY, maxOf(1, n.repEvery))
                    "w" -> c.add(Calendar.WEEK_OF_YEAR, maxOf(1, n.repEvery))
                    "m" -> c.add(Calendar.MINUTE, maxOf(5, n.repEvery))
                    else -> c.add(Calendar.DAY_OF_YEAR, maxOf(1, n.repEvery))
                }
                "monthly" -> c.add(Calendar.MONTH, 1)
                "yearly" -> c.add(Calendar.YEAR, 1)
                else -> return 0
            }
        }
        return c.timeInMillis
    }

    /** Pierwszy termin w wybrane dni tygodnia od dziś (o danej godzinie). */
    fun firstWeekday(mask: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        var k = 0
        while (k < 8 && (c.timeInMillis <= System.currentTimeMillis() || mask and (1 shl dayBit(c)) == 0)) {
            c.add(Calendar.DAY_OF_YEAR, 1); k++
        }
        return c.timeInMillis
    }

    /** Opis dla użytkownika, np. „codziennie o 8:00”, „Pn, Śr, Pt o 18:30”. */
    fun describe(n: Note): String {
        if (n.reminder <= 0) return ""
        val c = Calendar.getInstance()
        c.timeInMillis = n.reminder
        val time = String.format(Locale.US, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val date = String.format(Locale.US, "%d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        return when (n.repeat) {
            "daily" -> "codziennie o $time"
            "weekdays" -> when (n.repDays) {
                0b0011111 -> "w dni robocze o $time"
                0b1100000 -> "w weekendy o $time"
                0b1111111 -> "codziennie o $time"
                else -> DAY_NAMES.filterIndexed { i, _ -> n.repDays and (1 shl i) != 0 }.joinToString(", ") + " o $time"
            }
            "every" -> {
                val e = maxOf(1, n.repEvery)
                when (n.repUnit) {
                    "m" -> "co $e min"
                    "h" -> if (e == 1) "co godzinę" else "co $e godz."
                    "w" -> (if (e == 1) "co tydzień" else "co $e tyg.") + " o $time"
                    else -> (if (e == 1) "codziennie" else "co $e dni") + " o $time"
                } + " · następne $date $time"
            }
            "monthly" -> "co miesiąc ${c.get(Calendar.DAY_OF_MONTH)}. dnia o $time"
            "yearly" -> "co roku $date o $time"
            else -> "$date.${c.get(Calendar.YEAR)} o $time"
        }
    }

    private fun pi(ctx: Context, id: String, snooze: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode() + if (snooze) 3 else 0,
        Intent(ctx, ReminderReceiver::class.java).setAction(ACT_FIRE).putExtra("note", id).putExtra("snooze", snooze),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun setAlarm(ctx: Context, at: Long, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun schedule(ctx: Context, n: Note) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, n.id)
        am.cancel(p)
        if (n.reminder <= 0 || n.archived || n.deleted > 0) return
        // powtarzane: gdy termin minął (np. telefon był wyłączony) – przesuń na następny
        if (n.repeat.isNotEmpty() && n.reminder <= System.currentTimeMillis()) {
            val nx = next(n)
            if (nx > 0) n.reminder = nx
        }
        if (n.reminder <= System.currentTimeMillis()) return
        setAlarm(ctx, n.reminder, p)
    }

    fun scheduleAll(ctx: Context) {
        for (n in Store.notes) if (n.reminder > 0) schedule(ctx, n)
        Store.save()
    }

    fun snooze(ctx: Context, id: String, minutes: Int) {
        setAlarm(ctx, System.currentTimeMillis() + minutes * 60_000L, pi(ctx, id, true))
    }

    fun fire(ctx: Context, id: String, snoozed: Boolean) {
        val n = Store.note(id) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Przypomnienia", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(), Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("note", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(act: String, code: Int) = PendingIntent.getBroadcast(
            ctx, id.hashCode() + code, Intent(ctx, ReminderReceiver::class.java).setAction(act).putExtra("note", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            n.locked -> "Notatka zablokowana"
            n.isList -> n.live.filter { !it.done }.take(6).joinToString(", ") { it.text }
            else -> n.body.take(300)
        }.ifBlank { describe(n) }
        val notif = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(Icon.createWithResource(ctx, R.drawable.ic_bell))
            .setContentTitle(n.title.ifEmpty { "Przypomnienie" })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_box_on), "Gotowe", action(ACT_DONE, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_bell), "Odłóż 10 min", action(ACT_SNOOZE, 2)).build())
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id.hashCode(), notif)
        } catch (e: SecurityException) {
        }
        // powtarzane: ustaw kolejny termin (odłożone nie przesuwa harmonogramu)
        if (!snoozed && n.repeat.isNotEmpty()) {
            val nx = next(n, System.currentTimeMillis() + 1000)
            if (nx > 0) {
                n.reminder = nx
                Store.save()
                schedule(ctx, n)
            }
        }
        NotesWidget.updateAll(ctx)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val id = intent.getStringExtra("note")
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Reminders.scheduleAll(ctx)
                Shared.schedule(ctx)
            }
            Reminders.ACT_FIRE -> if (id != null) Reminders.fire(ctx, id, intent.getBooleanExtra("snooze", false))
            Reminders.ACT_SNOOZE -> if (id != null) {
                nm?.cancel(id.hashCode())
                Reminders.snooze(ctx, id, 10)
            }
            Reminders.ACT_DONE -> if (id != null) {
                nm?.cancel(id.hashCode())
                Store.note(id)?.let {
                    // jednorazowe: zakończone; powtarzane zostają w harmonogramie
                    if (it.repeat.isEmpty()) {
                        it.reminder = 0
                        Store.touch(it)
                        Store.save()
                    }
                }
                NotesWidget.updateAll(ctx)
            }
        }
    }
}
