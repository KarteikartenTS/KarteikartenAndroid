// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.tuev

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.NotificationChannel
import com.ichi2.anki.R
import com.ichi2.anki.common.permissions.canPostNotifications
import com.ichi2.anki.common.preferences.sharedPrefs
import timber.log.Timber
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * TÜV SÜD Akademie: Erinnerung im Duolingo-Stil.
 *
 * Wird die App 5 Tage nicht geöffnet, meldet sich Karla am nächsten Werktag um 9:30.
 * Jeder App-Start plant die Erinnerung neu (Frist beginnt von vorn). Bleibt die App weiter
 * zu, kommt die nächste Erinnerung wieder nach 5 Tagen. Rein lokal, ohne Server.
 */
object LernErinnerung {
    private const val WORK_NAME = "tuev_lern_erinnerung"
    private const val PREF_TEXT_INDEX = "tuev_reminder_text_index"
    private const val NOTIFICATION_ID = 0x7E5D

    private const val PAUSE_DAYS = 5L
    private val REMIND_AT: LocalTime = LocalTime.of(9, 30)

    /** Beim App-Start aufrufen: verschiebt die Erinnerung auf 5 Tage ab jetzt. */
    fun onAppOpened(context: Context) = schedule(context, LocalDateTime.now(), ExistingWorkPolicy.REPLACE)

    /** Nächster Werktag 9:30, frühestens nach [PAUSE_DAYS] vollen Tagen ab [from]. */
    @VisibleForTesting
    fun nextReminder(from: LocalDateTime): LocalDateTime {
        val earliest = from.plusDays(PAUSE_DAYS)
        var at = earliest.toLocalDate().atTime(REMIND_AT)
        if (at.isBefore(earliest)) at = at.plusDays(1)
        while (at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) {
            at = at.plusDays(1)
        }
        return at
    }

    private fun schedule(
        context: Context,
        from: LocalDateTime,
        policy: ExistingWorkPolicy,
    ) {
        val at = nextReminder(from)
        val delay = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(0)
        val request =
            OneTimeWorkRequestBuilder<Worker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        Timber.i("Lern-Erinnerung geplant für %s", at)
    }

    @SuppressLint("MissingPermission") // geprüft über canPostNotifications
    private fun notify(context: Context) {
        if (!canPostNotifications(context)) {
            Timber.i("Lern-Erinnerung: keine Benachrichtigungs-Berechtigung")
            return
        }
        val titles = context.resources.getStringArray(R.array.tuev_reminder_titles)
        val texts = context.resources.getStringArray(R.array.tuev_reminder_texts)
        // Texte der Reihe nach, damit nicht zweimal derselbe kommt
        val prefs = context.sharedPrefs()
        val index = prefs.getInt(PREF_TEXT_INDEX, 0) % texts.size
        prefs.edit { putInt(PREF_TEXT_INDEX, index + 1) }

        val open =
            PendingIntentCompat.getActivity(
                context,
                0,
                Intent(context, IntentHandler::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannel.TUEV_LERN_ERINNERUNG.id)
                .setSmallIcon(R.drawable.tuev_ic_notify)
                .setContentTitle(titles[index % titles.size])
                .setContentText(texts[index])
                .setStyle(NotificationCompat.BigTextStyle().bigText(texts[index]))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    class Worker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            notify(applicationContext)
            // Weiter keine Nutzung: in 5 Tagen erneut erinnern
            // APPEND_OR_REPLACE: REPLACE würde diesen laufenden Worker selbst abbrechen
            schedule(applicationContext, LocalDateTime.now(), ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
    }
}
