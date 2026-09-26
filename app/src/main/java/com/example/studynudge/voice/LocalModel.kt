package com.example.studynudge.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 端末内AI（Qwen2.5 3B Instruct, GGUF）のモデルファイルのダウンロードと状態管理。
 * ダウンロードは Android 標準の DownloadManager に任せる（Wi-Fi のみ・中断しても再開・通知バーに進捗）。
 * ファイルはこのアプリ専用の領域に保存され、他のアプリからは見えない。
 */
object LocalModel {

  const val FILE_NAME = "qwen2.5-3b-instruct-q4_k_m.gguf"
  // Qwen公式のGGUF配布（Q4_K_M量子化）。実機での動作確認は「Qwen/Qwen2.5-3B-Instruct-GGUF」で行われたもの
  private const val URL =
    "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"

  // 完了とみなす最小サイズ（実際は約 2.1GB）
  private const val MIN_BYTES = 1_900_000_000L
  private const val KEY_DL_ID = "model_dl_id"

  fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE_NAME)

  private fun prefs(ctx: Context) = ctx.getSharedPreferences(VoiceListenerService.PREFS, Context.MODE_PRIVATE)

  private fun result(state: String, downloaded: Long, total: Long): Map<String, Any> =
    mapOf("state" to state, "downloaded" to downloaded, "total" to total)

  fun isReady(ctx: Context): Boolean = status(ctx)["state"] == "ready"

  /** state: none / downloading / ready / failed */
  fun status(ctx: Context): Map<String, Any> {
    val p = prefs(ctx)
    val id = p.getLong(KEY_DL_ID, -1L)
    val f = file(ctx)

    if (id != -1L) {
      val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      var found = false
      var state = "downloading"
      var done = 0L
      var total = 0L
      dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (c.moveToFirst()) {
          found = true
          val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
          done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
          total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
          state = when (st) {
            DownloadManager.STATUS_SUCCESSFUL -> "ready"
            DownloadManager.STATUS_FAILED -> "failed"
            else -> "downloading"
          }
        }
      }
      if (found) {
        when (state) {
          "ready" -> {
            p.edit().remove(KEY_DL_ID).apply()
            return result("ready", f.length(), f.length())
          }
          "failed" -> {
            p.edit().remove(KEY_DL_ID).apply()
            dm.remove(id)
            f.delete()
            return result("failed", 0L, 0L)
          }
          else -> return result("downloading", done, total)
        }
      }
      p.edit().remove(KEY_DL_ID).apply() // ダウンロードの記録が消えていた
    }
    return if (f.exists() && f.length() >= MIN_BYTES) result("ready", f.length(), f.length())
    else result("none", 0L, 0L)
  }

  fun start(ctx: Context) {
    val state = status(ctx)["state"]
    if (state == "ready" || state == "downloading") return

    file(ctx).delete() // 中途半端なファイルが残っていると別名で保存されることがあるため
    val request = DownloadManager.Request(Uri.parse(URL))
      .setTitle("デイリー: 端末内AIモデル")
      .setDescription("Qwen2.5 3B Instruct（約2.1GB）")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI) // 容量が大きいので Wi-Fi のみ
      .setDestinationInExternalFilesDir(ctx, null, FILE_NAME)
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val id = dm.enqueue(request)
    prefs(ctx).edit().putLong(KEY_DL_ID, id).apply()
  }

  /** ダウンロードの中止、または保存済みモデルの削除 */
  fun deleteAll(ctx: Context) {
    val p = prefs(ctx)
    val id = p.getLong(KEY_DL_ID, -1L)
    if (id != -1L) {
      (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
      p.edit().remove(KEY_DL_ID).apply()
    }
    LocalLlm.release()
    file(ctx).delete()
  }
}
