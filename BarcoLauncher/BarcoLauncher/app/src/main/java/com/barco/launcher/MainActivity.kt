package com.barco.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        /** Color de la barra de abajo (igual que la ola mas cercana). */
        private val BAR_COLOR = Color.rgb(30, 80, 124)

        /**
         * Apps que salen primero y en este orden (se busca en el nombre de la app,
         * sin importar mayusculas). Cambia o anade las que quieras.
         */
        private val PRIORITY = listOf(
            "navega", "navig", "mapa", "maps", "waze",
            "carplay", "carlink", "zlink", "android auto",
            "música", "musica", "music", "spotify",
            "radio",
            "bluetooth",
            "ajustes", "settings"
        )
    }

    private lateinit var scene: SceneView
    private lateinit var bar: HorizontalScrollView
    private lateinit var row: LinearLayout
    private var barHeight = 0
    private var itemWidthPx = 0
    private var shownKeys: List<String> = emptyList()

    private var locationManager: LocationManager? = null
    private var lastFixMs = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFixMs = SystemClock.elapsedRealtime()
            val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            // Por debajo de 3 km/h es ruido del GPS: lo damos por parado.
            scene.gpsSpeed = if (kmh < 3f) 0f else kmh
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val staleCheck = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastFixMs > 5000L) {
                scene.gpsSpeed = 0f
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_COLOR)
        }

        scene = SceneView(this)
        root.addView(scene, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 78f))

        bar = HorizontalScrollView(this).apply {
            setBackgroundColor(BAR_COLOR)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 22f))

        bar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && h != barHeight) {
                barHeight = h
                buildApps()
            }
        }

        setContentView(root)
        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
    }

    override fun onResume() {
        super.onResume()
        if (barHeight > 0) buildApps() // por si se ha instalado/desinstalado algo
        startGps()
        handler.removeCallbacks(staleCheck)
        handler.postDelayed(staleCheck, 2000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(staleCheck)
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: SecurityException) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Al pulsar "Inicio" estando ya en el launcher, volvemos al principio de la barra.
        bar.smoothScrollTo(0, 0)
    }

    // Un launcher no debe cerrarse con "atras".
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }

    // ---------------------------------------------------------------- GPS

    private fun startGps() {
        val lm = locationManager ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        try {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            }
        } catch (e: SecurityException) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGps()
    }

    // ---------------------------------------------------------------- Apps

    private fun priorityOf(label: String): Int {
        val l = label.lowercase()
        val i = PRIORITY.indexOfFirst { l.contains(it) }
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun buildApps() {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val saved = loadOrder()
        val apps: List<Pair<ResolveInfo, String>> = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it to it.loadLabel(pm).toString() }
            .sortedWith(
                compareBy<Pair<ResolveInfo, String>>(
                    { savedIndex(saved, keyOf(it.first)) },
                    { priorityOf(it.second) },
                    { it.second.lowercase() }
                )
            )

        row.removeAllViews()
        shownKeys = apps.map { keyOf(it.first) }

        val iconSize = (barHeight * 0.50f).toInt()
        val itemWidth = (iconSize * 1.9f).toInt()
        itemWidthPx = itemWidth
        val labelSize = barHeight * 0.115f

        for ((info, label) in apps) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4, 0, 4, 0)
            }

            val icon = ImageView(this).apply {
                setImageDrawable(info.loadIcon(pm))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))

            val text = TextView(this).apply {
                this.text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSize)
                setShadowLayer(3f, 0f, 1f, Color.argb(160, 0, 0, 0))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
            val tlp = LinearLayout.LayoutParams(itemWidth - 8, ViewGroup.LayoutParams.WRAP_CONTENT)
            tlp.topMargin = (barHeight * 0.04f).toInt()
            item.addView(text, tlp)

            item.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.55f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
            item.setOnClickListener {
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    startActivity(launch)
                } catch (e: Exception) {
                }
            }

            item.setOnLongClickListener {
                item.alpha = 1f
                showMoveDialog(keyOf(info), label)
                true
            }

            row.addView(item, LinearLayout.LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // ------------------------------------------------- Orden de las apps

    private fun keyOf(info: ResolveInfo): String =
        info.activityInfo.packageName + "/" + info.activityInfo.name

    private fun savedIndex(saved: List<String>, key: String): Int {
        val i = saved.indexOf(key)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun loadOrder(): List<String> {
        val raw = getSharedPreferences("barco", MODE_PRIVATE).getString("order", "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n")
    }

    private fun saveOrder(keys: List<String>?) {
        val e = getSharedPreferences("barco", MODE_PRIVATE).edit()
        if (keys == null) e.remove("order") else e.putString("order", keys.joinToString("\n"))
        e.apply()
    }

    /** Menu al mantener pulsada una app: mover a izquierda / derecha / principio. */
    private fun showMoveDialog(key: String, label: String) {
        val options = arrayOf(
            "\u2190  Mover a la izquierda",
            "\u2192  Mover a la derecha",
            "\u21E4  Llevar al principio",
            "Restablecer el orden de todas"
        )
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(options) { _, which ->
                val list = shownKeys.toMutableList()
                val i = list.indexOf(key)
                var again = true
                when (which) {
                    0 -> if (i > 0) { list.removeAt(i); list.add(i - 1, key) }
                    1 -> if (i in 0 until list.size - 1) { list.removeAt(i); list.add(i + 1, key) }
                    2 -> if (i > 0) { list.removeAt(i); list.add(0, key) }
                    3 -> { saveOrder(null); again = false }
                }
                if (which != 3) saveOrder(list)
                buildApps()
                val pos = shownKeys.indexOf(key)
                if (pos >= 0) {
                    bar.post { bar.smoothScrollTo(maxOf(0, pos * itemWidthPx - itemWidthPx * 2), 0) }
                }
                if (again && pos >= 0) showMoveDialog(key, label)
            }
            .setNegativeButton("Listo", null)
            .show()
    }
}
