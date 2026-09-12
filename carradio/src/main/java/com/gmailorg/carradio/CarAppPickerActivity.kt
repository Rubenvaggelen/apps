package com.gmailorg.carradio

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CarAppPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_app_picker)
        rebuild()
    }

    private fun rebuild() {
        val container = findViewById<LinearLayout>(R.id.appPickerList)
        container.removeAllViews()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val existing = CarTileStore.apps(this)
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName && !existing.contains(it.activityInfo.packageName) }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        apps.forEach { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm).toString()
            val row = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 18f
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                setBackgroundResource(R.drawable.bg_outline)
                setOnClickListener {
                    CarTileStore.addApp(this@CarAppPickerActivity, pkg)
                    finish()
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8.dp)
            container.addView(row, lp)
        }
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
