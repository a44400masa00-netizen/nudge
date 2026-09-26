package com.example.studynudge.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.runBlocking

/**
 * 端末内AI（Qwen2.5 3B Instruct, GGUF）で返事を作る。
 * llama.cpp を Android から呼ぶ軽量ラッパー "llama-android"（CPU/NEON動作）を使う。
 *
 * 注意: dev.ffmpegkit.llama.Llama.loadModel() が返す型の正式名は、公式ドキュメントの
 * サンプルコードには明記されていない。ここでは "LlamaModel" という名前だと推測して書いている。
 * ビルドで "Unresolved reference" になった場合は、まずこの型名を疑うこと。
 *
 * モデルは約2.1GBあるので、使っていないと5分で解放してメモリを空ける。
 */
object LocalLlm {

  private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
  private const val CONTEXT_SIZE = 2048
  private const val MAX_HISTORY_TURNS = 6 // 直前の会話をどこまで文脈として渡すか

  private var model: dev.ffmpegkit.llama.LlamaModel? = null
  private val handler = Handler(Looper.getMainLooper())
  private val releaseRunnable = Runnable { Thread { release() }.start() }

  /** ブロッキング（数秒かかる）。バックグラウンドスレッドから呼ぶこと */
  @Synchronized
  fun ask(ctx: Context, systemPrompt: String, history: List<GeminiClient.Turn>): String = try {
    handler.removeCallbacks(releaseRunnable)
    runBlocking { generate(ctx, systemPrompt, history) }
  } catch (t: Throwable) {
    throw GeminiClient.GeminiException("端末内AIでエラーが起きました。", (t.message ?: t.javaClass.simpleName).take(160))
  } finally {
    handler.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
  }

  @Synchronized
  fun release() {
    model = null // llama-android にモデルを明示的に閉じるAPIがあれば、本来はここで呼ぶ
  }

  private suspend fun generate(ctx: Context, systemPrompt: String, history: List<GeminiClient.Turn>): String {
    val m = model ?: Llama.loadModel(
      modelPath = LocalModel.file(ctx).absolutePath,
      config = LlamaConfig(contextSize = CONTEXT_SIZE, threads = 4),
    ).also { model = it }

    val last = history.lastOrNull { it.role == "user" } ?: throw IllegalArgumentException("empty history")
    // Qwenの会話テンプレートは Llama.complete が自動で適用するので、直前のやり取りは
    // 「ユーザー: 〜」「デイリー: 〜」の形で1つの文章にまとめ、簡易な文脈として渡す
    val prior = history.dropLast(1).takeLast(MAX_HISTORY_TURNS)
    val context = prior.joinToString("") { (if (it.role == "user") "ユーザー: " else "デイリー: ") + it.text + "\n" }

    val result = Llama.complete(
      m,
      prompt = context + "ユーザー: " + last.text,
      systemPrompt = systemPrompt,
      maxTokens = 220,
    )
    return result.text.trim()
  }
}
