// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.tuev

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.utils.Permissions
import timber.log.Timber

/**
 * TÜV SÜD Akademie: Lern-Assistentin "Karla" im Stil der Win98-Büroassistenten.
 *
 * Beim ersten Start erklärt sie in einer kurzen Tour die wichtigsten Funktionen.
 * Danach ist sie über das Überlaufmenü der Deckliste ("Lern-Assistent") wieder aufrufbar.
 */
object LernAssistent {
    private const val PREF_TOUR_SHOWN = "tuev_assistant_tour_shown"

    /** Zeigt die Tour, falls sie noch nie bis zum Ende oder bis "Tipps schließen" gelaufen ist. */
    fun showOnFirstLaunch(activity: Activity) {
        if (activity.sharedPrefs().getBoolean(PREF_TOUR_SHOWN, false)) return
        show(activity)
    }

    /** Zeigt die Tour ab dem ersten Tipp. Ein bereits offener Assistent wird nicht doppelt angezeigt. */
    fun show(activity: Activity) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewById<View>(R.id.tuev_assistant_root) != null) return
        Timber.i("Lern-Assistent: anzeigen")

        val root = LayoutInflater.from(activity).inflate(R.layout.tuev_assistant, content, false)
        content.addView(root)
        // Overlay soll Berührungen außerhalb der Blase an die Deckliste durchlassen
        root.isClickable = false
        root.isFocusable = false

        val container = root.findViewById<View>(R.id.tuev_assistant_container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + (24 * activity.resources.displayMetrics.density).toInt()
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)

        Tour(activity, root).start()
    }

    private class Tour(
        private val activity: Activity,
        private val root: View,
    ) {
        private val titles = activity.resources.getStringArray(R.array.tuev_assistant_titles)
        private val texts = activity.resources.getStringArray(R.array.tuev_assistant_texts)
        private val title = root.findViewById<TextView>(R.id.tuev_assistant_title)
        private val text = root.findViewById<TextView>(R.id.tuev_assistant_text)
        private val progress = root.findViewById<TextView>(R.id.tuev_assistant_progress)
        private val back = root.findViewById<TextView>(R.id.tuev_assistant_back)
        private val next = root.findViewById<TextView>(R.id.tuev_assistant_next)
        private val character = root.findViewById<ImageView>(R.id.tuev_assistant_character)
        private var index = 0
        private var idle: ObjectAnimator? = null

        init {
            check(titles.size == texts.size) { "tuev_assistant_titles und _texts müssen gleich lang sein" }
        }

        fun start() {
            back.setOnClickListener { go(index - 1) }
            next.setOnClickListener { if (index == titles.lastIndex) finish() else go(index + 1) }
            root.findViewById<View>(R.id.tuev_assistant_close).setOnClickListener { finish() }
            // Antippen der Figur: kleiner Hüpfer, wie beim Original-Vorbild
            character.setOnClickListener { wiggle() }

            render()
            appear()
        }

        private fun go(target: Int) {
            index = target.coerceIn(0, titles.lastIndex)
            render()
            wiggle()
        }

        private fun render() {
            title.text = titles[index]
            text.text = texts[index]
            progress.text = "${index + 1}/${titles.size}"
            back.isInvisible = index == 0
            next.setText(if (index == titles.lastIndex) R.string.tuev_assistant_done else R.string.tuev_assistant_next)
        }

        /** Hereinspringen von unten, danach leichtes Schweben im Leerlauf. */
        private fun appear() {
            val container = root.findViewById<View>(R.id.tuev_assistant_container)
            container.translationY = 400f
            container.alpha = 0f
            container
                .animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(450)
                .setInterpolator(OvershootInterpolator(1.4f))
                .withEndAction { startIdle() }
                .start()
        }

        private fun startIdle() {
            idle =
                ObjectAnimator.ofFloat(character, View.TRANSLATION_Y, 0f, -6f).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
        }

        private fun wiggle() {
            ObjectAnimator
                .ofFloat(character, View.ROTATION, 0f, -10f, 8f, -4f, 0f)
                .setDuration(500)
                .start()
        }

        private fun finish() {
            Timber.i("Lern-Assistent: geschlossen bei Tipp %d", index + 1)
            val firstTour = !activity.sharedPrefs().getBoolean(PREF_TOUR_SHOWN, false)
            activity.sharedPrefs().edit { putBoolean(PREF_TOUR_SHOWN, true) }
            // Nach der ersten Tour um Erlaubnis für die Lern-Erinnerung bitten (Android 13+)
            if (firstTour && activity is FragmentActivity) {
                Permissions.showNotificationsPermissionBottomSheetIfNeeded(activity, activity.supportFragmentManager) {}
            }
            idle?.cancel()
            root
                .animate()
                .alpha(0f)
                .translationY(200f)
                .setDuration(250)
                .withEndAction { (root.parent as? ViewGroup)?.removeView(root) }
                .start()
        }
    }
}
