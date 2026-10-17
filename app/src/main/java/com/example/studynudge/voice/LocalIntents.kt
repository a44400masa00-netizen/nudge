package com.example.studynudge.voice

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar

/**
 * 定型の操作（タイマー・アラーム・ライト・音量・音楽・アプリ起動など）を、AIを通さず、
 * アプリ内で言葉を直接判定して実行する。一瞬で反応し、間違えず、AIの無料枠も使わない。
 * 判定できなかった言葉だけが、AI（パソコン／Gemini）に渡される。
 *
 * ※ 判定ロジックは JavaScript で作って、94通りの言い回しで試験してから、ここに移した。
 */
object LocalIntents {

  data class Parsed(val action: String, val args: Map<String, Any>, val reply: String)

  /**
   * 認識結果を判定し、AIの返事と同じ形式 "返事[[ACTION:操作 {JSON}]]" にして返す。
   * 該当しなければ null（→ AIに任せる）。
   * 呼びかけ(addressed)でない聞き取りでは、テレビや周囲の声の誤作動を防ぐため、短い言葉だけを対象にする。
   */
  fun match(ctx: Context, rawText: String, addressed: Boolean): String? {
    if (!addressed && rawText.length > 30) return null
    val parsed = parse(rawText) { name -> AppFinder.find(ctx, name) != null } ?: return null
    return toLegacy(parsed)
  }

  fun toLegacy(p: Parsed): String {
    val json = JSONObject()
    for ((k, v) in p.args) json.put(k, v)
    return p.reply + "[[ACTION:" + p.action + " " + json.toString() + "]]"
  }

  // ---------------------------------------------------------------------------------------

  private val KANJI_D = mapOf(
    '〇' to 0, '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
  )

  private fun kanjiVal(s: String): Int? {
    var total = 0
    var cur = 0
    for (ch in s) {
      when {
        ch == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
        ch == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
        KANJI_D.containsKey(ch) -> cur = KANJI_D.getValue(ch)
        else -> return null
      }
    }
    return total + cur
  }

  // 「一時停止」の「一時」を、時刻の「1時」にしてしまわないよう、時(?!停止|的) とする
  private val KANJI_RE = Regex("""([〇零一二三四五六七八九十百]+)(?=時間|分|秒|時(?!停止|的)|パーセント|%)""")
  private fun kanjiToDigits(s: String): String =
    KANJI_RE.replace(s) { m -> kanjiVal(m.value)?.toString() ?: m.value }

  private fun fmtDur(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    val sb = StringBuilder()
    if (h > 0) sb.append(h).append("時間")
    if (m > 0) sb.append(m).append("分")
    if (s > 0) sb.append(s).append("秒")
    return if (sb.isEmpty()) "0秒" else sb.toString()
  }

  private val RE_H_HALF = Regex("""(\d{1,5})時間半""")
  private val RE_H = Regex("""(\d{1,5})時間""")
  private val RE_M_HALF = Regex("""(\d{1,5})分半""")
  private val RE_M = Regex("""(\d{1,5})分""")
  private val RE_S = Regex("""(\d{1,5})秒""")

  private fun durationSeconds(t: String): Int {
    var sec = 0
    val hh = RE_H_HALF.find(t)
    if (hh != null) {
      sec += hh.groupValues[1].toInt() * 3600 + 1800
    } else {
      val h = RE_H.find(t)
      if (h != null) sec += h.groupValues[1].toInt() * 3600
    }
    val mh = RE_M_HALF.find(t)
    if (mh != null) {
      sec += mh.groupValues[1].toInt() * 60 + 30
    } else {
      val m = RE_M.find(t)
      if (m != null) sec += m.groupValues[1].toInt() * 60
    }
    val s = RE_S.find(t)
    if (s != null) sec += s.groupValues[1].toInt()
    return sec
  }

