package com.barco.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Escena de la parte de arriba: la familia de fondo, el mar de papel y el barquito.
 *
 *  - Coche parado  -> el barco se mece suavemente en el sitio.
 *  - Coche en marcha -> las olas van mas rapido y el barco navega de izquierda a derecha,
 *    cada vez mas rapido cuanto mas rapido va el coche.
 *
 * Mantener pulsado (1 segundo) sobre la escena activa el "modo prueba", que simula
 * 20 / 60 / 120 km/h para ver la animacion con el coche parado.
 */
class SceneView(context: Context) : View(context) {

    /** Velocidad real del GPS en km/h (la pone MainActivity). */
    @Volatile
    var gpsSpeed: Float = 0f

    // ---------- AJUSTES FACILES DE CAMBIAR ----------
    /** Tamano del barco respecto al ancho de la pantalla (0.26 = 26 %). */
    private val boatWidthFactor = 0.26f

    /** Cuanto mas alto, mas rapido cruza el barco la pantalla al ir el coche. */
    private val boatSpeedFactor = 1f / 1500f

    /** Velocidad minima de avance del barco con el coche parado (casi quieto). */
    private val boatIdleDrift = 0.003f
    // -------------------------------------------------

    private val demoSpeeds = floatArrayOf(-1f, 20f, 60f, 120f)
    private var demoIndex = 0

