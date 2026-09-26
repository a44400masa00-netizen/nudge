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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MapPickerActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var infoView: TextView
    private lateinit var radiusView: TextView
    private var gmap: GoogleMap? = null
    private var pin: Marker? = null
    private var circle: Circle? = null
    private var picked: LatLng? = null
    private var radius = 150
    private var placeLabel = ""

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra("key") ?: "home"
        placeLabel = intent.getStringExtra("label") ?: key
        val prefs = Prefs(this)
        val existing = prefs.getPlace(key)
        if (existing != null) radius = existing.radius

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(0, dp(28f), 0, 0)

        mapView = MapView(this)
        root.addView(mapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        infoView = TextView(this)
        infoView.text = "「${placeLabel}」の場所を、地図をタップして指定してください"
        infoView.setPadding(dp(16f), dp(8f), dp(16f), dp(0f))
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
                circle?.radius = radius.toDouble()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(seek)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(8f), 0, dp(8f), dp(12f))

        val locBtn = MaterialButton(this)
        locBtn.text = "現在地へ移動"
        locBtn.setOnClickListener {
            val g = lastKnown()
            val m = gmap
            if (g != null && m != null) {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(g, 17f))
            } else {
                Toast.makeText(this, "現在地を取得できません", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(locBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val saveBtn = MaterialButton(this)
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

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { g ->
            gmap = g
            g.uiSettings.isZoomControlsEnabled = true
            g.setOnMapClickListener { setPin(it) }
            g.setOnMapLongClickListener { setPin(it) }
            if (Perm.hasLocation(this)) {
                try {
                    g.isMyLocationEnabled = true
                } catch (e: SecurityException) {
                }
            }
            if (existing != null) {
                val ll = LatLng(existing.lat, existing.lng)
                g.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 17f))
                setPin(ll)
            } else {
                val ll = lastKnown()
                if (ll != null) {
                    g.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 16f))
                } else {
                    g.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.6812, 139.7671), 11f))
                }
            }
        }
    }

    private fun setPin(p: LatLng) {
        val g = gmap ?: return
        pin?.remove()
        circle?.remove()
        pin = g.addMarker(MarkerOptions().position(p).title(placeLabel))
        circle = g.addCircle(
            CircleOptions()
                .center(p)
                .radius(radius.toDouble())
                .strokeColor(Color.parseColor("#3F51B5"))
                .strokeWidth(3f)
                .fillColor(Color.parseColor("#223F51B5"))
        )
        picked = p
        infoView.text = String.format(Locale.US, "%s: %.5f, %.5f", placeLabel, p.latitude, p.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): LatLng? {
        if (!Perm.hasLocation(this)) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (best != null) LatLng(best.latitude, best.longitude) else null
        } catch (e: Exception) {
            null
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
