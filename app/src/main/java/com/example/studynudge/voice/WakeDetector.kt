package com.example.studynudge.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.nio.FloatBuffer

private const val TAG = "DailyWake"

/**
 * openWakeWord の推論パイプライン（端末内・ONNX Runtime）。
 *
 *   音声(16kHz, 80msごと) → melspectrogram.onnx → embedding_model.onnx → hey_daily.onnx → 呼びかけスコア(0〜1)
 *
 * assets/wakeword/ に次の3つが必要:
 *   melspectrogram.onnx / embedding_model.onnx（openWakeWord 公式。CI が自動取得）と hey_daily.onnx（学習したモデル）
 */
class OpenWakeWord(ctx: Context) {

  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val mel: OrtSession
  private val emb: OrtSession
  private val wake: OrtSession
  private val melIn: String
  private val embIn: String
  private val wakeIn: String
  private val wakeFrames: Int
  private val wakeRank: Int

  private val raw = FloatArray(CHUNK + CONTEXT)
  private val melFrames = ArrayDeque<FloatArray>()
  private val embFrames = ArrayDeque<FloatArray>()

  init {
    fun load(name: String): OrtSession {
      val bytes = ctx.assets.open("wakeword/$name").use { it.readBytes() }
      return env.createSession(bytes, OrtSession.SessionOptions())
    }
    mel = load("melspectrogram.onnx")
    emb = load("embedding_model.onnx")
    wake = load(WAKE_MODEL)
    melIn = mel.inputNames.first()
    embIn = emb.inputNames.first()
    wakeIn = wake.inputNames.first()

    // 呼びかけモデルの入力形状は [バッチ, フレーム数(通常16), 96]。実際の値をモデルから読む
    val shape = (wake.inputInfo.values.first().info as TensorInfo).shape
    wakeRank = shape.size
    wakeFrames = if (shape.size >= 3 && shape[1] > 0) shape[1].toInt() else 16
  }

  /** 80ms(1280サンプル)分の音声を渡して、呼びかけスコアを返す。バッファが溜まるまでは 0 */
  fun process(chunk: ShortArray): Float {
    // 直前の 480 サンプルを文脈として残し、新しい 1280 サンプルを後ろに付ける
    System.arraycopy(raw, CHUNK, raw, 0, CONTEXT)
    for (i in 0 until CHUNK) raw[CONTEXT + i] = chunk[i].toFloat() // int16 の値のまま（正規化しない）

    val melOut = run(mel, melIn, raw, longArrayOf(1, raw.size.toLong()))
    val frameCount = melOut.size / MEL_BINS
    for (f in 0 until frameCount) {
      melFrames.addLast(FloatArray(MEL_BINS) { melOut[f * MEL_BINS + it] / 10f + 2f })
    }
    while (melFrames.size > MEL_WINDOW) melFrames.removeFirst()
    if (melFrames.size < MEL_WINDOW) return 0f

    val embInput = FloatArray(MEL_WINDOW * MEL_BINS)
    var k = 0
    for (frame in melFrames) for (v in frame) embInput[k++] = v
    val embOut = run(emb, embIn, embInput, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))
    if (embOut.size < EMB_DIM) throw IllegalStateException("embedding size ${embOut.size}")
    embFrames.addLast(embOut.copyOf(EMB_DIM))
    while (embFrames.size > wakeFrames) embFrames.removeFirst()
    if (embFrames.size < wakeFrames) return 0f

    val wakeInput = FloatArray(wakeFrames * EMB_DIM)
    var j = 0
    for (frame in embFrames) for (v in frame) wakeInput[j++] = v
    val shape = if (wakeRank >= 3) longArrayOf(1, wakeFrames.toLong(), EMB_DIM.toLong())
    else longArrayOf(1, (wakeFrames * EMB_DIM).toLong())
    val out = run(wake, wakeIn, wakeInput, shape)
    return out.maxOrNull() ?: 0f
  }

  /** 検出のあと、同じ音で続けて反応しないようバッファを空にする */
  fun reset() {
    raw.fill(0f)
    melFrames.clear()
    embFrames.clear()
  }

  fun close() {
    try {
      mel.close()
      emb.close()
      wake.close()
    } catch (e: Exception) {
      // ignore
    }
  }

  private fun run(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): FloatArray =
    OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
      session.run(mapOf(inputName to input)).use { result ->
        val fb = (result[0] as OnnxTensor).floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        out
      }
    }

  companion object {
    const val WAKE_MODEL = "hey_daily.onnx"
    const val CHUNK = 1280 // 80ms @ 16kHz
    private const val CONTEXT = 480 // メルスペクトログラム計算のための前の音声(160×3)
    private const val MEL_BINS = 32
    private const val MEL_WINDOW = 76
    private const val EMB_DIM = 96
  }
}

/**
 * マイクを読み続けて openWakeWord に渡す。「ヘイ、デイリー」を検出したらマイクを手放して onDetected を呼ぶ
 * （直後に SpeechRecognizer が指示を聞き取るため、マイクを取り合わないようにする）。
 */
class WakeDetector(
  ctx: Context,
  private val onDetected: () -> Unit,
  private val onFailure: () -> Unit
) {
  // モデルの読み込みに失敗したら、ここで例外になる（呼び出し側で捕まえて簡易検出に切り替える）
  private val engine = OpenWakeWord(ctx)

  @Volatile private var running = false
  private var thread: Thread? = null

  fun start() {
    if (running) return
    thread?.join(300)
    running = true
    thread = Thread({ loop() }, "daily-wake").also { it.start() }
  }

  /** ノンブロッキング。スレッドが自分でマイクを解放して終わる */
  fun stop() {
    running = false
  }

  fun close() {
    running = false
    thread?.join(500)
    thread = null
    engine.close()
  }

  private fun loop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    var record: AudioRecord? = null
    try {
      val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val bufBytes = maxOf(minBuf, OpenWakeWord.CHUNK * 2 * 4)
      record = AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
      )
      if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")
      record.startRecording()

      val frame = ShortArray(OpenWakeWord.CHUNK)
      var hits = 0
      while (running) {
        var read = 0
        while (read < frame.size && running) {
          val n = record.read(frame, read, frame.size - read)
          if (n < 0) throw IllegalStateException("AudioRecord.read = $n")
          read += n
        }
        if (!running) break

        val score = engine.process(frame)
        if (score >= 0.3f) Log.d(TAG, "score=$score")
        hits = if (score >= THRESHOLD) hits + 1 else 0
        if (hits >= HITS_REQUIRED) {
          running = false
          engine.reset()
          onDetected()
          return
        }
      }
    } catch (e: Throwable) {
      // 他のアプリがマイクを使用中、またはモデルの入出力が想定と違うなど
      Log.w(TAG, "detector loop failed", e)
      if (running) {
        running = false
        onFailure()
      }
    } finally {
      try {
        record?.stop()
      } catch (e: Exception) {
        // ignore
      }
      record?.release()
    }
  }

  companion object {
    private const val SAMPLE_RATE = 16_000

    /**
     * 反応のしやすさ。誤反応が多ければ THRESHOLD を 0.7 くらいに上げる。
     * 呼びかけても反応しなければ 0.35 くらいに下げる。
     * → 実際に「呼んでいないのに喋り出す」誤検知が報告されたため、初期値を安全側に上げてある。
     */
    private const val THRESHOLD = 0.72f
    private const val HITS_REQUIRED = 4 // 80msごとのスコアが連続して閾値を超えたら検出（誤反応を減らす）
  }
}