    private val bmFamily: Bitmap = load(R.drawable.family)
    private val bmBoat: Bitmap = load(R.drawable.boat)
    private val bmBgLeft: Bitmap = load(R.drawable.bg_left)
    private val bmBgRight: Bitmap = load(R.drawable.bg_right)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 40, 70, 90)
    }

    private class Layer(
        val base: Float,      // altura media (fraccion del alto)
        val amp: Float,       // amplitud ola principal
        val wl: Float,        // longitud de onda principal (fraccion del ancho)
        val speed: Float,     // multiplicador de velocidad de la ola
        val ph: Float,        // fase inicial
        val amp2: Float,      // amplitud ola secundaria
        val wl2: Float,       // longitud de onda secundaria
        val colorTop: Int,
        val colorBottom: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }

    // De atras hacia delante. El barco va entre la capa 1 y la 2.
    private val layers = listOf(
        Layer(0.735f, 0.016f, 0.60f, 0.8f, 0.0f, 0.008f, 0.27f, Color.rgb(184, 210, 234), Color.rgb(150, 188, 220)),
        Layer(0.775f, 0.020f, 0.42f, 1.0f, 1.3f, 0.009f, 0.23f, Color.rgb(130, 178, 218), Color.rgb(104, 152, 200)),
        Layer(0.835f, 0.022f, 0.34f, 1.25f, 2.6f, 0.009f, 0.19f, Color.rgb(86, 142, 192), Color.rgb(62, 114, 168)),
        Layer(0.905f, 0.020f, 0.28f, 1.5f, 4.1f, 0.008f, 0.16f, Color.rgb(44, 98, 148), Color.rgb(30, 80, 124))
    )

    private val topPath = Path()
    private val fillPath = Path()
    private val dst = RectF()

    private var lastMs = 0L
    private var phase = 0.0
    private var speedSmooth = 0f
    private var boatX = Float.NaN

    init {
        isLongClickable = true
        setOnLongClickListener {
            demoIndex = (demoIndex + 1) % demoSpeeds.size
            true
        }
    }

    private fun load(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hf = h.toFloat()
        for (l in layers) {
            val top = hf * (l.base - l.amp - l.amp2)
            l.paint.shader = LinearGradient(0f, top, 0f, hf, l.colorTop, l.colorBottom, Shader.TileMode.CLAMP)
        }
        highlightPaint.strokeWidth = max(2f, hf * 0.006f)
        textPaint.textSize = max(14f, hf * 0.045f)
    }

    private fun waveY(l: Layer, x: Float, w: Float, h: Float): Float {
        val twoPi = 2.0 * PI
        val a = sin(twoPi * x / (w * l.wl) + phase * l.speed + l.ph)
        val b = sin(twoPi * x / (w * l.wl2) - phase * l.speed * 0.6 + l.ph * 1.7)
        return (h * l.base + h * l.amp * a + h * l.amp2 * b).toFloat()
    }

    private fun step(dt: Float, w: Float) {
        val demo = demoSpeeds[demoIndex]
        val target = if (demo >= 0f) demo else gpsSpeed
        speedSmooth += (target - speedSmooth) * min(1f, dt * 1.5f)
        if (target == 0f && speedSmooth < 0.3f) speedSmooth = 0f

        phase += dt * (0.7 + speedSmooth * 0.05)

        val boatW = boatWidth(w)
        if (boatX.isNaN()) boatX = w * 0.5f
        boatX += w * (boatIdleDrift + speedSmooth * boatSpeedFactor) * dt
        if (boatX > w + boatW / 2f) boatX = -boatW / 2f
    }

    private fun boatWidth(w: Float): Float = min(w * boatWidthFactor, height * 0.62f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        var dt = if (lastMs == 0L) 0f else (now - lastMs) / 1000f
        lastMs = now
        if (dt > 0.1f) dt = 0.1f
        step(dt, w)

        // Fondo + familia
        canvas.drawColor(Color.rgb(200, 218, 218))
        val famH = h * 0.80f
        val famW = famH * bmFamily.width / bmFamily.height
        val left = (w - famW) / 2f
        dst.set(0f, 0f, left + famW * 0.06f, famH)
        canvas.drawBitmap(bmBgLeft, null, dst, bitmapPaint)
        dst.set(left + famW * 0.94f, 0f, w, famH)
        canvas.drawBitmap(bmBgRight, null, dst, bitmapPaint)
        dst.set(left, 0f, left + famW, famH)
        canvas.drawBitmap(bmFamily, null, dst, bitmapPaint)

        // Mar (capas de papel) y barco
        for (i in layers.indices) {
            drawLayer(canvas, layers[i], w, h)
            if (i == 1) drawBoat(canvas, w, h)
        }

        // Aviso del modo prueba
        val demo = demoSpeeds[demoIndex]
        if (demo >= 0f) {
            canvas.drawText("Modo prueba: ${demo.toInt()} km/h  (manten pulsado para cambiar)", h * 0.04f, h * 0.07f, textPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun drawLayer(canvas: Canvas, l: Layer, w: Float, h: Float) {
        val stepPx = max(6f, w / 100f)
        topPath.reset()
        topPath.moveTo(0f, waveY(l, 0f, w, h))
        var x = stepPx
        while (x < w) {
            topPath.lineTo(x, waveY(l, x, w, h))
            x += stepPx
        }
        topPath.lineTo(w, waveY(l, w, w, h))

        fillPath.set(topPath)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        canvas.drawPath(fillPath, l.paint)
        canvas.drawPath(topPath, highlightPaint)
    }

    private fun drawBoat(canvas: Canvas, w: Float, h: Float) {
        val boatW = boatWidth(w)
        val boatH = boatW * bmBoat.height / bmBoat.width
        val surface = layers[1]

        // El barco sigue la superficie de la ola: sube, baja y se inclina con ella.
        val y = waveY(surface, boatX, w, h)
        val dy = waveY(surface, boatX + 14f, w, h) - waveY(surface, boatX - 14f, w, h)
        val angle = Math.toDegrees(atan2(dy.toDouble(), 28.0)).toFloat()

        val cy = y + boatH * 0.45f - boatH / 2f
        canvas.save()
        canvas.translate(boatX, cy)
        canvas.rotate(angle)
        dst.set(-boatW / 2f, -boatH / 2f, boatW / 2f, boatH / 2f)
        canvas.drawBitmap(bmBoat, null, dst, bitmapPaint)
        canvas.restore()
    }
}
