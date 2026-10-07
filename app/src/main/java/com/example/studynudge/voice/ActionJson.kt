package com.example.studynudge.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * AIの返事を、自由な文章ではなく、決まった形のJSONにする（形式の崩れを防ぐ）。
 *   {"reply":"3分のタイマーをセットしますね。","action":"set_timer","seconds":180}
 * 受け取ったあと toLegacy() で、既存の処理が読める "返事[[ACTION:…]]" の形に直す。
 *
 * 形式を「お願い」するのではなく、AI側の仕組み（Geminiの responseSchema、
 * パソコン側ページの JSON スキーマ指定）で「強制」するのがねらい。
 */
object ActionJson {

  /** 返事の形（JSON Schema）。パソコンのAI中継ページにも、この文字列をそのまま渡す */
  const val SCHEMA_JSON = """{"type":"object","properties":{"reply":{"type":"string"},"action":{"type":"string","enum":["none","set_timer","set_alarm","show_alarms","add_calendar_event","compose_email","open_google_home","flashlight","volume","brightness","battery_saver","do_not_disturb","ringer_mode","open_app","play_music","media","open_settings"]},"seconds":{"type":"integer"},"label":{"type":"string"},"hour":{"type":"integer"},"minute":{"type":"integer"},"title":{"type":"string"},"year":{"type":"integer"},"month":{"type":"integer"},"day":{"type":"integer"},"duration_minutes":{"type":"integer"},"to":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"},"on":{"type":"boolean"},"stream":{"type":"string","enum":["media","ring","alarm"]},"percent":{"type":"integer"},"delta":{"type":"string","enum":["up","down"]},"mode":{"type":"string","enum":["normal","vibrate","silent"]},"name":{"type":"string"},"query":{"type":"string"},"command":{"type":"string","enum":["pause","play","next","previous","stop"]},"screen":{"type":"string","enum":["wifi","bluetooth","airplane","display","sound","battery","location","mobile","other"]}},"required":["reply","action"]}"""

  /** 操作ごとに、使う項目 */
  private val ACTION_KEYS: Map<String, List<String>> = mapOf(
    "set_timer" to listOf("seconds", "label"),
    "set_alarm" to listOf("hour", "minute", "label"),
    "show_alarms" to emptyList(),
    "add_calendar_event" to listOf("title", "year", "month", "day", "hour", "minute", "duration_minutes"),
    "compose_email" to listOf("to", "subject", "body"),
    "open_google_home" to emptyList(),
    "flashlight" to listOf("on"),
    "volume" to listOf("stream", "percent", "delta"),
    "brightness" to listOf("percent"),
    "battery_saver" to listOf("on"),
    "do_not_disturb" to listOf("on"),
    "ringer_mode" to listOf("mode"),
    "open_app" to listOf("name"),
    "play_music" to listOf("query"),
    "media" to listOf("command"),
    "open_settings" to listOf("screen")
  )

  /** Gemini の responseSchema 形式（型名が大文字、列挙には format=enum）に直したもの */
  fun geminiSchema(): JSONObject {
    val props = JSONObject(SCHEMA_JSON).getJSONObject("properties")
    val out = JSONObject()
    val order = JSONArray()
    val keys = props.keys()
    while (keys.hasNext()) {
      val k = keys.next()
      order.put(k)
      val p = props.getJSONObject(k)
      val q = JSONObject()
      q.put("type", p.getString("type").uppercase())
      if (p.has("enum")) {
        q.put("format", "enum")
        q.put("enum", p.getJSONArray("enum"))
      }
      out.put(k, q)
    }
    return JSONObject()
      .put("type", "OBJECT")
      .put("properties", out)
      .put("required", JSONArray().put("reply").put("action"))
      .put("propertyOrdering", order)
  }

  /**
   * AIが返したJSONを、既存の処理が読める "返事[[ACTION:操作 {JSON}]]" の形に直す。
   * JSONとして読めなかったとき（形式の指定が効かなかったとき）は、元の文字列をそのまま返す
   * （昔の書き方で返ってきた場合も、そのまま動く）。
   */
  fun toLegacy(raw: String): String {
    val a = raw.indexOf('{')
    val b = raw.lastIndexOf('}')
    if (a < 0 || b <= a) return raw
    val obj = try {
      JSONObject(raw.substring(a, b + 1))
    } catch (e: Exception) {
      return raw
    }
    if (!obj.has("reply") && !obj.has("action")) return raw

    val reply = (if (obj.isNull("reply")) "" else obj.optString("reply", "")).trim().ifBlank { "はい。" }
    val action = (if (obj.isNull("action")) "none" else obj.optString("action", "none")).trim()
    val keys = ACTION_KEYS[action] ?: return reply // none や、知らない操作は、返事だけ

    val args = JSONObject()
    for (k in keys) {
      if (obj.has(k) && !obj.isNull(k)) args.put(k, obj.get(k))
    }
    return reply + "[[ACTION:" + action + " " + args.toString() + "]]"
  }
}
