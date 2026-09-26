package com.example.studynudge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.studynudge.reminder.Engine as ReminderEngine
import com.example.studynudge.voice.LocalModel
import com.example.studynudge.voice.VoiceListenerService
import com.example.studynudge.voice.VoicePrefs
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var reminderPrefs: com.example.studynudge.reminder.Prefs
    private lateinit var voicePrefs: VoicePrefs

    private lateinit var statusView: TextView
    private lateinit var apiKeyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var intervalEdit: EditText
    private lateinit var bedtimeEdit: EditText
    private lateinit var earlyEdit: EditText
    private lateinit var toggleButton: Button
    private val placeViews = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    // --- しつこい通知（在宅学習） ---
    private lateinit var reminderEnabledSwitch: Switch
    private lateinit var reminderTargetView: TextView
    private lateinit var reminderStartEdit: EditText
    private lateinit var reminderEndEdit: EditText
    private lateinit var reminderStatusView: TextView

    // --- 音声アシスタント（デイリー機能） ---
    private lateinit var voiceEnabledSwitch: Switch
    private lateinit var voiceApiKeyEdit: EditText
    private lateinit var voiceModelEdit: EditText
    private lateinit var voiceSpeakSwitch: Switch
    private lateinit var voiceBrainSpinner: Spinner
    private lateinit var voiceCallNameSpinner: Spinner
    private lateinit var voiceToneSpinner: Spinner
    private lateinit var voiceMusicAppEdit: EditText
    private lateinit var voiceSpotifyIdEdit: EditText
    private lateinit var voiceSpotifySecretEdit: EditText
    private lateinit var voiceModelStatusView: TextView
    private lateinit var voiceStatusView: TextView

    private val brainOptions = listOf(
        "auto" to "自動（Geminiが使えない時だけ端末内AI）",
        "cloud" to "クラウドのみ（Gemini）",
        "device" to "端末内AIのみ（オフライン可・要ダウンロード）"
    )
    private val callNameOptions = listOf("masa" to "「まさ」と呼ぶ", "you" to "「あなた」と呼ぶ")
    private val toneOptions = listOf("polite" to "丁寧な敬語", "casual" to "タメ口")

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val appPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val pkg = result.data?.getStringExtra("package")
                val label = result.data?.getStringExtra("label")
                if (pkg != null) {
                    reminderPrefs.targetPackage = pkg
                    reminderPrefs.targetAppLabel = label ?: pkg
                    refresh()
                }
            }
        }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun label(t: String, size: Float = 16f, bold: Boolean = true): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(0, dp(16f), 0, dp(4f))
        return tv
    }

    private fun note(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setPadding(0, dp(2f), 0, dp(6f))
        return tv
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = MaterialButton(this)
        b.text = t
        b.setOnClickListener { onClick() }
        return b
    }

    private fun edit(hintText: String, value: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.setText(value)
        return e
    }

    private fun switchRow(text: String, initial: Boolean): Switch {
        val s = Switch(this)
        s.text = text
        s.isChecked = initial
        return s
    }

    private fun spinnerFor(options: List<Pair<String, String>>, current: String): Spinner {
        val sp = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        val idx = options.indexOfFirst { it.first == current }
        if (idx >= 0) sp.setSelection(idx)
        return sp
    }

    private fun spinnerValue(spinner: Spinner, options: List<Pair<String, String>>): String =
        options.getOrNull(spinner.selectedItemPosition)?.first ?: options[0].first

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        reminderPrefs = com.example.studynudge.reminder.Prefs(this)
        voicePrefs = VoicePrefs(this)
        com.example.studynudge.reminder.NotificationHelper.createChannels(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(40f), dp(16f), dp(40f))
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label(getString(R.string.app_name), 24f))
        statusView = TextView(this)
        root.addView(statusView)

        // --- 権限 ---
        root.addView(label("① 権限の設定"))
        root.addView(button("他のアプリの上に表示を許可") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("使用状況へのアクセスを許可") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("位置情報・カレンダー・マイク・通知を許可") { requestRuntimePerms() })
        root.addView(button("通知へのアクセスを許可（音楽操作・ハンズフリー起動用）") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("正確なアラームを許可（端末により表示されないことがあります）") {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "この端末では自動で開けませんでした", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "この端末では追加の許可は不要です", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(button("電池の最適化の設定を開く（強く推奨）") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(button("画面の明るさ操作を許可（音声アシスタント用・任意）") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(button("おやすみモードの操作を許可（音声アシスタント用・任意）") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        root.addView(
            note(
                "※ APKを直接インストールした場合、許可の画面が押せないことがあります。その時は" +
                    "「設定 > アプリ > ${getString(R.string.app_name)} > 右上の︙ > 制限付き設定を許可」を先に行ってください。"
            )
        )

        // --- AI ---
        root.addView(label("② AI（Gemini）の設定　※勉強の声かけ用"))
        apiKeyEdit = edit("Gemini APIキー", prefs.apiKey, false)
        root.addView(apiKeyEdit)
        modelEdit = edit("モデル名", prefs.model, false)
        root.addView(modelEdit)

        // --- 場所 ---
        root.addView(label("③ 場所の登録（地図をタップしてピンを置く）"))
        for ((key, name) in PlaceKeys.ALL) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val tv = TextView(this)
            placeViews[key] = tv
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("地図で設定") {
                val i = Intent(this, MapPickerActivity::class.java)
                i.putExtra("key", key)
                i.putExtra("label", name)
                startActivity(i)
            })
            row.addView(button("消去") {
                prefs.clearPlace(key)
                refresh()
            })
            root.addView(row)
        }

        // --- 動作設定 ---
        root.addView(label("④ 動作の設定　※勉強の声かけ用"))
        root.addView(TextView(this).apply { text = "通常の声かけ間隔（分）" })
        intervalEdit = edit("通常の声かけ間隔（分）", prefs.intervalMin.toString(), true)
        root.addView(intervalEdit)
        root.addView(TextView(this).apply { text = "就寝時刻（この時刻以降の使用で「夜更かし」メッセージ, 0-23）" })
        bedtimeEdit = edit("就寝時刻（時, 0-23）", prefs.bedtimeHour.toString(), true)
        root.addView(bedtimeEdit)
        root.addView(TextView(this).apply { text = "早朝の基準（この時刻より前に使い始めると「おはようございます」メッセージ）" })
        earlyEdit = edit("早朝の基準（時, 0-23）", prefs.earlyHour.toString(), true)
        root.addView(earlyEdit)
        root.addView(button("設定を保存") { saveSettings() })

        // --- 実行 ---
        root.addView(label("⑤ 見守りの実行"))
        toggleButton = button("見守りを開始") { toggleService() }
        root.addView(toggleButton)
        root.addView(button("今すぐテスト表示") { testNow() })
        root.addView(
            note("「見守り」を開始すると、④の声かけと、⑥のしつこい通知の両方がまとめて動きます。")
        )

        // --- ⑥ しつこい通知（在宅学習） ---
        root.addView(label("⑥ しつこい通知（在宅学習）"))
        root.addView(
            note(
                "設定した時間帯に「自宅」（③で登録した地図ピン）にいると声をかけ、" +
                    "対象アプリを開くまで数分おきに催促します。在宅の判定は③の「自宅」ピンを使うので、" +
                    "別途Wi-Fiの登録は不要です。"
            )
        )
        reminderEnabledSwitch = switchRow("しつこい通知を有効にする", reminderPrefs.enabled)
        root.addView(reminderEnabledSwitch)

        val targetRow = LinearLayout(this)
        targetRow.orientation = LinearLayout.HORIZONTAL
        reminderTargetView = TextView(this)
        targetRow.addView(reminderTargetView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        targetRow.addView(button("アプリを選ぶ") {
            appPickerLauncher.launch(Intent(this, AppPickerActivity::class.java))
        })
        root.addView(targetRow)

        root.addView(TextView(this).apply { text = "開始時刻（時, 0-23）" })
        reminderStartEdit = edit("開始時刻（時）", (reminderPrefs.windowStartMinutes / 60).toString(), true)
        root.addView(reminderStartEdit)
        root.addView(TextView(this).apply { text = "終了時刻（時, 0-23）" })
        reminderEndEdit = edit("終了時刻（時）", (reminderPrefs.windowEndMinutes / 60).toString(), true)
        root.addView(reminderEndEdit)
        root.addView(button("この設定を保存") { saveReminderSettings() })
        reminderStatusView = TextView(this)
        root.addView(reminderStatusView)
        root.addView(button("状態を確認する") { showReminderStatus() })
        root.addView(
            note(
                "「今日はもう休む」を選んだ日は、その日はもう催促しません。" +
                    "翌日、日付が変わると自動的にリセットされます。"
            )
        )

        // --- ⑦ 音声アシスタント（デイリー機能） ---
        root.addView(label("⑦ 音声アシスタント（デイリー機能）"))
        root.addView(
            note(
                "「ヘイ、デイリー」と呼びかけると起動する、常駐の音声アシスタントです。" +
                    "タイマー・アラーム・音楽再生・ライト・音量などをハンズフリーで操作できます。" +
                    "呼びかけの言葉（ヘイ、デイリー）は学習済みモデルに固定されており、アプリ名を変えても変更できません。"
            )
        )
        voiceEnabledSwitch = switchRow("音声アシスタントを有効にする", VoiceListenerService.running)
        voiceEnabledSwitch.setOnCheckedChangeListener { _, isChecked -> onVoiceSwitchChanged(isChecked) }
        root.addView(voiceEnabledSwitch)

        voiceApiKeyEdit = edit("Gemini APIキー（空欄なら②と同じキーを使用）", voicePrefs.apiKey, false)
        root.addView(voiceApiKeyEdit)
        voiceModelEdit = edit("モデル名", voicePrefs.model, false)
        root.addView(voiceModelEdit)

        voiceSpeakSwitch = switchRow("声で読み上げる", voicePrefs.speak)
        root.addView(voiceSpeakSwitch)

        root.addView(TextView(this).apply { text = "頭脳の選び方" })
        voiceBrainSpinner = spinnerFor(brainOptions, voicePrefs.brain)
        root.addView(voiceBrainSpinner)

        root.addView(TextView(this).apply { text = "呼び方" })
        voiceCallNameSpinner = spinnerFor(callNameOptions, voicePrefs.callName)
        root.addView(voiceCallNameSpinner)

        root.addView(TextView(this).apply { text = "話し方" })
        voiceToneSpinner = spinnerFor(toneOptions, voicePrefs.tone)
        root.addView(voiceToneSpinner)

        voiceMusicAppEdit = edit("音楽アプリ名（例: Spotify）", voicePrefs.musicApp, false)
        root.addView(voiceMusicAppEdit)

        root.addView(note("Spotifyを使う場合のみ、下2つを入力すると曲の再生精度が上がります（任意・Spotify for Developersで無料取得）"))
        voiceSpotifyIdEdit = edit("Spotify Client ID（任意）", voicePrefs.spotifyClientId, false)
        root.addView(voiceSpotifyIdEdit)
        voiceSpotifySecretEdit = edit("Spotify Client Secret（任意）", voicePrefs.spotifyClientSecret, false)
        root.addView(voiceSpotifySecretEdit)

        root.addView(button("この設定を保存") { saveVoiceSettings() })

        root.addView(label("端末内AI（オフラインモデル）", 14f))
        voiceModelStatusView = TextView(this)
        root.addView(voiceModelStatusView)
        val modelRow = LinearLayout(this)
        modelRow.orientation = LinearLayout.HORIZONTAL
        modelRow.addView(button("ダウンロード（Wi-Fi推奨・約2.1GB）") {
            LocalModel.start(this)
            Toast.makeText(this, "通知バーでダウンロードの進み具合を確認できます", Toast.LENGTH_LONG).show()
            handler.postDelayed({ refresh() }, 1500L)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modelRow.addView(button("削除") {
            LocalModel.deleteAll(this)
            refresh()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(modelRow)
        root.addView(
            note(
                "この機能はビルドが失敗しやすい部分です。ビルドエラーになる場合は、app/build.gradle.kts の " +
                    "dev.ffmpegkit-maintained:llama-android の行を削除して再ビルドしてください（クラウド版のみで動きます）。"
            )
        )

        voiceStatusView = TextView(this)
        root.addView(voiceStatusView)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestRuntimePerms() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.add(Manifest.permission.READ_CALENDAR)
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }

    private fun saveSettings() {
        prefs.apiKey = apiKeyEdit.text.toString().trim()
        val m = modelEdit.text.toString().trim()
        if (m.isNotEmpty()) prefs.model = m
        prefs.intervalMin = (intervalEdit.text.toString().toIntOrNull() ?: 30).coerceIn(5, 600)
        prefs.bedtimeHour = (bedtimeEdit.text.toString().toIntOrNull() ?: 23).coerceIn(0, 23)
        prefs.earlyHour = (earlyEdit.text.toString().toIntOrNull() ?: 5).coerceIn(0, 23)
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun saveReminderSettings() {
        reminderPrefs.enabled = reminderEnabledSwitch.isChecked
        val startHour = (reminderStartEdit.text.toString().toIntOrNull() ?: 18).coerceIn(0, 23)
        val endHour = (reminderEndEdit.text.toString().toIntOrNull() ?: 23).coerceIn(0, 23)
        reminderPrefs.windowStartMinutes = startHour * 60
        reminderPrefs.windowEndMinutes = endHour * 60
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun saveVoiceSettings() {
        voicePrefs.apiKey = voiceApiKeyEdit.text.toString().trim()
        val m = voiceModelEdit.text.toString().trim()
        if (m.isNotEmpty()) voicePrefs.model = m
        voicePrefs.speak = voiceSpeakSwitch.isChecked
        voicePrefs.brain = spinnerValue(voiceBrainSpinner, brainOptions)
        voicePrefs.callName = spinnerValue(voiceCallNameSpinner, callNameOptions)
        voicePrefs.tone = spinnerValue(voiceToneSpinner, toneOptions)
        voicePrefs.musicApp = voiceMusicAppEdit.text.toString().trim()
        voicePrefs.spotifyClientId = voiceSpotifyIdEdit.text.toString().trim()
        voicePrefs.spotifyClientSecret = voiceSpotifySecretEdit.text.toString().trim()
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun canStart(): Boolean {
        if (!Perm.hasLocation(this)) {
            Toast.makeText(this, "先に位置情報を許可してください", Toast.LENGTH_LONG).show()
            return false
        }
        if (!Perm.hasOverlay(this)) {
            Toast.makeText(this, "先に「他のアプリの上に表示」を許可してください", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun toggleService() {
        saveSettings()
        saveReminderSettings()
        if (NudgeService.running) {
            prefs.wasRunning = false
            stopService(Intent(this, NudgeService::class.java))
        } else {
            if (!canStart()) return
            prefs.wasRunning = true
            ContextCompat.startForegroundService(this, Intent(this, NudgeService::class.java))
        }
        handler.postDelayed({ refresh() }, 600L)
    }

    private fun testNow() {
        saveSettings()
        if (!canStart()) return
        val i = Intent(this, NudgeService::class.java)
        i.action = NudgeService.ACTION_TEST
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "ホーム画面などに移動して待ってください", Toast.LENGTH_LONG).show()
        handler.postDelayed({ refresh() }, 8000L)
    }

    private fun onVoiceSwitchChanged(wantOn: Boolean) {
        if (wantOn == VoiceListenerService.running) return
        saveVoiceSettings()
        if (wantOn) {
            if (!Perm.hasMic(this)) {
                Toast.makeText(this, "先にマイクを許可してください", Toast.LENGTH_LONG).show()
                voiceEnabledSwitch.isChecked = false
                return
            }
            if (voicePrefs.apiKey.isBlank() && prefs.apiKey.isNotBlank()) {
                voicePrefs.apiKey = prefs.apiKey
            }
            voicePrefs.enabled = true
            ContextCompat.startForegroundService(this, Intent(this, VoiceListenerService::class.java))
        } else {
            voicePrefs.enabled = false
            stopService(Intent(this, VoiceListenerService::class.java))
        }
        handler.postDelayed({ refresh() }, 600L)
    }

    private fun lastKnownLocation(): android.location.Location? {
        if (!Perm.hasLocation(this)) return null
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun isAtHome(): Boolean {
        val home = prefs.getPlace("home") ?: return false
        val loc = lastKnownLocation() ?: return false
        val res = FloatArray(1)
        android.location.Location.distanceBetween(loc.latitude, loc.longitude, home.lat, home.lng, res)
        return res[0] < home.radius.toFloat()
    }

    private fun showReminderStatus() {
        reminderStatusView.text = ReminderEngine.debugStatus(this, isAtHome())
    }

    private fun refresh() {
        val sb = StringBuilder()
        fun line(ok: Boolean, t: String) {
            sb.append(if (ok) "✅ " else "❌ ").append(t).append("\n")
        }
        line(Perm.hasOverlay(this), "他のアプリの上に表示")
        line(Perm.hasUsage(this), "使用状況へのアクセス")
        line(Perm.hasLocation(this), "位置情報")
        line(Perm.hasCalendar(this), "カレンダー")
        line(Perm.hasMic(this), "マイク")
        line(Perm.hasNotificationListener(this), "通知へのアクセス")
        line(Perm.hasExactAlarm(this), "正確なアラーム")
        line(Perm.isIgnoringBatteryOptimizations(this), "電池の最適化から除外")
        line(prefs.apiKey.isNotBlank(), "Gemini APIキー（勉強の声かけ）")
        line(NudgeService.running, if (NudgeService.running) "見守り中" else "停止中")
        val err = prefs.lastError
        if (err.isNotEmpty()) sb.append("\n直近のエラー: ").append(err)
        statusView.text = sb.toString()

        for ((key, name) in PlaceKeys.ALL) {
            val p = prefs.getPlace(key)
            placeViews[key]?.text = if (p == null) {
                "$name: 未設定"
            } else {
                String.format(Locale.US, "%s: %.4f, %.4f（半径%dm）", name, p.lat, p.lng, p.radius)
            }
        }
        toggleButton.text = if (NudgeService.running) "見守りを停止" else "見守りを開始"

        // ⑥ しつこい通知
        val label = reminderPrefs.targetAppLabel
        reminderTargetView.text = "対象アプリ: ${label ?: "未設定"}"
        val rsb = StringBuilder()
        rsb.append(if (reminderPrefs.restedToday) "今日: 休み中\n" else "今日: 通常\n")
        rsb.append("警告レベル: ${reminderPrefs.warningCount}\n")
        rsb.append(if (reminderPrefs.studying) "現在: 対象アプリを使用中\n" else "")
        reminderStatusView.text = rsb.toString()

        // ⑦ 音声アシスタント
        val ms = LocalModel.status(this)
        val state = ms["state"] as? String ?: "none"
        voiceModelStatusView.text = when (state) {
            "ready" -> "状態: 準備完了（使用可能）"
            "downloading" -> {
                val d = (ms["downloaded"] as? Long) ?: 0L
                val t = (ms["total"] as? Long) ?: 0L
                val pct = if (t > 0) (d * 100 / t) else 0
                "状態: ダウンロード中（${pct}%）"
            }
            "failed" -> "状態: ダウンロード失敗"
            else -> "状態: 未ダウンロード"
        }
        val vsb = StringBuilder()
        vsb.append(if (VoiceListenerService.running) "音声アシスタント: 動作中\n" else "音声アシスタント: 停止中\n")
        if (!Perm.hasMic(this)) vsb.append("※マイクの許可が必要です\n")
        if (!Perm.hasNotificationListener(this)) vsb.append("※音楽操作・ハンズフリー起動には「通知へのアクセス」が必要です\n")
        voiceStatusView.text = vsb.toString()
    }
}