  private val RE_CLOCK_COLON = Regex("""(\d{1,2}):(\d{2})""")
  private val RE_CLOCK_JI = Regex("""(\d{1,2})時(?!間)(半|(\d{1,2})分)?""")
  private val RE_NIGHT = Regex("""(夜中|深夜|未明)""")
  private val RE_AM = Regex("""(午前|朝|早朝|明朝)""")
  private val RE_PM = Regex("""(午後|夕方|夜|今夜|晩|昼)""")

  private fun parseClock(t: String): Pair<Int, Int>? {
    var h: Int
    var min: Int
    val idx: Int
    val c = RE_CLOCK_COLON.find(t)
    if (c != null) {
      h = c.groupValues[1].toInt()
      min = c.groupValues[2].toInt()
      idx = c.range.first
    } else {
      val j = RE_CLOCK_JI.find(t) ?: return null
      h = j.groupValues[1].toInt()
      min = when {
        j.groupValues[2] == "半" -> 30
        j.groupValues[3].isNotEmpty() -> j.groupValues[3].toInt()
        else -> 0
      }
      idx = j.range.first
    }
    val before = t.substring(maxOf(0, idx - 6), idx)
    val night = RE_NIGHT.containsMatchIn(before)
    val am = night || RE_AM.containsMatchIn(before)
    val pm = !night && RE_PM.containsMatchIn(before)
    if (pm && h < 12) h += 12
    else if (pm && h == 12 && before.contains("夜")) h = 0
    if (am && h == 12) h = 0
    if (h == 24) h = 0
    if (h > 23 || min > 59) return null
    return Pair(h, min)
  }

  // ---- カレンダーの予定 ----------------------------------------------------------------
  // 「明日の15時から歯医者の予定を入れて」→ 日付・時刻・長さ・題名。
  // 日付の計算（明日は何月何日か）は、AIにやらせると間違えるので、ここで確実に行う。

  private val WD = mapOf('月' to 1, '火' to 2, '水' to 3, '木' to 4, '金' to 5, '土' to 6, '日' to 7)

  private fun sod(c: Calendar): Calendar {
    val x = c.clone() as Calendar
    x.set(Calendar.HOUR_OF_DAY, 0)
    x.set(Calendar.MINUTE, 0)
    x.set(Calendar.SECOND, 0)
    x.set(Calendar.MILLISECOND, 0)
    return x
  }

  private fun plusDays(c: Calendar, n: Int): Calendar {
    val x = sod(c)
    x.add(Calendar.DAY_OF_MONTH, n)
    return x
  }

  /** 月曜=1 … 日曜=7 */
  private fun dow1(c: Calendar): Int {
    val d = c.get(Calendar.DAY_OF_WEEK)
    return if (d == Calendar.SUNDAY) 7 else d - 1
  }

  private fun ymd(year: Int, month0: Int, day: Int): Calendar {
    val x = Calendar.getInstance()
    x.clear()
    x.set(year, month0, day, 0, 0, 0)
    return x
  }

  /** bare = true は「金曜」のように、週の指定がないもの */
  private class DateHit(val date: Calendar, val bare: Boolean)

  private val RE_MD = Regex("""(\d{1,2})月(\d{1,2})日""")
  private val RE_NEXTM = Regex("""(来月|再来月)の?(\d{1,2})日""")
  private val RE_ASATTE = Regex("""(明後日|あさって)""")
  private val RE_ASHITA = Regex("""(明日|あした|あす)""")
  private val RE_KYOU = Regex("""(今日|きょう|本日)""")
  private val RE_DAYS_LATER = Regex("""(\d{1,2})日後""")
  private val RE_WEEKDAY_REL = Regex("""(再来週|来週|今週)の?([月火水木金土日])曜日?""")
  private val RE_WEEKDAY = Regex("""([月火水木金土日])曜日?""")
  private val RE_DAY_ONLY = Regex("""(\d{1,2})日(?!後|間)""")

