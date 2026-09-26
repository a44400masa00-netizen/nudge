package com.example.studynudge

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class LocationTracker(private val context: Context) : LocationListener {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val history = ArrayList<Location>()
    private var started = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !Perm.hasLocation(context)) return
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 60_000L, 0f, this, Looper.getMainLooper())
                }
            }
            started = true
        } catch (e: Exception) {
            // 権限や設定の問題。無視して続行
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (e: Exception) {
        }
        started = false
    }

    @Synchronized
    private fun add(l: Location) {
        history.add(l)
        val limit = System.currentTimeMillis() - 20 * 60_000L
        history.removeAll { it.time < limit }
    }

    override fun onLocationChanged(location: Location) {
        add(location)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @SuppressLint("MissingPermission")
    @Synchronized
    fun current(): Location? {
        val now = System.currentTimeMillis()
        val latest = history.lastOrNull()
        if (latest != null && now - latest.time < 10 * 60_000L) return latest
        if (!Perm.hasLocation(context)) return null
        try {
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (best != null && now - best.time < 30 * 60_000L) return best
        } catch (e: Exception) {
        }
        return null
    }

    /** 直近数分間の移動距離(m)と平均速度(km/h)を返す */
    @Synchronized
    fun movement(): Pair<Double, Double> {
        val now = System.currentTimeMillis()
        val recent = history.filter { now - it.time <= 6 * 60_000L }
        if (recent.size < 2) return Pair(0.0, 0.0)
        val a = recent.first()
        val b = recent.last()
        val sec = (b.time - a.time) / 1000.0
        if (sec < 90) return Pair(0.0, 0.0)
        val res = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
        val meters = res[0].toDouble()
        return Pair(meters, meters / sec * 3.6)
    }
}
