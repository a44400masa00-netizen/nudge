package com.example.studynudge.voice

/** サービス → JS（アプリが開いているとき）へイベントを流す小さな橋渡し */
object VoiceBus {
  @Volatile
  var listener: ((String, Map<String, Any?>) -> Unit)? = null

  fun emit(name: String, payload: Map<String, Any?>) {
    try {
      listener?.invoke(name, payload)
    } catch (e: Exception) {
      // JS 側が終了済みなどで送れない場合は無視
    }
  }
}
