package com.example.studynudge.voice

import android.content.Context
import com.example.studynudge.RelayClient
import com.example.studynudge.RelayPrefs

/**
 * 返事を作る「頭脳」。設定されていれば、まずパソコンのローカルAI（中継）を試し、
 * 繋がらなければGeminiを使う。端末内AI（オフラインモデル）は使わない。
 */
object Brain {

  fun canAnswer(ctx: Context, apiKey: String): Boolean {
    val relay = RelayPrefs(ctx)
    return relay.isConfigured() || apiKey.isNotBlank()
  }

  fun missingMessage(): String =
    "アプリを開いて、設定でGeminiのAPIキーを入れるか、パソコンのローカルAIを設定してください。"

  /**
   * cloudPrompt: Gemini用のシステムプロンプト（詳しい版）
   * relayPrompt: パソコンのローカルAI用のシステムプロンプト（軽量版）
   * 必要になったほうだけ組み立てる（使用状況の取得に少し時間がかかるため）。
   */
  fun ask(
    ctx: Context,
    apiKey: String,
    model: String,
    cloudPrompt: () -> String,
    relayPrompt: () -> String,
    history: List<GeminiClient.Turn>
  ): String {
    val relay = RelayPrefs(ctx)
    if (relay.isConfigured()) {
      try {
        return RelayClient.ask(
          relay.dbUrl, relay.secret, relayPrompt(), flatten(history),
          timeoutMs = 25_000L, maxTokens = 220
        )
      } catch (e: Exception) {
        if (apiKey.isBlank()) throw GeminiClient.GeminiException("パソコンのAIに繋がりませんでした: ${e.message}")
        // 繋がらなかったので、Geminiにフォールバックする
      }
    }
    if (apiKey.isBlank()) throw GeminiClient.GeminiException(missingMessage())
    return GeminiClient.ask(apiKey, model, cloudPrompt(), history)
  }

  /**
   * パソコンのローカルAI中継は複数ターンの会話を受け付けないため、1つの文にまとめる。
   * 多くのローカルAIは文脈の上限が4096トークン程度と狭いため、直近だけに絞る。
   */
  private fun flatten(history: List<GeminiClient.Turn>): String {
    val recent = history.takeLast(6)
    if (recent.size == 1) return recent[0].text
    val sb = StringBuilder()
    for (t in recent) {
      sb.append(if (t.role == "user") "ユーザー: " else "アシスタント: ")
      sb.append(t.text).append("\n")
    }
    return sb.toString()
  }
}