  private fun parseDateWord(t: String, now: Calendar): DateHit? {
    val today = sod(now)
    val year = today.get(Calendar.YEAR)
    var m = RE_MD.find(t)
    if (m != null) {
      val mo = m.groupValues[1].toInt() - 1
      val da = m.groupValues[2].toInt()
      var d = ymd(year, mo, da)
      if (d.before(today)) d = ymd(year + 1, mo, da)
      return DateHit(d, false)
    }
    m = RE_NEXTM.find(t)
    if (m != null) {
      val add = if (m.groupValues[1] == "来月") 1 else 2
      return DateHit(ymd(year, today.get(Calendar.MONTH) + add, m.groupValues[2].toInt()), false)
    }
    if (t.contains("明々後日")) return DateHit(plusDays(today, 3), false)
    if (RE_ASATTE.containsMatchIn(t)) return DateHit(plusDays(today, 2), false)
    if (RE_ASHITA.containsMatchIn(t)) return DateHit(plusDays(today, 1), false)
    if (RE_KYOU.containsMatchIn(t)) return DateHit(today, false)
    m = RE_DAYS_LATER.find(t)
    if (m != null) return DateHit(plusDays(today, m.groupValues[1].toInt()), false)
    m = RE_WEEKDAY_REL.find(t)
    if (m != null) {
      val wk = when (m.groupValues[1]) { "今週" -> 0; "来週" -> 1; else -> 2 }
      val mon = plusDays(today, -(dow1(today) - 1))
      var d = plusDays(mon, wk * 7 + (WD.getValue(m.groupValues[2][0]) - 1))
      if (d.before(today)) d = plusDays(d, 7) // 今週の、すでに過ぎた曜日は、来週にする
      return DateHit(d, false)
    }
    m = RE_WEEKDAY.find(t)
    if (m != null) {
      val diff = (WD.getValue(m.groupValues[1][0]) - dow1(today) + 7) % 7
      return DateHit(plusDays(today, diff), diff == 0)
    }
    m = RE_DAY_ONLY.find(t)
    if (m != null) {
      val dd = m.groupValues[1].toInt()
      var d = ymd(year, today.get(Calendar.MONTH), dd)
      if (d.before(today)) d = ymd(year, today.get(Calendar.MONTH) + 1, dd)
      return DateHit(d, false)
    }
    return null
  }

  private val RE_CAL_WORD = Regex("""(予定|スケジュール|カレンダー|アポ|予約)""")
  private val RE_CAL_VERB = Regex("""(入れて|いれて|入れとい|追加|登録|作って|つくって|書いて|メモ|ブロック|セット)""")
  private val RE_CLOCK_TOK = Regex("""(午前|午後|朝|昼|夕方|夜中|夜|今夜|深夜|早朝)?の?(\d{1,2}:\d{2}|\d{1,2}時(?!間)(半|\d{1,2}分)?)(から|に|まで|ごろ|頃)?""")
  private val RE_T1 = Regex("""(明々後日|明後日|あさって|明日|あした|あす|今日|きょう|本日)(の)?""")
  private val RE_T2 = Regex("""(再来週|来週|今週)(の)?[月火水木金土日]曜日?(の)?""")
  private val RE_T3 = Regex("""[月火水木金土日]曜日?(の)?""")
  private val RE_T4 = Regex("""(再来月|来月)(の)?\d{1,2}日(の)?""")
  private val RE_T5 = Regex("""\d{1,2}月\d{1,2}日(の)?""")
  private val RE_T6 = Regex("""\d{1,2}日後(の)?""")
  private val RE_T7 = Regex("""\d{1,2}日(?!後|間)(の)?""")
  private val RE_T8 = Regex("""\d+時間半?(の)?""")
  private val RE_T9 = Regex("""\d+分間?(の)?""")
  private val RE_T10 = Regex("""カレンダー(に|へ)?""")
  private val RE_TITLE_TAIL = Regex("""(の)?(予定|スケジュール|予約|アポ)?(を)?(入れて|いれて|入れといて|追加して|追加|登録して|登録|作って|つくって|書いて|メモして|ブロックして|セットして)(ください|くれる|お願い|ね|よ)*${'$'}""")
  private val RE_TITLE_LEAD = Regex("""^(の|に|で|と|は)+""")
  private val RE_TITLE_TRAIL = Regex("""(の|に|を|は)+${'$'}""")
  private val RE_TITLE_ONLY = Regex("""^(予定|スケジュール|予約|アポ)${'$'}""")

