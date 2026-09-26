package com.example.studynudge.voice

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify で「同じアーティストの別の曲」「全然違うアーティスト」が再生されてしまう問題への対応。
 *
 * MEDIA_PLAY_FROM_SEARCH や spotify:search: は、Spotify 側があいまいな全文検索の
 * 「それっぽい一番上」を選ぶため、精度が低い。
 * ここでは Spotify の Web API（Client Credentials。ユーザーのログインは不要）で
 * 曲名とアーティスト名から正確な曲を検索し、spotify:track:曲ID を組み立てる。
 * この形式で開くと、Spotify はその1曲を正確に再生する。
 *
 * 使うには、設定で Spotify の Client ID / Client Secret（developer.spotify.com で無料発行）が必要。
 * 未設定、またはこの処理に失敗したときは、呼び出し側が曖昧検索にフォールバックする。
 */
object SpotifyResolver {

  private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
  private const val SEARCH_URL = "https://api.spotify.com/v1/search"

  @Volatile private var cachedToken: String? = null
  @Volatile private var cachedExpiry: Long = 0L

  /** 曲名・アーティスト名から、Spotify の曲URI（spotify:track:xxxx）を探す。見つからなければ null */
  fun findTrackUri(clientId: String, clientSecret: String, title: String, artist: String): String? {
    if (clientId.isBlank() || clientSecret.isBlank() || title.isBlank()) return null
    return try {
      val token = getToken(clientId, clientSecret) ?: return null
      search(token, title, artist)
    } catch (e: Exception) {
      null
    }
  }

  @Synchronized
  private fun getToken(clientId: String, clientSecret: String): String? {
    val now = System.currentTimeMillis()
    cachedToken?.let { if (now < cachedExpiry) return it }

    val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
    return try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 8_000
      conn.readTimeout = 8_000
      conn.doOutput = true
      val basic = android.util.Base64.encodeToString(
        "$clientId:$clientSecret".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
      )
      conn.setRequestProperty("Authorization", "Basic $basic")
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray(Charsets.UTF_8)) }

      if (conn.responseCode != 200) return null
      val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
      val token = json.optString("access_token").ifBlank { return null }
      // 期限の少し前に切れたことにして、境界での失敗を避ける
      cachedExpiry = now + (json.optLong("expires_in", 3600) - 60) * 1000
      cachedToken = token
      token
    } catch (e: IOException) {
      null
    } finally {
      conn.disconnect()
    }
  }

  private fun search(token: String, title: String, artist: String): String? {
    // track:曲名 artist:アーティスト名 という絞り込み検索クエリを組み立てる（空なら省略）
    val q = buildString {
      append("track:").append(title)
      if (artist.isNotBlank()) append(" artist:").append(artist)
    }
    val url = URL("$SEARCH_URL?type=track&limit=1&q=${URLEncoder.encode(q, "UTF-8")}")
    val conn = url.openConnection() as HttpURLConnection
    return try {
      conn.requestMethod = "GET"
      conn.connectTimeout = 8_000
      conn.readTimeout = 8_000
      conn.setRequestProperty("Authorization", "Bearer $token")

      if (conn.responseCode != 200) return null
      val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
      var item = json.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)

      // track:/artist: で絞り込みすぎて0件のときは、素の全文検索でもう一度だけ試す
      if (item == null && artist.isNotBlank()) {
        item = plainSearch(token, "$title $artist")
      }
      item?.optString("uri")?.takeIf { it.startsWith("spotify:track:") }
    } catch (e: IOException) {
      null
    } finally {
      conn.disconnect()
    }
  }

  private fun plainSearch(token: String, query: String): JSONObject? {
    val url = URL("$SEARCH_URL?type=track&limit=1&q=${URLEncoder.encode(query, "UTF-8")}")
    val conn = url.openConnection() as HttpURLConnection
    return try {
      conn.requestMethod = "GET"
      conn.connectTimeout = 8_000
      conn.readTimeout = 8_000
      conn.setRequestProperty("Authorization", "Bearer $token")
      if (conn.responseCode != 200) return null
      val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
      json.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)
    } catch (e: IOException) {
      null
    } finally {
      conn.disconnect()
    }
  }
}
