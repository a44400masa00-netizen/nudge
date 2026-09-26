package com.example.studynudge.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** アプリが閉じていても使えるよう、Gemini API (generateContent) をネイティブから直接呼ぶ。 */
object GeminiClient {

  /** @param detail 画面にだけ出す詳細（HTTPコードやAPIのエラー文）。読み上げには使わない */
  class GeminiException(message: String, val detail: String = "") : Exception(message)

  private class HttpFailure(val status: Int, val raw: String) : Exception("HTTP $status")

  data class Turn(val role: String, val text: String) // role: "user" | "model"

  private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

  // 混雑(503など)は一時的なことが多いので、間を置いて自動でやり直す
  private val RETRYABLE = setOf(408, 500, 502, 503, 504)
  private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_500)

  /** ブロッキング呼び出し。必ずバックグラウンドスレッドから呼ぶこと。 */
  fun ask(apiKey: String, model: String, systemPrompt: String, history: List<Turn>): String {
    // Gemini 3.x は thinkingLevel で思考量を調整（音声会話は速さ重視で low）
    var useThinking = model.startsWith("gemini-3")
    var attempt = 0
    while (true) {
      try {
        return request(apiKey, model, systemPrompt, history, useThinking)
      } catch (e: HttpFailure) {
        // 思考設定が原因で拒否された場合は、設定を外して1回やり直す
        if (e.status == 400 && useThinking && e.raw.contains("thinking", ignoreCase = true)) {
          useThinking = false
          continue
        }
        if (e.status in RETRYABLE && attempt < RETRY_DELAYS_MS.size) {
          try {
            Thread.sleep(RETRY_DELAYS_MS[attempt])
          } catch (ie: InterruptedException) {
            throw GeminiException("中断されました。")
          }
          attempt += 1
          continue
        }
        throw toGeminiException(e)
      }
    }
  }

  private fun request(
    apiKey: String,
    model: String,
    systemPrompt: String,
    history: List<Turn>,
    useThinking: Boolean
  ): String {
    val contents = JSONArray()
    history.forEach { t ->
      contents.put(
        JSONObject()
          .put("role", t.role)
          .put("parts", JSONArray().put(JSONObject().put("text", t.text)))
      )
    }
    val generationConfig = JSONObject()
    if (useThinking) {
      generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
    }
    val body = JSONObject()
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
      .put("contents", contents)
      .put("generationConfig", generationConfig)

    val url = URL("$ENDPOINT/${URLEncoder.encode(model, "UTF-8")}:generateContent")
    val conn = url.openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("x-goog-api-key", apiKey)
      conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
      if (code !in 200..299) throw HttpFailure(code, raw)

      val json = JSONObject(raw)
      if (json.optJSONObject("promptFeedback")?.has("blockReason") == true) {
        throw GeminiException("その内容にはお答えできませんでした。")
      }
      val parts = json.optJSONArray("candidates")
        ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
      val sb = StringBuilder()
      if (parts != null) {
        for (i in 0 until parts.length()) {
          val p = parts.optJSONObject(i) ?: continue
          if (p.optBoolean("thought", false)) continue
          sb.append(p.optString("text", ""))
        }
      }
      val text = sb.toString().trim()
      if (text.isEmpty()) throw GeminiException("うまく回答を作れませんでした。もう一度話しかけてください。")
      return text
    } catch (e: HttpFailure) {
      throw e
    } catch (e: GeminiException) {
      throw e
    } catch (e: IOException) {
      throw GeminiException("通信に失敗しました。ネットワークを確認してください。", e.javaClass.simpleName)
    } finally {
      conn.disconnect()
    }
  }

  private fun toGeminiException(e: HttpFailure): GeminiException {
    val apiMessage = try {
      JSONObject(e.raw).optJSONObject("error")?.optString("message", "") ?: ""
    } catch (ex: Exception) {
      e.raw.take(120)
    }
    val detail = "HTTP ${e.status}" + if (apiMessage.isNotBlank()) ": ${apiMessage.take(160)}" else ""
    val message = when (e.status) {
      400 ->
        if (apiMessage.contains("API key", ignoreCase = true)) "APIキーが正しくありません。アプリの設定を確認してください。"
        else "リクエストエラーです。"
      401, 403 -> "APIキーが無効か、権限がありません。アプリの設定を確認してください。"
      404 -> "モデルが見つかりません。アプリの設定でモデル名を確認してください。"
      429 -> "利用上限に達したか、混み合っています。少し待ってからお試しください。"
      in RETRYABLE -> "ジェミニ側が混み合っているようです。少し待ってからもう一度お願いします。"
      else -> "ジェミニのAPIでエラーが起きました。"
    }
    return GeminiException(message, detail)
  }
}
