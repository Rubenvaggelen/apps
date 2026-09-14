package com.gmailorg.hub

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Voegt op ieder onderliggend scherm van de normale The One-app dezelfde terug-naar-menu-knop toe. */
object MenuButtonHelper {
    private const val MENU_BUTTON_TAG = "the_one_menu_button"

    fun attach(activity: Activity) {
        if (activity is HomeActivity) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<Button>(MENU_BUTTON_TAG) != null) return

        val button = Button(activity).apply {
            tag = MENU_BUTTON_TAG
            text = "← Terug naar menu"
            textSize = 15f
            isAllCaps = false
            minHeight = 44.dp(activity)
            setPadding(20.dp(activity), 6.dp(activity), 20.dp(activity), 6.dp(activity))
            setOnClickListener { goToMenu(activity) }
        }

        val root = content.getChildAt(0)
        if (root is LinearLayout && root.orientation == LinearLayout.VERTICAL) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                bottomMargin = 6.dp(activity)
            }
            root.addView(button, 0, lp)
        } else {
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 12.dp(activity)
                marginEnd = 12.dp(activity)
            }
            activity.addContentView(button, lp)
        }
    }

    fun goToMenu(activity: Activity) {
        val intent = Intent(activity, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private fun Int.dp(activity: Activity): Int =
        (this * activity.resources.displayMetrics.density).toInt()
}
