package com.gmailorg.carradio

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Zichtbare laag die in fade-modus geen touch claimt.
 * Daardoor kunnen dashboardtegels achter de YouTube-speler gewoon worden bediend.
 */
class PassThroughFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var passThrough: Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (passThrough) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return !passThrough && super.onInterceptTouchEvent(ev)
    }
}