  private fun parseCalendar(o: String, t: String, now: Calendar): Parsed? {
    if (!RE_CAL_WORD.containsMatchIn(t)) return null
    if (!RE_CAL_VERB.containsMatchIn(t)) return null
    if (RE_CLOCK_WORD.containsMatchIn(t)) return null
    val dh = parseDateWord(t, now) ?: return null
    val c = parseClock(t) ?: return null
    var date = dh.date
    if (dh.bare && date.timeInMillis == sod(now).timeInMillis &&
      c.first * 60 + c.second <= now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    ) {
      date = plusDays(date, 7) // 「月曜」と言って、今日がその月曜で、時刻がもう過ぎていたら、来週
    }
    // 長さ: 「15時から17時まで」または「2時間」など。なければ1時間
    var dur = 60
    val clocks = RE_CLOCK_JI.findAll(t).toList()
    if (clocks.size >= 2 && t.contains("まで")) {
      val e = clocks[1]
      var h2 = e.groupValues[1].toInt()
      val m2 = when {
        e.groupValues[2] == "半" -> 30
        e.groupValues[3].isNotEmpty() -> e.groupValues[3].toInt()
        else -> 0
      }
      if (h2 < c.first && h2 < 12) h2 += 12
      val diff = h2 * 60 + m2 - (c.first * 60 + c.second)
      if (diff > 0) dur = diff
    } else {
      val rest = RE_CLOCK_JI.replace(RE_CLOCK_COLON.replace(t, ""), "")
      val ds = durationSeconds(rest)
      if (ds >= 300) dur = Math.round(ds / 60.0).toInt()
    }
    dur = minOf(maxOf(dur, 5), 1440)
    // 題名: 日付・時刻・長さ・「予定を入れて」を取り除いた残り
    var ti = o
    ti = RE_T1.replace(ti, "")
    ti = RE_T2.replace(ti, "")
    ti = RE_T3.replace(ti, "")
    ti = RE_T4.replace(ti, "")
    ti = RE_T5.replace(ti, "")
    ti = RE_T6.replace(ti, "")
    ti = RE_T7.replace(ti, "")
    ti = RE_CLOCK_TOK.replace(ti, "")
    ti = RE_T8.replace(ti, "")
    ti = RE_T9.replace(ti, "")
    ti = RE_T10.replace(ti, "").trim()
    ti = RE_TITLE_TAIL.replace(ti, "")
    ti = RE_TITLE_LEAD.replace(ti, "")
    ti = RE_TITLE_TRAIL.replace(ti, "").trim()
    if (ti.isEmpty() || RE_TITLE_ONLY.matches(ti)) ti = "予定"
    if (ti.length > 40) ti = ti.substring(0, 40)
    val month = date.get(Calendar.MONTH) + 1
    val day = date.get(Calendar.DAY_OF_MONTH)
    val mm = if (c.second > 0) "${c.second}分" else ""
    return Parsed(
      "add_calendar_event",
      mapOf(
        "title" to ti, "year" to date.get(Calendar.YEAR), "month" to month, "day" to day,
        "hour" to c.first, "minute" to c.second, "duration_minutes" to dur
      ),
      "${month}月${day}日の${c.first}時${mm}に、「${ti}」の予定を入れますね。"
    )
  }

