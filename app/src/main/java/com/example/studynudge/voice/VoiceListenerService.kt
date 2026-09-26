package com.example.studynudge.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 「デイリー」と呼びかけると起動する常駐サービス（マイク型フォアグラウンドサービス）。
 *
 *  WAKE（呼びかけ待ち）──「デイリー」──▶ SESSION（会話中）⇄ BUSY（考え中 → 読み上げ）
 *                                        │
 *                 「デイリー戻って」──────┘──▶ WAKE に戻る
 *
 * 一度呼びかけると会話モード(SESSION)になり、毎回呼ばなくても続けて話せる。
 * アプリ(JS)が閉じていても動くよう、音声認識・Gemini 呼び出し・読み上げをすべてネイティブで行う。
 */
class VoiceListenerService : Service() {

  private enum class Phase { WAKE, SESSION, BUSY }

  private val main = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor()
  private val thinking = ThinkingSound()

  private var recognizer: SpeechRecognizer? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var wakeLock: PowerManager.WakeLock? = null
  private lateinit var audio: AudioManager
  private var focusRequest: AudioFocusRequest? = null
  private val focusListener = AudioManager.OnAudioFocusChangeListener { }

  private var phase = Phase.WAKE
  private var sessionActive = false // 会話モード中（「デイリー戻って」まで続く）
  private var externalPause = false // アプリ画面で会話中は待機を止める
  private var stopped = false
  private var cleanedUp = false
  private var useOnDevice = Build.VERSION.SDK_INT >= 33
  private var consecutiveErrors = 0

  // openWakeWord による「ヘイ、デイリー」検出（準備できるまで／失敗したら SpeechRecognizer で代用）
  private val prep: ExecutorService = Executors.newSingleThreadExecutor()
  private var wakeDetector: WakeDetector? = null
  private var detectorStartedAt = 0L
  private var detectorFailures = 0

  // 会話履歴（worker スレッドからのみ触る）
  private val history = ArrayList<GeminiClient.Turn>()
  private var lastInteraction = 0L

  private val restartRunnable = Runnable { startListening() }
  private val sessionTimeoutRunnable = Runnable { onSessionTimeout() }

