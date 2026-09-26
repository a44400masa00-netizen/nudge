package com.example.studynudge.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 「考え中」に鳴らし続ける、ポコポコという泡のような音。
 * 波形は実行時に合成する（音声ファイル不要）。start() でループ再生、stop() で止まる。
 */
class ThinkingSound {

  private var track: AudioTrack? = null

  @Synchronized
  fun start() {
    if (track != null) return
    try {
      val pcm = synth()
      val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
      val format = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()
      val t = AudioTrack(attrs, format, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
      t.write(pcm, 0, pcm.size)
      t.setLoopPoints(0, pcm.size, -1) // 無限ループ（モノラル16bitなのでフレーム数 = サンプル数）
      t.play()
      track = t
    } catch (e: Exception) {
      track = null
    }
  }

  @Synchronized
  fun stop() {
    track?.let {
      try {
        it.stop()
      } catch (e: Exception) {
        // ignore
      }
      it.release()
    }
    track = null
  }

  /** ポコッ という短い泡の音を4つ並べた約1.2秒のパターン */
  private fun synth(): ShortArray {
    val total = (SAMPLE_RATE * LOOP_SECONDS).toInt()
    val buf = FloatArray(total)
    // (開始秒, 開始周波数Hz)。泡は音程が少し上がりながら消える
    val pops = listOf(0.00 to 380.0, 0.20 to 520.0, 0.62 to 450.0, 0.80 to 610.0)
    val popSeconds = 0.12
    val n = (SAMPLE_RATE * popSeconds).toInt()

    for ((start, f0) in pops) {
      val offset = (start * SAMPLE_RATE).toInt()
      var phase = 0.0
      for (i in 0 until n) {
        val t = i.toDouble() / SAMPLE_RATE
        val freq = f0 * (1.0 + 6.0 * t)
        phase += 2.0 * PI * freq / SAMPLE_RATE
        val envelope = (1.0 - exp(-t * 900.0)) * exp(-t * 38.0)
        val sample = (sin(phase) + 0.25 * sin(2.0 * phase)) * envelope * 0.35
        val idx = offset + i
        if (idx < total) buf[idx] += sample.toFloat()
      }
    }
    return ShortArray(total) { (buf[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
  }

  companion object {
    private const val SAMPLE_RATE = 22050
    private const val LOOP_SECONDS = 1.2
  }
}