  private val OPEN_VERB = Regex("""(開いて|開く|出して|見せて|表示して|起動して|立ち上げて)""")
  private val BAD_APP = Regex("""(ドア|窓|扉|ファイル|ページ|サイト|メール|カレンダー|設定|写真|フォルダ|蓋|鍵|傘|口|本|手|心|目)""")
  private val MUSIC_BAD = Regex("""(動画|ビデオ|映画|ドラマ|アニメ|ニュース|ラジオ|テレビ|ポッドキャスト|水|トイレ|録音|ライト|音量)""")
  private val KAKETE_BAD = Regex("""(電話|鍵|タイマー|アラーム|電気|エアコン|ロック|保険|声|迷惑|時間|お願い)""")
  private val MUSIC_VERBS = Regex("""(流して|再生して|聴かせて|聞かせて|かけて|聴きたい|聞きたい)""")
  private val MUSIC_TAIL = Regex("""^(ください|下さい|くれ|くれる|ほしい|欲しい|お願い|おねがい|もらえる|いい|ね|よ|な|かな|です|ます|ませんか)*${'$'}""")
  private val MUSIC_WORDS = Regex("""(曲|音楽|ミュージック|プレイリスト|bgm|歌|アルバム)""", RegexOption.IGNORE_CASE)
  private val MUSIC_GENERIC = Regex("""^(音楽|曲|歌|ミュージック|bgm)?${'$'}""", RegexOption.IGNORE_CASE)
  private val PUNCT = Regex("""[、。，,.！!？?「」『』~〜]""")

  private data class Music(val generic: Boolean, val query: String = "", val app: String? = null)

  private fun extractMusic(o: String, isApp: (String) -> Boolean): Music? {
    val vm = MUSIC_VERBS.findAll(o).toList()
    if (vm.isEmpty()) return null
    val v = vm.last()
    val tail = o.substring(v.range.last + 1).replace(Regex("""\s"""), "")
    if (!MUSIC_TAIL.matches(tail)) return null
    var q = o.substring(0, v.range.first).trim()
    q = q.replace(Regex("""(という|っていう)(曲|歌|音楽)?(を)?${'$'}"""), "")
      .replace(Regex("""(の曲|の歌|の音楽|の楽曲|の新曲)(を)?${'$'}"""), "")
      .replace(Regex("""(を|は|で)${'$'}"""), "")
      .trim()
    if (v.groupValues[1] == "かけて") {
      if (!MUSIC_WORDS.containsMatchIn(o)) return null
      if (KAKETE_BAD.containsMatchIn(q)) return null
    }
    if (MUSIC_BAD.containsMatchIn(q)) return null
    if (MUSIC_GENERIC.matches(q)) return Music(generic = true)
    var app: String? = null
    val di = q.indexOf('で')
    if (di > 0 && di < q.length - 1) {
      val left = q.substring(0, di).trim()
      val right = q.substring(di + 1).trim()
      if (left.isNotEmpty() && right.isNotEmpty() && isApp(left)) {
        app = left
        q = right
      }
    }
    return Music(generic = false, query = q, app = app)
  }

  private val RE_APP = Regex("""^(.+?)\s*(?:を|アプリを|のアプリを)?\s*(開いて|開く|起動して|起動する|立ち上げて)(?:ください|くれ|お願い|ね|よ)*${'$'}""")

  private fun extractApp(o: String, isApp: (String) -> Boolean): String? {
    val m = RE_APP.find(o) ?: return null
    val name = m.groupValues[1].replace(Regex("""(の)?アプリ${'$'}"""), "").replace(Regex("""を${'$'}"""), "").trim()
    if (name.isEmpty() || name.length > 20 || BAD_APP.containsMatchIn(name)) return null
    return if (isApp(name)) name else null
  }

  // ---------------------------------------------------------------------------------------

