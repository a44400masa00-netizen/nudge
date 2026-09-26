package com.example.studynudge

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.location.Location
import android.provider.CalendarContract
import java.util.Calendar

class SituationCollector(private val context: Context, private val prefs: Prefs) {

    fun collect(tracker: LocationTracker, sessionStart: Long): Situation {
        val now = System.currentTimeMillis()

        // --- 位置 ---
        val loc = tracker.current()
        var placeKey: String? = null
        var placeLabel: String? = null
        if (loc != null) {
            var best = Float.MAX_VALUE
            for ((key, label) in PlaceKeys.ALL) {
                val p = prefs.getPlace(key) ?: continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lng, res)
                if (res[0] < p.radius.toFloat() && res[0] < best) {
                    best = res[0]
                    placeKey = key
                    placeLabel = label
                }
            }
        }
        val mv = tracker.movement()
        val moving = loc != null && mv.first >= 250.0 && mv.second >= 4.0

        // --- カレンダー ---
        val events = todayEvents()
        val current = events?.firstOrNull { !it.allDay && it.begin <= now && now < it.end }
        val next = events?.filter { !it.allDay && it.begin > now }?.minByOrNull { it.begin }

        // --- スマホ使用状況 ---
        var fgPkg: String? = null
        var fgLabel: String? = null
        var fgCat: String? = null
        var todayMin: Int? = null
        if (Perm.hasUsage(context)) {
            fgPkg = foregroundPackage(now)
            if (fgPkg != null) {
                val info = appInfo(fgPkg)
                fgLabel = info.first
                fgCat = info.second
            }
            todayMin = todayUsageMinutes(now)
        }
        val sessionMin = if (sessionStart > 0) ((now - sessionStart) / 60000L).toInt() else 0

        return Situation(
            nowMillis = now,
            placeKey = placeKey,
            placeLabel = placeLabel,
            hasLocation = loc != null,
            moving = moving,
            speedKmh = mv.second,
            hasCalendar = events != null,
            events = events ?: emptyList(),
            currentEvent = current,
            nextEvent = next,
            foregroundPackage = fgPkg,
            foregroundLabel = fgLabel,
            foregroundCategory = fgCat,
            sessionMinutes = sessionMin,
            todayUsageMinutes = todayMin
        )
    }

    private fun todayEvents(): List<CalEvent>? {
        if (!Perm.hasCalendar(context)) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = start + 24L * 3600L * 1000L
        val list = ArrayList<CalEvent>()
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, start)
            ContentUris.appendId(b, end)
            val proj = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
            )
            val cursor = context.contentResolver.query(
                b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC"
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    list.add(
                        CalEvent(
                            c.getString(0) ?: "(無題)",
                            c.getLong(1),
                            c.getLong(2),
                            c.getInt(3) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return null
        }
        return list
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(now: Long): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(now - 15 * 60_000L, now)
            val e = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.packageName
                } else if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && e.packageName == pkg) {
                    pkg = null
                }
            }
            pkg
        } catch (ex: Exception) {
            null
        }
    }

    private fun appInfo(pkg: String): Pair<String, String?> {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(ai).toString()
            val cat = when (ai.category) {
                ApplicationInfo.CATEGORY_GAME -> "ゲーム"
                ApplicationInfo.CATEGORY_VIDEO -> "動画"
                ApplicationInfo.CATEGORY_SOCIAL -> "SNS"
                ApplicationInfo.CATEGORY_AUDIO -> "音楽"
                ApplicationInfo.CATEGORY_NEWS -> "ニュース"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "仕事効率化"
                else -> null
            }
            Pair(label, cat)
        } catch (e: Exception) {
            Pair(pkg, null)
        }
    }

    private fun todayUsageMinutes(now: Long): Int? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            var total = 0L
            for (s in stats) {
                if (s.packageName == context.packageName) continue
                if (s.lastTimeStamp < start) continue
                total += s.totalTimeInForeground
            }
            (total / 60000L).toInt()
        } catch (e: Exception) {
            null
        }
    }
}
