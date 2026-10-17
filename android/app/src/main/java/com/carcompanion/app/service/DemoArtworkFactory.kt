package com.carcompanion.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.carcompanion.app.media.ArtworkEncoder
import com.carcompanion.app.model.Artwork

/** Deterministic bench image: colors, edges and a DEMO label test the real JPEG/BLE/render path. */
internal object DemoArtworkFactory {
    suspend fun encode(context: Context, quality: Int): Artwork? {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colors = intArrayOf(0xff152842.toInt(), 0xff416c9a.toInt(), 0xff345f48.toInt(), 0xff151b21.toInt())
            colors.forEachIndexed { index, color ->
                paint.color = color
                val left = (index % 2) * 120f
                val top = (index / 2) * 120f
                canvas.drawRect(left, top, left + 120f, top + 120f, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.WHITE
            canvas.drawCircle(120f, 120f, 87f, paint)
            canvas.drawCircle(120f, 120f, 77f, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xee050709.toInt()
            canvas.drawRoundRect(35f, 87f, 205f, 153f, 5f, 5f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            paint.textSize = 30f
            canvas.drawText("DEMO", 120f, 127f, paint)
            paint.isFakeBoldText = false
            paint.textSize = 11f
            canvas.drawText("CAR COMPANION", 120f, 176f, paint)
            // Full-primary patches make byte-order/color errors obvious on the physical AMOLED.
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE).forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect(87f + index * 17f, 189f, 100f + index * 17f, 196f, paint)
            }
            return ArtworkEncoder.encode(context, bitmap, null, quality)
        } finally { bitmap.recycle() }
    }
}
