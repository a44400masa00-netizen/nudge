package com.example.studynudge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class MapPickerActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var infoView: TextView
    private lateinit var radiusView: TextView
    private var marker: Marker? = null
    private var circle: Polygon? = null
    private var picked: GeoPoint? = null
    private var radius = 150
    private var placeLabel = ""

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conf = Configuration.getInstance()
        conf.load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = packageName
        conf.osmdroidBasePath = File(cacheDir, "osmdroid")
        conf.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        val key = intent.getStringExtra("key") ?: "home"
        placeLabel = intent.getStringExtra("label") ?: key
        val prefs = Prefs(this)
        val existing = prefs.getPlace(key)
        if (existing != null) radius = existing.radius

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(0, dp(28f), 0, 0)

        map = MapView(this)
        // 国土地理院の地図タイルを使用（OpenStreetMapの無料サーバーはアプリからのアクセスを
        // ブロックすることがあるため。日本国内向け・APIキー不要・無料）
        map.setTileSource(
            XYTileSource(
                "GSI", 5, 18, 256, ".png",
                arrayOf("https://cyberjapandata.gsi.go.jp/xyz/std/")
            )
        )
        map.setMultiTouchControls(true)
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        infoView = TextView(this)
        infoView.text = "「${placeLabel}」の場所を、地図をタップして指定してください"
        infoView.setPadding(dp(16f), dp(8f), dp(16f), 0)
        root.addView(infoView)

        radiusView = TextView(this)
        radiusView.text = "この場所とみなす半径: ${radius} m"
        radiusView.setPadding(dp(16f), dp(8f), dp(16f), 0)
        root.addView(radiusView)

        val seek = SeekBar(this)
        seek.max = 950
        seek.progress = (radius - 50).coerceIn(0, 950)
        seek.setPadding(dp(24f), dp(4f), dp(24f), dp(4f))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                radius = progress + 50
                radiusView.text = "この場所とみなす半径: ${radius} m"
                picked?.let { drawCircle(it) }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(seek)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(8f), 0, dp(8f), dp(12f))

        val locBtn = MaterialButton(this)
        locBtn.cornerRadius = dp(20f)
        locBtn.text = "現在地へ移動"
        locBtn.setOnClickListener {
            val g = lastKnown()
            if (g != null) {
                map.controller.setZoom(17.0)
                map.controller.setCenter(g)
            } else {
                Toast.makeText(this, "現在地を取得できません", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(locBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val saveBtn = MaterialButton(this)
        saveBtn.cornerRadius = dp(20f)
        saveBtn.text = "この場所で保存"
        saveBtn.setOnClickListener {
            val p = picked
            if (p == null) {
                Toast.makeText(this, "先に地図をタップしてピンを置いてください", Toast.LENGTH_SHORT).show()
            } else {
                prefs.setPlace(key, p.latitude, p.longitude, radius)
                Toast.makeText(this, "${placeLabel}を保存しました", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        row.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        setContentView(root)

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) setPin(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) setPin(p)
                return true
            }
        }
        map.overlays.add(MapEventsOverlay(receiver))

        if (existing != null) {
            val g = GeoPoint(existing.lat, existing.lng)
            map.controller.setZoom(17.0)
            map.controller.setCenter(g)
            setPin(g)
        } else {
            val g = lastKnown()
            if (g != null) {
                map.controller.setZoom(16.0)
                map.controller.setCenter(g)
            } else {
                map.controller.setZoom(11.0)
                map.controller.setCenter(GeoPoint(35.6812, 139.7671))
            }
        }
    }

    private fun setPin(p: GeoPoint) {
        marker?.let { map.overlays.remove(it) }
        val m = Marker(map)
        m.position = p
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = placeLabel
        map.overlays.add(m)
        marker = m
        picked = p
        drawCircle(p)
        infoView.text = String.format(Locale.US, "%s: %.5f, %.5f", placeLabel, p.latitude, p.longitude)
    }

    /** 円が描けないosmdroidの標準機能の代わりに、多角形で円を近似して描く */
    private fun drawCircle(center: GeoPoint) {
        circle?.let { map.overlays.remove(it) }
        val points = ArrayList<GeoPoint>()
        val earthRadius = 6371000.0
        val latRad = Math.toRadians(center.latitude)
        for (i in 0..64) {
            val angle = 2.0 * Math.PI * i / 64
            val dLat = (radius * cos(angle)) / earthRadius
            val dLng = (radius * sin(angle)) / (earthRadius * cos(latRad))
            points.add(
                GeoPoint(
                    center.latitude + Math.toDegrees(dLat),
                    center.longitude + Math.toDegrees(dLng)
                )
            )
        }
        val poly = Polygon()
        poly.points = points
        poly.fillColor = Color.parseColor("#223F51B5")
        poly.strokeColor = Color.parseColor("#3F51B5")
        poly.strokeWidth = 3f
        map.overlays.add(poly)
        circle = poly
        map.invalidate()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): GeoPoint? {
        if (!Perm.hasLocation(this)) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (best != null) GeoPoint(best.latitude, best.longitude) else null
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }
}
