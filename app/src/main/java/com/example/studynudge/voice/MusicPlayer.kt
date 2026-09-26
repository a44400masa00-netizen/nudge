package com.example.studynudge.voice

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * 指定した音楽アプリで、指定した曲・アーティストをハンズフリーで再生する。
 * 再生できたかどうかは、そのアプリの再生状態（MediaSession）で確認する。
 *
 * Spotify は「検索して再生」の標準の依頼(MEDIA_PLAY_FROM_SEARCH)への対応が弱く、
 * あいまいな全文検索で「それっぽい一番上」を選んでしまうため、別アーティストの曲や
 * 同じアーティストの別の曲が再生されることがある。Spotify の Client ID/Secret が
 * 設定されていれば、先に Web API で正確な曲を検索し、spotify:track:曲ID を直接開く
 * ことで、その問題を避ける（このときは検索に頼らないので、かなり正確になる）。
 *
 * それ以外のアプリ・上の方法が使えないときは、次を順に試す:
 *   1. 「検索して再生」の標準の依頼（曲名・アーティスト名を分けて渡す）
 *   2. そのアプリの検索画面を開いて、アプリの再生機能に「検索して再生」を依頼
 *   3. アプリを開いて、同じく「検索して再生」を依頼
 */
object MusicPlayer {

  class Result(val ok: Boolean, val message: String)

  private const val SPOTIFY_PKG = "com.spotify.music"

  fun play(ctx: Context, appName: String, title: String, artist: String, rawQuery: String): Result {
    // title/artist が無い古い呼び出しのための後方互換
    val songTitle = title.ifBlank { rawQuery }
    if (songTitle.isBlank()) return Result(false, "何を再生するか分かりませんでした。")
    if (!AppNotificationListener.isEnabled(ctx)) {
      return Result(false, "音楽をハンズフリーで再生するには、アプリの設定で「通知へのアクセス」を許可してください。")
    }
    val name = appName.ifBlank {
      ctx.getSharedPreferences(VoiceListenerService.PREFS, Context.MODE_PRIVATE).getString("music_app", "").orEmpty()
    }
    if (name.isBlank()) return Result(false, "音楽アプリが決まっていません。アプリの設定で、使う音楽アプリを入力してください。")
    val app = AppFinder.find(ctx, name) ?: return Result(false, "「$name」というアプリが見つかりませんでした。")
    val pkg = app.packageName
    val pm = ctx.packageManager
    // アプリ名だけの指定(query未使用)なら曲名検索、rawQueryがあり title/artist未分割ならそのまま使う
    val query = if (artist.isNotBlank()) "$songTitle $artist" else songTitle

    // 0) Spotify: Web API で正確な曲を見つけて、直接その曲を開く（設定されていれば最優先）
    if (pkg == SPOTIFY_PKG) {
      val prefs = ctx.getSharedPreferences(VoiceListenerService.PREFS, Context.MODE_PRIVATE)
      val clientId = prefs.getString("spotify_client_id", "").orEmpty()
      val clientSecret = prefs.getString("spotify_client_secret", "").orEmpty()
      val uri = SpotifyResolver.findTrackUri(clientId, clientSecret, songTitle, artist)
      if (uri != null) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
        Launcher.start(ctx, intent, pkg)
        if (waitPlaying(ctx, pkg, 6_000)) return Result(true, "")
        // 開けた/開けなかったに関わらず、正確な曲は指定できたのでここで終える
        // （検索頼みの方法に戻すと、せっかく特定した曲と違うものが再生されかねない）
        return Result(true, "")
      }
    }

    // 1) 標準の「検索して再生」（曲名・アーティスト名を分けて渡せるアプリはそちらを優先して読む）
    val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
      .setPackage(pkg)
      .putExtra(SearchManager.QUERY, query)
      .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio") // 曲の検索であることを明示
      .putExtra(MediaStore.EXTRA_MEDIA_TITLE, songTitle)
    if (artist.isNotBlank()) playIntent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
    if (playIntent.resolveActivity(pm) != null) {
      Launcher.start(ctx, playIntent, pkg)
      if (waitPlaying(ctx, pkg, 6_000)) return Result(true, "")
    }

    // 2) 検索画面を開いて、アプリの再生機能に依頼
    searchLink(pkg, query)?.let { link ->
      Launcher.start(ctx, link, pkg)
      if (tryControllerPlay(ctx, pkg, query, songTitle, artist)) return Result(true, "")
    }

    // 3) アプリを開いて、アプリの再生機能に依頼
    pm.getLaunchIntentForPackage(pkg)?.let { Launcher.start(ctx, it, pkg) }
    if (tryControllerPlay(ctx, pkg, query, songTitle, artist)) return Result(true, "")

    return Result(false, "${app.label}は開きましたが、自動では再生できませんでした。")
  }

  // ---- 再生状態の確認・操作 -------------------------------------------------------------

  private fun controllers(ctx: Context): List<MediaController> = try {
    val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    msm.getActiveSessions(ComponentName(ctx, AppNotificationListener::class.java))
  } catch (e: SecurityException) {
    emptyList()
  }

  private fun waitPlaying(ctx: Context, pkg: String, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (controllers(ctx).any { it.packageName == pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }) {
        return true
      }
      Thread.sleep(500)
    }
    return false
  }

  /** アプリの再生機能（MediaSession）が現れるのを待って、「検索して再生」を依頼する */
  private fun tryControllerPlay(ctx: Context, pkg: String, query: String, title: String, artist: String): Boolean {
    val deadline = System.currentTimeMillis() + 4_000
    var controller: MediaController? = null
    while (System.currentTimeMillis() < deadline) {
      controller = controllers(ctx).firstOrNull { it.packageName == pkg }
      if (controller != null) break
      Thread.sleep(400)
    }
    val c = controller ?: return false
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
      putString(MediaStore.EXTRA_MEDIA_TITLE, title)
      if (artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
    }
    c.transportControls.playFromSearch(query, extras)
    return waitPlaying(ctx, pkg, 5_000)
  }

  private fun searchLink(pkg: String, query: String): Intent? {
    val q = Uri.encode(query)
    val uri = when (pkg) {
      SPOTIFY_PKG -> "spotify:search:$q"
      "com.google.android.apps.youtube.music" -> "https://music.youtube.com/search?q=$q"
      "com.google.android.youtube" -> "https://www.youtube.com/results?search_query=$q"
      else -> return null
    }
    return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
  }
}
