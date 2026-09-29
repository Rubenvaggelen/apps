package com.gmailorg.carradio

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Voegt op ieder onderliggend The One Car-scherm dezelfde navigatie terug naar menu/player toe. */
object MenuButtonHelper {
    private const val TOOLBAR_TAG = "the_one_car_navigation_toolbar"

    fun attach(activity: Activity) {
        if (activity is MainActivity || activity is CarPlayerActivity) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<LinearLayout>(TOOLBAR_TAG) != null) return

        val toolbar = LinearLayout(activity).apply {
            tag = TOOLBAR_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        if (UsbPlaybackService.snapshot().hasTrack) {
            toolbar.addView(Button(activity).apply {
                text = "← Terug naar player"
                textSize = 21f
                isAllCaps = false
                minHeight = 44.dp(activity)
                setPadding(18.dp(activity), 6.dp(activity), 18.dp(activity), 6.dp(activity))
                setOnClickListener { goToPlayer(activity) }
            })
        }

        toolbar.addView(Button(activity).apply {
            text = "← Terug naar menu"
            textSize = 21f
            isAllCaps = false
            minHeight = 44.dp(activity)
            setPadding(18.dp(activity), 6.dp(activity), 18.dp(activity), 6.dp(activity))
            setOnClickListener { goToMenu(activity) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            if (toolbar.childCount > 0) marginStart = 8.dp(activity)
        })

        val root = content.getChildAt(0)
        if (root is LinearLayout && root.orientation == LinearLayout.VERTICAL) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6.dp(activity)
            }
            root.addView(toolbar, 0, lp)
        } else {
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 12.dp(activity)
                marginEnd = 12.dp(activity)
            }
            activity.addContentView(toolbar, lp)
        }
    }

    fun goToMenu(activity: Activity) {
        val intent = Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        activity.startActivity(intent)
        activity.finish()
    }

    fun goToPlayer(activity: Activity) {
        val intent = Intent(activity, CarPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private fun Int.dp(activity: Activity): Int =
        (this * activity.resources.displayMetrics.density).toInt()
}