  private val RE_CLOCK_WORD = Regex("""(アラーム|タイマー|目覚まし)""")
  private val RE_CANCEL = Regex("""(止め|とめ|消し|けし|キャンセル|解除|取り消|やめ|オフ|ストップ|リセット|削除|一覧|確認|見せ|見たい|リスト)""")
  private val RE_GUARD = Regex("""(は何|って何|ってなに|とは|でしょうか|ないで|ずに)""")
  private val RE_WAKE = Regex("""^(へい|ヘイ|hey)?(でいりー|デイリー|daily)""")
  private val RE_FILLER = Regex("""^(ねえ|ねぇ|あの|えっと|ちょっと|すみません|お願い|おねがい)+""")
  private val RE_WS = Regex("""\s+""")
  private val RE_PERCENT1 = Regex("""(\d{1,3})(%|パーセント)""")
  private val RE_VOL_NUM = Regex("""(?:音量|ボリューム)(?:を)?(\d{1,3})(?:に|にして|まで|くらい|ぐらい)?""")
  private val RE_BRIGHT_NUM = Regex("""(?:明るさ|輝度)(?:を)?(\d{1,3})""")
  private val OFF_W = Regex("""(オフ|解除|無効|切|やめ|止め|終わり|外)""")
  private val ON_W = Regex("""(オン|つけ|付け|入れ|にして|有効|開始|する)""")
  private val RE_AFTER = Regex("""(時間|分|秒)(半)?(間)?後""")
  private val RE_AFTER_WORD = Regex("""(教えて|知らせて|通知|声をかけて|呼んで|起こして|お知らせ|リマインド|アラーム|タイマー|アラート)""")
  private val RE_MEASURE = Regex("""(\d+)(時間|分|秒)(間)?(計って|はかって|測って)""")

