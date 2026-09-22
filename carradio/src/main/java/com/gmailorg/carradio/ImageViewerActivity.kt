package com.gmailorg.carradio

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ImageViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isBlank()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val back = Button(this).apply {
            text = "← Terug"
            textSize = 16f
            setOnClickListener { finish() }
        }
        root.addView(
            back,
            FrameLayout.LayoutParams(150.dp, 56.dp, Gravity.TOP or Gravity.START).apply {
                leftMargin = 18.dp
                topMargin = 14.dp
            }
        )

        val titleText = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(
            titleText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                56.dp,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 14.dp }
        )

        setContentView(root)

        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val maxW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val maxH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
            var sample = 1
            while (opts.outWidth / sample > maxW * 2 || opts.outHeight / sample > maxH * 2) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bitmap != null) image.setImageBitmap(bitmap)
            else Toast.makeText(this, "Afbeelding kon niet worden geopend", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Afbeelding kon niet worden geopend", Toast.LENGTH_SHORT).show()
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
