package com.example.studynudge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * パソコンで開いているNudgeローカルAI中継ページに、Firebase Realtime Database経由で
 * 問い合わせるクライアント。ブロッキング呼び出しなので、必ずバックグラウンドスレッドから呼ぶこと。
 */
object RelayClient {
    class RelayException(message: String) : Exception(message)

    fun ask(dbUrl: String, secret: String, system: String?, prompt: String, timeoutMs: Long = 90_000L): String {
        val base = dbUrl.trim().trimEnd('/')
        if (base.isBlank()) throw RelayException("データベースURLが未設定です")
        val id = UUID.randomUUID().toString().replace("-", "")
        val reqUrl = URL("$base/relay/${enc(secret)}/requests/$id.json")
        val respUrl = URL("$base/relay/${enc(secret)}/responses/$id.json")

        val body = JSONObject()
        body.put("prompt", prompt)
        if (!system.isNullOrBlank()) body.put("system", system)
        body.put("status", "pending")
        putJson(reqUrl, body.toString())

        val start = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(1500)
                val raw = getJson(respUrl)
                if (raw != null && raw != "null") {
                    val obj = JSONObject(raw)
                    val text = obj.optString("text", "")
                    if (text.isBlank()) throw RelayException("パソコンから空の返答でした")
                    return text
                }
            }
            throw RelayException("パソコンからの返答がタイムアウトしました（ページが開かれていない可能性があります）")
        } finally {
            // 後片付け。失敗しても致命的ではないので無視する
            try { deleteQuiet(respUrl) } catch (e: Exception) { }
            try { deleteQuiet(reqUrl) } catch (e: Exception) { }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun putJson(url: URL, body: String) {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw RelayException("パソコンへの送信に失敗しました (HTTP $code) $err")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: URL): String? {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun deleteQuiet(url: URL) {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