  /** @return 該当なしなら null */
  fun parse(raw: String, now: Calendar = Calendar.getInstance(), isApp: (String) -> Boolean): Parsed? {
    val orig = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
    if (orig.isEmpty() || orig.length > 60) return null
    val oSp = PUNCT.replace(orig, "") // 空白は残す（曲名などの取り出し用）
    val o = kanjiToDigits(oSp)
    var t = RE_WS.replace(o, "").lowercase()
    t = RE_WAKE.replace(t, "")
    t = RE_FILLER.replace(t, "")
    if (t.isEmpty()) return null
    // 質問（〜は何？）や否定（〜しないで）は、命令ではないので、AIに任せる
    if (RE_GUARD.containsMatchIn(t)) return null

    fun r(action: String, args: Map<String, Any>, reply: String) = Parsed(action, args, reply)

    // 1) アラーム・タイマーの取り消し／一覧
    if (RE_CLOCK_WORD.containsMatchIn(t) && RE_CANCEL.containsMatchIn(t)) {
      return r("show_alarms", emptyMap(), "時計アプリの一覧を開きますね。")
    }
    // 2) 音量
    if (t.contains("音量") || t.contains("ボリューム")) {
      val stream = when {
        Regex("""(着信|呼び出し|通知音|ベル)""").containsMatchIn(t) -> "ring"
        t.contains("アラーム") -> "alarm"
        else -> "media"
      }
      val pm = RE_PERCENT1.find(t) ?: RE_VOL_NUM.find(t)
      if (pm != null) {
        val p = minOf(100, pm.groupValues[1].toInt())
        return r("volume", mapOf("stream" to stream, "percent" to p), "音量を${p}%にしますね。")
      }
      if (Regex("""(最大|マックス|フル)""").containsMatchIn(t)) {
        return r("volume", mapOf("stream" to stream, "percent" to 100), "音量を最大にしますね。")
      }
      if (Regex("""(ミュート|消音|ゼロ)""").containsMatchIn(t)) {
        return r("volume", mapOf("stream" to stream, "percent" to 0), "音量をゼロにしますね。")
      }
      if (Regex("""(上げ|あげ|大きく|アップ|増や|ふやし)""").containsMatchIn(t)) {
        return r("volume", mapOf("stream" to stream, "delta" to "up"), "音量を上げますね。")
      }
      if (Regex("""(下げ|さげ|小さく|ダウン|減ら|へらし|絞)""").containsMatchIn(t)) {
        return r("volume", mapOf("stream" to stream, "delta" to "down"), "音量を下げますね。")
      }
    }
    // 3) 画面の明るさ（数字で言われたときだけ）
    if (t.contains("明るさ") || t.contains("輝度")) {
      val pm = RE_PERCENT1.find(t) ?: RE_BRIGHT_NUM.find(t)
      if (pm != null) {
        val p = minOf(100, pm.groupValues[1].toInt())
        return r("brightness", mapOf("percent" to p), "画面の明るさを${p}%にしますね。")
      }
      if (Regex("""(最大|マックス)""").containsMatchIn(t)) {
        return r("brightness", mapOf("percent" to 100), "画面の明るさを最大にしますね。")
      }
    }
    // 4) ライト
    if (Regex("""(ライト|懐中電灯|フラッシュ|トーチ)""").containsMatchIn(t) &&
      !Regex("""(部屋|照明|電気|リビング|寝室|ホーム|ライトニング|ヘッドライト|ライトノベル|ライトアップ)""").containsMatchIn(t)
    ) {
      if (Regex("""(消し|けし|切っ|切って|オフ|止め|とめ)""").containsMatchIn(t)) {
        return r("flashlight", mapOf("on" to false), "ライトを消しますね。")
      }
      if (Regex("""(つけ|付け|点け|オン|ともし|光らせ|点灯|照らし)""").containsMatchIn(t)) {
        return r("flashlight", mapOf("on" to true), "ライトをつけますね。")
      }
    }
    // 5) 電力モード・おやすみモード・マナーモード
    if (Regex("""(バッテリーセーバー|省電力|低電力|電力モード|節電モード)""").containsMatchIn(t)) {
      if (OFF_W.containsMatchIn(t)) return r("battery_saver", mapOf("on" to false), "電力モードをオフにしますね。")
      if (ON_W.containsMatchIn(t)) return r("battery_saver", mapOf("on" to true), "電力モードをオンにしますね。")
    }
    if (Regex("""(おやすみモード|サイレントモード|集中モード)""").containsMatchIn(t)) {
      if (OFF_W.containsMatchIn(t)) return r("do_not_disturb", mapOf("on" to false), "おやすみモードをオフにしますね。")
      if (ON_W.containsMatchIn(t)) return r("do_not_disturb", mapOf("on" to true), "おやすみモードをオンにしますね。")
    }
    if (t.contains("マナーモード")) {
      return if (Regex("""(解除|オフ|やめ|戻|通常|切|外)""").containsMatchIn(t)) {
        r("ringer_mode", mapOf("mode" to "normal"), "マナーモードを解除しますね。")
      } else {
        r("ringer_mode", mapOf("mode" to "vibrate"), "マナーモードにしますね。")
      }
    }
    if (t.contains("消音モード") || t.contains("無音モード")) {
      return r("ringer_mode", mapOf("mode" to "silent"), "消音にしますね。")
    }
    // 5.5) カレンダーの予定
    val calHit = parseCalendar(o, t, now)
    if (calHit != null) return calHit
    // 6) タイマー
    val sec = durationSeconds(t)
    val afterTimer = RE_AFTER.containsMatchIn(t) && RE_AFTER_WORD.containsMatchIn(t)
    val measure = RE_MEASURE.containsMatchIn(t)
    if ((t.contains("タイマー") || afterTimer || measure) && sec in 1..86400) {
      return r("set_timer", mapOf("seconds" to sec), "${fmtDur(sec)}のタイマーをセットしますね。")
    }
    // 7) アラーム
    if (Regex("""(アラーム|目覚まし|起こして|起きたい|起床)""").containsMatchIn(t)) {
      val c = parseClock(t)
      if (c != null) {
        val when_ = if (c.second > 0) "${c.first}時${c.second}分" else "${c.first}時"
        return r("set_alarm", mapOf("hour" to c.first, "minute" to c.second), "${when_}にアラームをセットしますね。")
      }
    }
    // 8) 音楽の操作（再生中の音楽）
    if (Regex("""(一時停止|ポーズ)""").containsMatchIn(t)) return r("media", mapOf("command" to "pause"), "一時停止しますね。")
    if (Regex("""(次の曲|次の歌|スキップ|曲を飛ばし|曲を送)""").containsMatchIn(t)) return r("media", mapOf("command" to "next"), "次の曲にしますね。")
    if (Regex("""(前の曲|前の歌|一つ前|ひとつ前|1つ前)""").containsMatchIn(t)) return r("media", mapOf("command" to "previous"), "前の曲にしますね。")
    if (Regex("""(音楽|曲|再生|ミュージック|プレイヤー|歌)(を)?(止め|とめ|停止|ストップ)""").containsMatchIn(t)) {
      return r("media", mapOf("command" to "pause"), "音楽を止めますね。")
    }
    if (Regex("""^(再生|音楽を再生|曲を再生|音楽を再開|再生を再開|再開)(して|お願い)?${'$'}""").containsMatchIn(t) ||
      Regex("""^(音楽|曲|ミュージック)(を)?(かけ|流し)(て)?${'$'}""").containsMatchIn(t)
    ) {
      return r("media", mapOf("command" to "play"), "再生しますね。")
    }
    // 9) 設定画面・Google Home
    if (OPEN_VERB.containsMatchIn(t)) {
      fun s(screen: String, name: String) = r("open_settings", mapOf("screen" to screen), "${name}の設定を開きますね。")
      if (Regex("""(wi-?fi|ワイファイ|ウィーファイ|無線lan)""").containsMatchIn(t)) return s("wifi", "Wi-Fi")
      if (Regex("""(bluetooth|ブルートゥース)""").containsMatchIn(t)) return s("bluetooth", "Bluetooth")
      if (t.contains("機内モード")) return s("airplane", "機内モード")
      if (t.contains("位置情報")) return s("location", "位置情報")
      if (Regex("""(モバイル通信|データ通信|モバイルデータ)""").containsMatchIn(t)) return s("mobile", "モバイル通信")
      if (Regex("""(画面|ディスプレイ)(の)?設定""").containsMatchIn(t)) return s("display", "画面")
      if (Regex("""(サウンド|音)(の)?設定""").containsMatchIn(t)) return s("sound", "音")
      if (Regex("""(電池|バッテリー)(の)?設定""").containsMatchIn(t)) return s("battery", "電力モード")
      if (Regex("""^設定(アプリ|画面)?(を)?(開いて|開く|出して|見せて|表示して|起動して)${'$'}""").containsMatchIn(t)) return s("other", "")
      if (Regex("""(グーグルホーム|googleホーム|googlehome|ホームアプリ|スマートホーム)""").containsMatchIn(t)) {
        return r("open_google_home", emptyMap(), "Google Homeを開きますね。")
      }
    }
    // 10) 曲を流す
    val mu = extractMusic(oSp, isApp)
    if (mu != null) {
      if (mu.generic) return r("media", mapOf("command" to "play"), "再生しますね。")
      val args = HashMap<String, Any>()
      args["query"] = mu.query
      if (mu.app != null) args["app"] = mu.app
      return r("play_music", args, "${mu.query}を流しますね。")
    }
    // 11) アプリを開く（端末に実在するアプリのときだけ）
    val app = extractApp(oSp, isApp)
    if (app != null) return r("open_app", mapOf("name" to app), "${app}を開きますね。")
    return null
  }
}
