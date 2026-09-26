package com.example.studynudge.voice

import android.content.Context

/**
 * 返事を作る「頭脳」の選択。
 *   auto  : まず Gemini。使えないとき（回数制限・混雑・通信なし）は端末内AIで答える
 *   cloud : Gemini だけ
 *   device: 端末内AIだけ（オフラインでも動く）
 */
object Brain {

  fun canAnswer(ctx: Context, mode: String, apiKey: String): Boolean = when (mode) {
    "device" -> LocalModel.isReady(ctx)
    "cloud" -> apiKey.isNotBlank()
    else -> apiKey.isNotBlank() || LocalModel.isReady(ctx)
  }

  fun missingMessage(mode: String): String =
    if (mode == "device") "端末内AIのモデルがありません。アプリの設定からダウンロードしてください。"
    else "アプリを開いて、設定でジェミニのAPIキーを入れてください。"

  fun ask(
    ctx: Context,
    mode: String,
    apiKey: String,
    model: String,
    systemPrompt: String,
    history: List<GeminiClient.Turn>
  ): String {
    val localReady = LocalModel.isReady(ctx)

    if (mode == "device") {
      if (!localReady) throw GeminiClient.GeminiException(missingMessage(mode))
      return LocalLlm.ask(ctx, systemPrompt, history)
    }
    if (mode == "cloud") {
      return GeminiClient.ask(apiKey, model, systemPrompt, history)
    }

    // auto
    if (apiKey.isBlank()) {
      if (!localReady) throw GeminiClient.GeminiException(missingMessage(mode))
      return LocalLlm.ask(ctx, systemPrompt, history)
    }
    return try {
      GeminiClient.ask(apiKey, model, systemPrompt, history)
    } catch (e: GeminiClient.GeminiException) {
      if (!localReady) throw e
      LocalLlm.ask(ctx, systemPrompt, history) // Gemini が使えないので、端末内AIで答える
    }
  }
}
