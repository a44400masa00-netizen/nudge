package com.example.studynudge.voice

/**
 * 音声認識の文字列から「デイリー」の呼びかけを見つけ、その後ろの指示文を取り出す。
 * カタカナ→ひらがな、大文字→小文字、空白/記号の除去をしてから比較する。
 */
object WakeWord {

  // 正規化後（ひらがな・小文字）の表記ゆれ
  private val WORDS = listOf("でいりー", "でいりい", "でぃりー", "でぃりい", "daily")

  // 呼びかけの前に付けてよい文字数（「ねえ」「へい」「おっけー」などを許容）
  private const val MAX_PREFIX = 6

  private const val TRIM_CHARS = "、。,.!！?？"

  private fun normalizeChar(ch: Char): Char? {
    val code = ch.code
    return when {
      code in 0x30A1..0x30F6 -> (code - 0x60).toChar() // カタカナ → ひらがな
      ch == 'ー' -> ch
      ch.isLetterOrDigit() -> ch.lowercaseChar()
      else -> null // 空白・記号は捨てる
    }
  }

  /**
   * 呼びかけが含まれていれば、その後ろの指示文（無ければ空文字）を返す。含まれていなければ null。
   */
  fun extractCommand(raw: String): String? {
    val norm = StringBuilder()
    val rawIndex = ArrayList<Int>()
    raw.forEachIndexed { i, ch ->
      val n = normalizeChar(ch)
      if (n != null) {
        norm.append(n)
        rawIndex.add(i)
      }
    }
    val s = norm.toString()

    var bestPos = -1
    var bestLen = 0
    for (w in WORDS) {
      val p = s.indexOf(w)
      if (p in 0..MAX_PREFIX && (bestPos == -1 || p < bestPos || (p == bestPos && w.length > bestLen))) {
        bestPos = p
        bestLen = w.length
      }
    }
    if (bestPos == -1) return null

    val rawEnd = rawIndex[bestPos + bestLen - 1] + 1
    return raw.substring(rawEnd).trim { it.isWhitespace() || it in TRIM_CHARS }
  }

  // 会話を終える言葉（正規化後）。「デイリー」と一緒に言われたときだけ有効
  private val END_WORDS = listOf("戻って", "もどって", "戻ろう", "もどろう", "終了", "終わり", "おわり", "おしまい", "もういい")

  private fun normalizeAll(raw: String): String {
    val sb = StringBuilder()
    raw.forEach { ch -> normalizeChar(ch)?.let { sb.append(it) } }
    return sb.toString()
  }

  // 「デイリー」の聞き間違い（デリー・ディリー・ダイリー・dairy など）も含めて、呼びかけらしい音
  private val WAKE_LIKE = WORDS + listOf("でいり", "でぃり", "だいり", "でり", "dairy", "でいりい")

  /**
   * 「デイリー戻って」「デイリー戻っていいよ」など、会話を終える呼びかけか。
   *  - 「戻って」などの終了の言葉が無ければ false
   *  - 呼びかけ（の聞き間違い）が一緒にあれば true
   *  - 呼びかけが認識されなくても、短い発話（「戻って」だけ等）なら true。
   *    音声認識は「デイリー」と「戻って」を別々の発話として返すことがあるため
   */
  fun isEndCommand(raw: String): Boolean {
    val s = normalizeAll(raw)
    if (END_WORDS.none { s.contains(it) }) return false
    return WAKE_LIKE.any { s.contains(it) } || s.length <= SHORT_UTTERANCE
  }

  private const val SHORT_UTTERANCE = 10
}
