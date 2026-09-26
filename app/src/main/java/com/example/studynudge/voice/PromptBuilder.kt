package com.example.studynudge.voice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** サービス側（アプリが閉じていても動く）で使うシステムプロンプト。JS 側 src/usage.ts と同じ内容。 */
object PromptBuilder {

  /** 「あなたへの話しかけではない」と判断したときに返させる目印 */
  const val IGNORE_TOKEN = "[[IGNORE]]"

  private fun formatDuration(ms: Long): String {
    val totalMin = Math.round(ms / 60000.0).toInt()
    if (totalMin < 1) return "1分未満"
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
      h == 0 -> "${m}分"
      m == 0 -> "${h}時間"
      else -> "${h}時間${m}分"
    }
  }

  private fun hhmm(ts: Long): String = SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(ts))

  private fun describeUsage(ctx: Context): String {
    if (!UsageCollector.hasPermission(ctx)) {
      return "【スマホ使用状況】ユーザーがまだ「使用状況へのアクセス」を許可していないため取得できません。" +
        "使用状況について聞かれたら、アプリを開いて許可するとアドバイスできることを伝えてください。"
    }
    val u = try {
      UsageCollector.collectToday(ctx)
    } catch (e: Exception) {
      return "【スマホ使用状況】取得に失敗しました。使用状況の話題では、今は確認できないと伝えてください。"
    }
    val lines = ArrayList<String>()
    lines.add("【今日のスマホ使用状況（0:00〜${hhmm(u.now)}、経過${formatDuration(u.now - u.startOfDay)}）】")
    lines.add("- 合計使用時間: ${formatDuration(u.totalMs)}（このアプリとホーム画面は除く。経過時間を超えることはない）")
    lines.add("- 画面ONの回数: ${u.screenOnCount}回 / ロック解除: ${u.unlockCount}回")
    if (u.apps.isNotEmpty()) {
      lines.add("- アプリ別（使用時間の長い順）:")
      u.apps.take(8).forEach {
        lines.add("  ・${it.label}: ${formatDuration(it.totalMs)}、起動${it.launchCount}回、最終使用${hhmm(it.lastUsed)}")
      }
    }
    if (u.launches.isNotEmpty()) {
      val recent = u.launches.takeLast(12).joinToString(" → ") { "${hhmm(it.timestamp)} ${it.label}" }
      lines.add("- 直近に起動したアプリ（時系列）: $recent")
    }
    return lines.joinToString("\n")
  }

  fun build(ctx: Context): String {
    val now = SimpleDateFormat("yyyy年M月d日(E) H:mm", Locale.JAPAN).format(Date())
    return listOf(
      "あなたの名前は「デイリー」です。ユーザーと音声でおしゃべりする、親しみやすいAIアシスタントです。",
      "",
      "# 話し方のルール",
      "- 返答は音声で読み上げられます。自然な日本語の話し言葉で、1〜3文（100文字前後）で短く答えてください。",
      "- ユーザーはInstagramやLINEなど他のアプリを見ながら話しかけていることがあります。手短に答えてください。",
      "- 会話は続いています。ユーザーは毎回「デイリー」と呼ばずに話しかけてきます。",
      "- 動画・テレビ・周囲の人の声など、スマホのマイクが拾った音がそのまま届くことがあります。明らかにあなたへの話しかけではない（動画のセリフ、他の人との会話、独り言など）と判断したときだけ、返答を「$IGNORE_TOKEN」の一語だけにしてください。あなたへの質問・依頼・雑談の可能性が少しでもあれば、普通に答えてください。",
      "- Markdown、箇条書き、絵文字、記号、URLは使わないでください。",
      "- 相手の話に共感し、会話が続く軽い一言や質問を添えても構いません（毎回でなくてよい）。",
      "",
      "# スマホ使用状況の扱い",
      "- 下の使用状況は、端末から取得した今日の実データです。数字は正確に使い、データにないことは作らないでください。",
      "- ユーザーが使用状況を尋ねたときや、会話の流れで自然なときに、データに基づいて具体的にアドバイスしてください（使いすぎ、休憩、寝る前の使用、ついつい開いてしまうアプリ など）。",
      "- 説教くさくならず、責めないでください。うまく使えている点も伝えてください。",
      "- アプリの中で何をしていたかまでは分かりません。分からないことは分からないと答えてください。",
      "",
      "# 現在日時",
      now,
      "",
      describeUsage(ctx),
      "",
      extrasFromPrefs(ctx)
    ).joinToString("\n")
  }

  private fun extrasFromPrefs(ctx: Context): String {
    val prefs = ctx.getSharedPreferences(VoiceListenerService.PREFS, Context.MODE_PRIVATE)
    return extras(
      prefs.getString("call_name", "masa").orEmpty(),
      prefs.getString("tone", "polite").orEmpty(),
      prefs.getString("music_app", "").orEmpty()
    )
  }

  /** ユーザーの呼び方・話し方の指示と、スマホ操作のしかた。アプリ画面側(JS)のプロンプトにも同じものを足す */
  fun extras(callName: String, tone: String, musicApp: String): String {
    val call =
      if (callName == "you") "ユーザーのことは「あなた」と呼んでください。ただし毎回ではなく、必要なときだけにしてください。"
      else "ユーザーのことは「まさ」と呼んでください。毎回ではなく、ときどき自然に呼びかけてください。"
    val talk =
      if (tone == "casual") "話し方はタメ口にしてください。親しい友達のように「〜だよ」「〜だね」「〜してね」と話し、敬語（です・ます）は使わないでください。"
      else "話し方は、です・ます調の丁寧な敬語にしてください。"
    val music = if (musicApp.isBlank()) "音楽アプリは未設定です。" else "ユーザーが設定した音楽アプリは「$musicApp」です。"
    return "# 呼び方と話し方（これを最優先で守る）\n- $call\n- $talk\n\n" + ACTION_RULES + "\n$music appは省略するとこのアプリを使います。"
  }

  private val ACTION_RULES = """
# スマホの操作
ユーザーがスマホの操作（タイマー、アラーム、ライト、音量など）を頼んだときは、返事の文章のあとに、操作を次の形式で書いてください。この部分は読み上げられず、アプリが実行します。
[[ACTION:操作名 {"項目": 値}]]

使える操作:
- set_timer {"seconds": 180, "label": "カップ麺"}  タイマー（秒数）
- set_alarm {"hour": 7, "minute": 30, "label": "起床"}  アラーム（24時間表記。次にくるその時刻に鳴る）
- cancel_timers {}  /  cancel_alarms {}  タイマー／アラームをすべて取り消す
- stop_ringing {}  鳴っているタイマー／アラームを止める
- flashlight {"on": true}  ライトのオン・オフ
- volume {"stream": "media", "percent": 40}  音量。streamは media / ring / alarm。上げる・下げるは {"stream": "media", "delta": "up"} または "down"
- brightness {"percent": 30}  画面の明るさ
- battery_saver {"on": true}  電力モード（バッテリーセーバー）
- do_not_disturb {"on": true}  おやすみモード
- ringer_mode {"mode": "vibrate"}  normal / vibrate / silent
- open_app {"name": "Instagram"}  アプリを開く（ハンズフリーで開きます。名前はアプリの正式名で）
- play_music {"query": "米津玄師 Lemon", "app": "Spotify"}  音楽アプリで曲・アーティスト・プレイリストを検索して再生（app は省略可）
- media {"command": "pause"}  再生中の音楽の操作。pause / play / next / previous / stop
- open_settings {"screen": "wifi"}  設定画面を開く。screenは wifi / bluetooth / airplane / display / sound / battery / location / mobile / other

例:
ユーザー「3分のタイマーをかけて」→ 「3分のタイマーをセットしますね。[[ACTION:set_timer {"seconds": 180}]]」

ルール:
- 上の操作にないこと（Wi-Fiやモバイル通信のオン・オフ、電話、メッセージなど）は、できないと正直に伝えてください。設定画面を開けるものは open_settings で案内してください。
- 操作が成功したかどうかは、あなたの返事のあとにアプリが確認します。「〜しますね」のように書き、「完了しました」とは断言しないでください。
- 操作は、ユーザーが頼んだときだけ書いてください。JSONは必ず1行で、上の形式どおりに書いてください。
""".trimIndent()
}
