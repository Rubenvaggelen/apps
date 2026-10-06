package com.gmailorg.runcoach
import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout

object LifestyleNav {
    fun attach(activity: Activity) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
        root.addView(Button(activity).apply {
            text = "← Terug naar Run"
            setTextColor(activity.getColor(R.color.ink))
            setBackgroundColor(activity.getColor(R.color.panel))
            setOnClickListener {
                activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                activity.finish()
            }
        }, 0)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}
