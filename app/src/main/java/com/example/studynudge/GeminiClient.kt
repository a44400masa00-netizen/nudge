package com.example.studynudge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object Prompts {
    val SYSTEM = """
あなたは、学生ユーザーに勉強を促す、親しみやすく少しだけ背中を押してくれる学習コーチです。
スマホ画面の上部に表示する短いメッセージを書いてください。

ルール:
- 日本語で、1〜3文、全体で100文字以内。
- 「現在の状況」から、ユーザーが今どんな状況かを推測し、その推測を自然に口にしながら勉強を促す。
- 次の予定まで時間があるときは、予定の30分前まで勉強し、その後は準備をするよう提案する。
- 予定のない日は、一日を通して勉強に取り組むよう促す。
- 移動中（通学・通勤など）は、単語や暗記など移動中でもできる勉強を提案する。
- 早朝・深夜は、体調を気遣いつつ、何をしているのか尋ねる。
- 叱りすぎない。絵文字は使わない。
- メッセージ本文だけを出力する。前置き、見出し、かぎかっこは付けない。

例:
今日は学校や塾がないようですね。今日は一日勉強に取り組みましょう。
塾が始まるまで、あと1時間ほどあります。塾が始まる30分前までは勉強をして、その後は塾の準備をしましょう。
今は、通勤の途中のようですね。この時間を使って勉強しましょう。
おはようございます。何されているんですか？
夜更かしですか？何をするんですか？
""".trimIndent()

    fun user(s: Situation, trigger: Trigger, prefs: Prefs): String {
        val sb = StringBuilder()
        sb.append("現在の状況:\n").append(s.promptText())
        when (trigger) {
            Trigger.EARLY -> sb.append("\n特別な状況: 朝").append(prefs.earlyHour)
                .append("時前にスマホを使い始めて数分たった。何をしているのか気さくに尋ねる。\n")
            Trigger.NIGHT -> sb.append("\n特別な状況: ユーザーが設定した就寝時刻（")
                .append(prefs.bedtimeHour).append("時）を過ぎてもスマホを使っている。夜更かしかを尋ね、何をするのか聞く。\n")
            Trigger.NORMAL, Trigger.TEST -> sb.append("\n上の状況に合わせて、勉強を促すメッセージを書いてください。\n")
        }
        return sb.toString()
    }
}

object GeminiClient {
    /** ストリーミングでテキスト断片を受け取る。失敗時は例外を投げる。 */
    fun stream(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        onChunk: (String) -> Unit
    ) {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")

        val body = JSONObject()
        body.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
        )
        body.put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))
            )
        )
        val gen = JSONObject().put("temperature", 0.9).put("maxOutputTokens", 1024)
        if (model.contains("2.5-flash")) {
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        body.put("generationConfig", gen)

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException("HTTP $code ${err.take(300)}")
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val obj = JSONObject(json)
                    val cands = obj.optJSONArray("candidates") ?: continue
                    val parts = cands.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                        if (t.isNotEmpty()) onChunk(t)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