  // ---- ライフサイクル -----------------------------------------------------------

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    createChannel()
    initTts()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      startForegroundCompat(idleText())
    } catch (e: Exception) {
      // マイク許可が無い / バックグラウンドから起動された等
      VoiceBus.emit("onMessage", mapOf("role" to "error", "text" to "常時待機を開始できませんでした: ${e.message}"))
      shutdown()
      return START_NOT_STICKY
    }
    if (!running) {
      running = true
      acquireWakeLock()
      emitState("listening")
      scheduleRestart(0)
      prepareWake()
    }
    // 強制終了後に勝手に再起動されない（バックグラウンドからのマイク起動は Android が禁止しているため）
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    cleanup()
    super.onDestroy()
  }

  private fun shutdown() {
    cleanup()
    @Suppress("DEPRECATION")
    stopForeground(true)
    stopSelf()
  }

  private fun cleanup() {
    if (cleanedUp) return
    cleanedUp = true
    stopped = true
    running = false
    instance = null
    main.removeCallbacksAndMessages(null)
    thinking.stop()
    wakeDetector?.close()
    wakeDetector = null
    prep.shutdownNow()
    destroyRecognizer()
    tts?.stop()
    tts?.shutdown()
    tts = null
    abandonFocus()
    worker.shutdownNow()
    try {
      wakeLock?.let { if (it.isHeld) it.release() }
    } catch (e: Exception) {
      // ignore
    }
    wakeLock = null
    emitState("stopped")
  }

  // ---- 外部（JS）からの操作 -------------------------------------------------------

  /** アプリ画面での会話中は待機を止め、終わったら再開する */
  fun setExternalPause(paused: Boolean) {
    if (stopped || externalPause == paused) return
    externalPause = paused
    if (paused) {
      main.removeCallbacks(restartRunnable)
      wakeDetector?.stop()
      destroyRecognizer()
      emitState("paused")
    } else if (phase != Phase.BUSY) {
      phase = if (sessionActive) Phase.SESSION else Phase.WAKE
      showIdleState()
      scheduleRestart(400)
    }
  }

  // ---- 音声認識 -----------------------------------------------------------------

  private fun createRecognizer(): SpeechRecognizer? {
    val r: SpeechRecognizer? = when {
      useOnDevice && Build.VERSION.SDK_INT >= 33 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ->
        SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
      SpeechRecognizer.isRecognitionAvailable(this) -> SpeechRecognizer.createSpeechRecognizer(this)
      else -> null
    }
    r?.setRecognitionListener(listener)
    return r
  }

  private fun destroyRecognizer() {
    try {
      recognizer?.cancel()
      recognizer?.destroy()
    } catch (e: Exception) {
      // ignore
    }
    recognizer = null
  }

  private fun scheduleRestart(delayMs: Long) {
    main.removeCallbacks(restartRunnable)
    if (stopped) return
    main.postDelayed(restartRunnable, delayMs)
  }

  private fun startListening() {
    if (stopped || externalPause || phase == Phase.BUSY) return
    // 呼びかけ待ちは openWakeWord（専用モデル）が使えるならそちらで。会話中の聞き取りだけ SpeechRecognizer を使う
    if (phase == Phase.WAKE) {
      val detector = wakeDetector
      if (detector != null) {
        destroyRecognizer()
        detectorStartedAt = System.currentTimeMillis()
        detector.start()
        return
      }
    }
    if (recognizer == null) recognizer = createRecognizer()
    val r = recognizer
    if (r == null) {
      VoiceBus.emit("onMessage", mapOf("role" to "error", "text" to "この端末では音声認識を利用できません。"))
      shutdown()
      return
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }
    try {
      r.startListening(intent)
    } catch (e: Exception) {
      destroyRecognizer()
      scheduleRestart(1_000)
    }
  }

  private val listener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onResults(results: Bundle?) {
      val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.filterNotNull() ?: emptyList()
      handleResults(list)
    }

    override fun onError(error: Int) {
      handleError(error)
    }
  }

  private fun handleResults(candidates: List<String>) {
    if (stopped || externalPause) return
    consecutiveErrors = 0
    when (phase) {
      Phase.WAKE -> {
        // 会話中でないときの「デイリー戻って」は無視。「デイリー」が含まれていれば会話を開始する
        if (candidates.any { WakeWord.isEndCommand(it) }) {
          scheduleRestart(150)
          return
        }
        for (c in candidates) {
          val cmd = WakeWord.extractCommand(c)
          if (cmd != null) {
            onWake(cmd)
            return
          }
        }
        scheduleRestart(150) // 呼びかけ無し: 何も送らず捨てて待機を続ける
      }
      Phase.SESSION -> {
        val first = candidates.firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) {
          scheduleRestart(150)
          return
        }
        armSessionTimeout()
        if (candidates.any { WakeWord.isEndCommand(it) }) {
          VoiceBus.emit("onMessage", mapOf("role" to "user", "text" to first)) // 認識された文を画面にも出す
          endSession()
          return
        }
        // 「デイリー、〇〇」と呼びかけ付きで言われた場合は呼びかけ部分を外す（呼ばれたことは確実）
        val stripped = WakeWord.extractCommand(first)
        when {
          stripped == null -> processCommand(first, addressed = false)
          stripped.isEmpty() -> scheduleRestart(150) // 「デイリー」だけ。続きを待つ（会話中は合図音を鳴らさない）
          else -> processCommand(stripped, addressed = true)
        }
      }
      Phase.BUSY -> Unit
    }
  }

  private fun handleError(code: Int) {
    if (stopped || externalPause) return
    when (code) {
      SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
        scheduleRestart(200) // 無音は普通のこと。聞き取りを続ける
      }
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
        destroyRecognizer()
        scheduleRestart(1_000)
      }
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
        VoiceBus.emit("onMessage", mapOf("role" to "error", "text" to "マイクの許可がありません。設定を確認してください。"))
        shutdown()
      }
      12, 13 -> { // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE
        if (useOnDevice) {
          useOnDevice = false // 端末内モデルが無い → 通常の認識サービスに切り替える
          destroyRecognizer()
          scheduleRestart(300)
        } else {
          VoiceBus.emit("onMessage", mapOf("role" to "error", "text" to "日本語の音声認識を利用できません。"))
          shutdown()
        }
      }
      else -> {
        // ネットワーク/サーバ/クライアント/音声入力エラー: 指数バックオフで再試行
        consecutiveErrors += 1
        val delay = minOf(30_000L, 1_000L shl minOf(consecutiveErrors, 5))
        destroyRecognizer()
        scheduleRestart(delay)
      }
    }
  }

  // ---- openWakeWord（呼びかけ検出） ------------------------------------------------------------

  private fun idleText(): String = if (wakeDetector != null) IDLE_TEXT_WAKEWORD else IDLE_TEXT

  private fun prepareWake() {
    prep.execute {
      var failure: String? = null
      val detector = try {
        WakeDetector(
          applicationContext,
          onDetected = { main.post { onWakeDetected() } },
          onFailure = { main.post { onDetectorFailure() } }
        )
      } catch (e: Throwable) {
        Log.w("DailyWake", "wake model load failed", e)
        failure = e.message ?: e.javaClass.simpleName
        null
      }
      main.post {
        if (stopped) {
          detector?.close()
          return@post
        }
        if (detector == null) {
          VoiceBus.emit(
            "onMessage",
            mapOf(
              "role" to "error",
              "text" to "呼びかけ用モデルを読み込めなかったので、簡易検出で動いています。($failure)"
            )
          )
          return@post
        }
        wakeDetector = detector
        showIdleState()
        if (phase == Phase.WAKE && !externalPause) {
          destroyRecognizer() // 簡易検出から専用モデルへ切り替え
          scheduleRestart(0)
        }
      }
    }
  }

  private fun onWakeDetected() {
    if (stopped || externalPause || phase != Phase.WAKE) return
    detectorFailures = 0
    consecutiveErrors = 0
    onWake("") // 合図音 → 会話モードへ
  }

  private fun onDetectorFailure() {
    if (stopped || externalPause || phase != Phase.WAKE) return
    // 起動直後にすぐ失敗する状態が続くなら、専用モデルをあきらめて簡易検出に切り替える
    if (System.currentTimeMillis() - detectorStartedAt > 5_000) detectorFailures = 0 else detectorFailures += 1
    if (detectorFailures >= 3) {
      wakeDetector?.close()
      wakeDetector = null
      VoiceBus.emit(
        "onMessage",
        mapOf("role" to "error", "text" to "専用の呼びかけ検出が動かないため、簡易検出に切り替えました。")
      )
      showIdleState()
      scheduleRestart(300)
    } else {
      scheduleRestart(minOf(30_000L, 1_000L shl minOf(detectorFailures + 1, 5))) // マイクが空くまで待って再試行
    }
  }

  // ---- 会話モード（呼びかけ → 続けて会話 → 「デイリー戻って」で終了） -------------------------

  private fun onWake(command: String) {
    sessionActive = true
    armSessionTimeout()
    if (command.isBlank()) {
      // 「デイリー」だけ言われた: 合図音を鳴らして続きを聞く
      phase = Phase.SESSION
      chime()
      showIdleState()
      scheduleRestart(350)
    } else {
      processCommand(command, addressed = true)
    }
  }

  private fun endSession() {
    sessionActive = false
    main.removeCallbacks(sessionTimeoutRunnable)
    phase = Phase.BUSY
    main.removeCallbacks(restartRunnable)
    destroyRecognizer()
    val casual = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tone", "polite") == "casual"
    speakText(if (casual) "うん、待機に戻るね。また呼んでね。" else "はい、待機に戻ります。また呼んでくださいね。", "model")
  }

  /** 長時間だれも話さなかったら待機に戻す（つけっぱなし防止） */
  private fun onSessionTimeout() {
    if (stopped || !sessionActive) return
    if (phase == Phase.BUSY) {
      armSessionTimeout()
      return
    }
    sessionActive = false
    phase = Phase.WAKE
    destroyRecognizer()
    VoiceBus.emit("onMessage", mapOf("role" to "error", "text" to "しばらく話しかけがなかったので、待機に戻りました。"))
    showIdleState()
    if (!externalPause) scheduleRestart(300)
  }

  private fun armSessionTimeout() {
    main.removeCallbacks(sessionTimeoutRunnable)
    if (SESSION_IDLE_TIMEOUT_MS > 0) main.postDelayed(sessionTimeoutRunnable, SESSION_IDLE_TIMEOUT_MS)
  }

  private fun showIdleState() {
    if (sessionActive) {
      updateNotification(SESSION_TEXT)
      emitState("awake")
    } else {
      updateNotification(idleText())
      emitState("listening")
    }
  }

  // ---- 指示 → Gemini → 読み上げ ------------------------------------------------------------

  /** @param addressed 「デイリー」と呼びかけて言われた発話か（true なら無視せず必ず答える） */
  private fun processCommand(text: String, addressed: Boolean) {
    phase = Phase.BUSY
    main.removeCallbacks(restartRunnable)
    destroyRecognizer()
    updateNotification("考え中…")
    emitState("thinking")
    thinking.start() // 考え中はポコポコ音を鳴らし続ける

    worker.execute {
      val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val apiKey = prefs.getString("api_key", "").orEmpty()
      val model = prefs.getString("model", "gemini-3.8-flash").orEmpty().ifBlank { "gemini-3.8-flash" }

      val brainMode = prefs.getString("brain", "auto").orEmpty().ifBlank { "auto" }

      if (!Brain.canAnswer(applicationContext, brainMode, apiKey)) {
        main.post { deliverError(text, Brain.missingMessage(brainMode)) }
        return@execute
      }

      val now = System.currentTimeMillis()
      if (now - lastInteraction > IDLE_RESET_MS) history.clear() // しばらく間が空いたら文脈をリセット
      lastInteraction = now
      history.add(GeminiClient.Turn("user", text))

      try {
        val reply = Brain.ask(applicationContext, brainMode, apiKey, model, PromptBuilder.build(applicationContext), trimmedHistory())
        val ignored = reply.contains(PromptBuilder.IGNORE_TOKEN)
        if (ignored && !addressed) {
          // 動画や周囲の声など、デイリーへの話しかけではない → 何も言わず聞き取りに戻る
          history.removeAt(history.size - 1)
          main.post { resumeListening() }
          return@execute
        }
        val finalText = if (ignored) "うまく聞き取れませんでした。もう一度お願いします。" else PhoneActions.process(applicationContext, reply)
        history.add(GeminiClient.Turn("model", finalText))
        main.post { deliverReply(text, finalText) }
      } catch (e: Exception) {
        history.removeAt(history.size - 1) // 失敗したターンは履歴に残さない
        val spoken = e.message ?: "エラーが起きました。"
        val detail = (e as? GeminiClient.GeminiException)?.detail.orEmpty()
        val shown = if (detail.isNotBlank()) "$spoken\n（$detail）" else spoken
        main.post { deliverError(text, spoken, shown) }
      }
    }
  }

  private fun trimmedHistory(): List<GeminiClient.Turn> {
    val recent = history.takeLast(20).toMutableList()
    while (recent.isNotEmpty() && recent[0].role != "user") recent.removeAt(0)
    return recent
  }

  private fun deliverReply(userText: String, reply: String) {
    if (stopped) return
    VoiceBus.emit("onMessage", mapOf("role" to "user", "text" to userText))
    speakText(reply, "model")
  }

  /** @param spoken 読み上げる短い文 @param shown 画面に出す文（詳細つき） */
  private fun deliverError(userText: String, spoken: String, shown: String = spoken) {
    if (stopped) return
    VoiceBus.emit("onMessage", mapOf("role" to "user", "text" to userText))
    speakText(spoken, "error", shown)
  }

  /** 画面に出して読み上げる。読み上げが終わったら聞き取りに戻る */
  private fun speakText(text: String, role: String, shown: String = text) {
    thinking.stop()
    VoiceBus.emit("onMessage", mapOf("role" to role, "text" to shown))
    val speakOn = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("speak", true)
    if (!speakOn || !ttsReady) {
      resumeListening()
      return
    }
    requestFocus()
    updateNotification("話しています…")
    emitState("speaking")
    tts?.speak(cleanForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
  }

  /** 考え中/読み上げが終わったあと: 会話中ならそのまま続きを聞く、そうでなければ呼びかけ待ちへ */
  private fun resumeListening() {
    thinking.stop()
    if (PhoneActions.consumeEndSession()) {
      // 音楽を流したので会話モードを終える（歌詞を話しかけと聞き間違えないように）
      sessionActive = false
      main.removeCallbacks(sessionTimeoutRunnable)
    }
    abandonFocus()
    if (stopped) return
    phase = if (sessionActive) Phase.SESSION else Phase.WAKE
    showIdleState()
    if (!externalPause) scheduleRestart(if (sessionActive) 300 else 400)
  }

  // ---- 読み上げ・合図・音声フォーカス ----------------------------------------------------

  private fun initTts() {
    tts = TextToSpeech(applicationContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        tts?.language = Locale.JAPAN
        tts?.setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {}
          override fun onDone(utteranceId: String?) {
            main.post { resumeListening() }
          }

          @Suppress("OVERRIDE_DEPRECATION")
          override fun onError(utteranceId: String?) {
            main.post { resumeListening() }
          }
        })
        ttsReady = true
      }
    }
  }

  private fun cleanForSpeech(text: String): String =
    text
      .replace(Regex("https?://\\S+"), "")
      .replace(Regex("[*_`#>~]"), "")
      .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF\\uFE0F]"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
      .take(3_900)

  /** 他のアプリ(Instagram/LINE等)の音声を一時的に小さくして、デイリーの声を聞き取りやすくする */
  private fun requestFocus() {
    if (Build.VERSION.SDK_INT >= 26) {
      val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setOnAudioFocusChangeListener(focusListener)
        .build()
      audio.requestAudioFocus(req)
      focusRequest = req
    } else {
      @Suppress("DEPRECATION")
      audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }
  }

  private fun abandonFocus() {
    if (!this::audio.isInitialized) return
    if (Build.VERSION.SDK_INT >= 26) {
      focusRequest?.let { audio.abandonAudioFocusRequest(it) }
      focusRequest = null
    } else {
      @Suppress("DEPRECATION")
      audio.abandonAudioFocus(focusListener)
    }
  }

  private fun chime() {
    try {
      val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
      tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
      main.postDelayed({ tg.release() }, 500)
    } catch (e: Exception) {
      // ignore
    }
  }

  // ---- 通知・WakeLock --------------------------------------------------------------

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val ch = NotificationChannel(CHANNEL_ID, "デイリー待機中", NotificationManager.IMPORTANCE_LOW)
      ch.setShowBadge(false)
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
  }

  @Suppress("DEPRECATION")
  private fun buildNotification(text: String): Notification {
    val open = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
    val openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
    return builder
      .setContentTitle("デイリー")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(openPi)
      .setOngoing(true)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .build()
  }

  private fun startForegroundCompat(text: String) {
    val n = buildNotification(text)
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIF_ID, n)
    }
  }

  private fun updateNotification(text: String) {
    try {
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .notify(NOTIF_ID, buildNotification(text))
    } catch (e: Exception) {
      // ignore
    }
  }

  private fun acquireWakeLock() {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daily:listening").apply {
      setReferenceCounted(false)
      acquire()
    }
  }

  private fun emitState(state: String) = VoiceBus.emit("onState", mapOf("state" to state))

  companion object {
    const val PREFS = "daily_config"
    private const val CHANNEL_ID = "daily_listening"
    private const val NOTIF_ID = 4201
    private const val UTTERANCE_ID = "daily-utt"
    private const val IDLE_TEXT = "「デイリー」と呼びかけてください"
    private const val IDLE_TEXT_WAKEWORD = "「ヘイ、デイリー」と呼びかけてください"
    private const val SESSION_TEXT = "会話中です。「デイリー戻って」で待機に戻ります"
    private const val IDLE_RESET_MS = 10 * 60 * 1000L

    /**
     * 会話モード中、この時間だれも話さなかったら待機に戻す（つけっぱなし防止）。
     * 0 にすると「デイリー戻って」と言うまで永久に続く。
     */
    private const val SESSION_IDLE_TIMEOUT_MS = 15 * 60 * 1000L

    @Volatile var running = false
    @Volatile var instance: VoiceListenerService? = null

    fun setPaused(paused: Boolean) {
      val s = instance ?: return
      s.main.post { s.setExternalPause(paused) }
    }
  }
}
