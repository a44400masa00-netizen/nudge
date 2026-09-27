package com.example.studynudge

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.example.studynudge.reminder.Engine as ReminderEngine
import com.example.studynudge.voice.LocalModel
import com.example.studynudge.voice.VoiceListenerService
import com.example.studynudge.voice.VoicePrefs
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var reminderPrefs: com.example.studynudge.reminder.Prefs
    private lateinit var voicePrefs: VoicePrefs
    private lateinit var relayPrefs: RelayPrefs

    private lateinit var statusView: TextView
    private lateinit var apiKeyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var intervalEdit: EditText
    private lateinit var bedtimeEdit: EditText
    private lateinit var earlyEdit: EditText
    private lateinit var toggleSwitch: MaterialSwitch
    private val placeRows = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    // --- しつこい通知（在宅学習） ---
    private lateinit var reminderEnabledSwitch: MaterialSwitch
    private lateinit var reminderTargetView: TextView
    private lateinit var reminderStartEdit: EditText
    private lateinit var reminderEndEdit: EditText
    private lateinit var reminderStatusView: TextView

    // --- パソコンのローカルAI中継 ---
    private lateinit var relayEnabledSwitch: MaterialSwitch
    private lateinit var relayDbUrlEdit: EditText
    private lateinit var relaySecretEdit: EditText
    private lateinit var relayStatusView: TextView

    // --- 音声アシスタント（デイリー機能） ---
    private lateinit var voiceEnabledSwitch: MaterialSwitch
    private lateinit var voiceApiKeyEdit: EditText
    private lateinit var voiceModelEdit: EditText
    private lateinit var voiceSpeakSwitch: MaterialSwitch
    private lateinit var voiceBrainDropdown: MaterialAutoCompleteTextView
    private lateinit var voiceCallNameDropdown: MaterialAutoCompleteTextView
    private lateinit var voiceToneDropdown: MaterialAutoCompleteTextView
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

    // ============================ 見た目まわりの小さな部品 ============================

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /** アイコンを、色付きの円の中に置いたバッジを作る（Material 3 の「アイコンコンテナ」） */
    private fun iconBadge(iconRes: Int, bgAttr: Int, fgAttr: Int): FrameLayout {
        val frame = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(themeColor(bgAttr))
        frame.background = bg
        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(themeColor(fgAttr)))
        val ivLp = FrameLayout.LayoutParams(dp(22f), dp(22f))
        ivLp.gravity = Gravity.CENTER
        frame.addView(iv, ivLp)
        return frame
    }

    /** 大きく角丸のカードを1枚作り、その中身を入れるための入れ物を返す */
    private fun card(pageRoot: LinearLayout, iconRes: Int, badgeBg: Int, badgeFg: Int, title: String, subtitle: String? = null): LinearLayout {
        val cv = MaterialCardView(this)
        cv.radius = dp(24f).toFloat()
        cv.cardElevation = 0f
        cv.strokeWidth = 0
        cv.setCardBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        val outerLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        outerLp.topMargin = dp(16f)
        pageRoot.addView(cv, outerLp)

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(dp(20f), dp(20f), dp(20f), dp(18f))
        cv.addView(inner)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(iconBadge(iconRes, badgeBg, badgeFg), LinearLayout.LayoutParams(dp(40f), dp(40f)))

        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.setPadding(dp(14f), 0, 0, 0)
        val titleTv = TextView(this)
        titleTv.text = title
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        titleTv.setTypeface(titleTv.typeface, Typeface.BOLD)
        titleTv.setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        titleCol.addView(titleTv)
        if (subtitle != null) {
            val subTv = TextView(this)
            subTv.text = subtitle
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            subTv.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            titleCol.addView(subTv)
        }
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inner.addView(header)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, dp(14f), 0, 0)
        inner.addView(content)
        return content
    }

    /** ノート（小さな注意書き） */
    private fun note(container: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        tv.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        tv.setPadding(dp(4f), dp(8f), dp(4f), dp(4f))
        container.addView(tv)
    }

    /** タップできる領域を広く取った、見出し＋説明＋矢印の行 */
    private fun actionRow(container: LinearLayout, title: String, subtitle: String? = null, showChevron: Boolean = true, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56f)
        if (onClick != null) {
            row.isClickable = true
            row.isFocusable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            row.setBackgroundResource(outValue.resourceId)
            row.setOnClickListener { onClick() }
        }
        row.setPadding(dp(4f), dp(8f), dp(4f), dp(8f))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val titleTv = TextView(this)
        titleTv.text = title
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        titleTv.setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        col.addView(titleTv)
        if (subtitle != null) {
            val subTv = TextView(this)
            subTv.text = subtitle
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            subTv.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            subTv.setPadding(0, dp(2f), 0, 0)
            col.addView(subTv)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (showChevron) {
            val chev = ImageView(this)
            chev.setImageResource(R.drawable.ic_chevron_right)
            ImageViewCompat.setImageTintList(chev, ColorStateList.valueOf(themeColor(MaterialR.attr.colorOnSurfaceVariant)))
            row.addView(chev, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        }
        container.addView(row)
        return row
    }

    /** 見出し＋説明＋右端に揃えたスイッチの行 */
    private fun switchRow(container: LinearLayout, title: String, subtitle: String? = null, initial: Boolean): MaterialSwitch {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56f)
        row.setPadding(dp(4f), dp(8f), dp(4f), dp(8f))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val titleTv = TextView(this)
        titleTv.text = title
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        titleTv.setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        col.addView(titleTv)
        if (subtitle != null) {
            val subTv = TextView(this)
            subTv.text = subtitle
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            subTv.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            subTv.setPadding(0, dp(2f), 0, 0)
            col.addView(subTv)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = MaterialSwitch(this)
        sw.isChecked = initial
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(row)
        return sw
    }

    /** 角丸の、輪郭線タイプの入力欄 */
    private fun textField(container: LinearLayout, label: String, value: String, numeric: Boolean = false): EditText {
        val til = TextInputLayout(this)
        til.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        til.setBoxCornerRadii(dp(16f).toFloat(), dp(16f).toFloat(), dp(16f).toFloat(), dp(16f).toFloat())
        til.hint = label
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10f)
        val edit = TextInputEditText(til.context)
        edit.setText(value)
        edit.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        til.addView(edit)
        container.addView(til, lp)
        return edit
    }

    /** 角丸の、選ぶだけのドロップダウン欄（Material 3 の Exposed Dropdown Menu） */
    private fun dropdownField(container: LinearLayout, label: String, options: List<Pair<String, String>>, current: String): MaterialAutoCompleteTextView {
        val til = TextInputLayout(this)
        til.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        til.setBoxCornerRadii(dp(16f).toFloat(), dp(16f).toFloat(), dp(16f).toFloat(), dp(16f).toFloat())
        til.hint = label
        til.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10f)
        val actv = MaterialAutoCompleteTextView(til.context)
        actv.inputType = InputType.TYPE_NULL
        actv.keyListener = null
        actv.setSimpleItems(options.map { it.second }.toTypedArray())
        val idx = options.indexOfFirst { it.first == current }
        if (idx >= 0) actv.setText(options[idx].second, false)
        til.addView(actv)
        container.addView(til, lp)
        return actv
    }

    private fun dropdownValue(actv: MaterialAutoCompleteTextView, options: List<Pair<String, String>>): String =
        options.firstOrNull { it.second == actv.text.toString() }?.first ?: options[0].first

    /** 角丸で、横幅いっぱいの塗りつぶしボタン */
    private fun filledButton(container: LinearLayout, text: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this)
        b.text = text
        b.cornerRadius = dp(20f)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f))
        lp.topMargin = dp(12f)
        container.addView(b, lp)
        return b
    }

    /** 角丸の、控えめな（アウトライン）ボタン。横並びで使う */
    private fun outlinedButton(container: LinearLayout, text: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.cornerRadius = dp(20f)
        b.setOnClickListener { onClick() }
        return b
    }

    // ================================ 画面の組み立て ================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        reminderPrefs = com.example.studynudge.reminder.Prefs(this)
        voicePrefs = VoicePrefs(this)
        relayPrefs = RelayPrefs(this)
        com.example.studynudge.reminder.NotificationHelper.createChannels(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(themeColor(android.R.attr.colorBackground))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(44f), dp(16f), dp(40f))
        scroll.addView(root)
        setContentView(scroll)

        val appTitle = TextView(this)
        appTitle.text = getString(R.string.app_name)
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        appTitle.setTypeface(appTitle.typeface, Typeface.BOLD)
        appTitle.setTextColor(themeColor(MaterialR.attr.colorOnBackground))
        root.addView(appTitle)

        // --- 状態 ---
        val statusContent = card(root, R.drawable.ic_check_circle, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer, "状態")
        statusView = TextView(this)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        statusView.setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        statusView.setLineSpacing(dp(4f).toFloat(), 1f)
        statusContent.addView(statusView)

        // --- ① 権限 ---
        val permContent = card(root, R.drawable.ic_lock, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer, "権限", "順番に許可していってください")
        actionRow(permContent, "他のアプリの上に表示") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        actionRow(permContent, "使用状況へのアクセス") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        actionRow(permContent, "位置情報・カレンダー・マイク・通知", "まとめて許可") { requestRuntimePerms() }
        actionRow(permContent, "通知へのアクセス", "音楽操作・ハンズフリー起動用") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        actionRow(permContent, "正確なアラーム", "端末により表示されないことがあります") {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "この端末では自動で開けませんでした", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "この端末では追加の許可は不要です", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow(permContent, "電池の最適化から除外", "強く推奨") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        actionRow(permContent, "画面の明るさ操作", "音声アシスタント用・任意") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        actionRow(permContent, "おやすみモードの操作", "音声アシスタント用・任意", showChevron = true) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        note(permContent, "APKを直接インストールした場合、上のボタンが反応しないことがあります。その時は「設定 > アプリ > ${getString(R.string.app_name)} > 右上の︙ > 制限付き設定を許可」を先に行ってください。")

        // --- ② 勉強の声かけ ---
        val nudgeContent = card(root, R.drawable.ic_sparkle, MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer, "勉強の声かけ", "AI（Gemini）が状況を見て声をかけます")
        apiKeyEdit = textField(nudgeContent, "Gemini APIキー", prefs.apiKey)
        modelEdit = textField(nudgeContent, "モデル名", prefs.model)
        intervalEdit = textField(nudgeContent, "声かけの間隔（分）", prefs.intervalMin.toString(), numeric = true)
        bedtimeEdit = textField(nudgeContent, "就寝時刻（時, 0〜23）", prefs.bedtimeHour.toString(), numeric = true)
        earlyEdit = textField(nudgeContent, "早朝の基準（時, 0〜23）", prefs.earlyHour.toString(), numeric = true)
        note(nudgeContent, "就寝時刻を過ぎての使用には「夜更かしですか」、早朝の基準より前に使い始めると「おはようございます」というメッセージになります。")
        filledButton(nudgeContent, "この設定を保存") { saveSettings() }

        // --- パソコンのローカルAI中継 ---
        val relayContent = card(root, R.drawable.ic_pc, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer, "パソコンのローカルAI", "Geminiの無料枠を節約したい時に")
        note(relayContent, "パソコンのブラウザで「Nudgeローカルai中継」のページを開いたままにしておくと、勉強の声かけは、まずこちらに問い合わせるようになります。パソコンが繋がらない時は、自動的にGeminiに切り替わります。")
        relayEnabledSwitch = switchRow(relayContent, "パソコンのAIを使う", initial = relayPrefs.enabled)
        relayDbUrlEdit = textField(relayContent, "FirebaseのデータベースURL", relayPrefs.dbUrl)
        relaySecretEdit = textField(relayContent, "合言葉（パソコン側と同じもの）", relayPrefs.secret)
        filledButton(relayContent, "この設定を保存") { saveRelaySettings() }
        relayStatusView = TextView(this)
        relayStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        relayStatusView.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        relayStatusView.setPadding(dp(4f), dp(10f), dp(4f), 0)
        relayContent.addView(relayStatusView)
        outlinedButton(relayContent, "接続をテストする") { testRelay() }.also {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f))
            lp.topMargin = dp(6f)
            relayContent.addView(it, lp)
        }

        // --- ③ 登録した場所 ---
        val placesContent = card(root, R.drawable.ic_pin, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer, "登録した場所", "タップして地図でピンを置く")
        for ((key, name) in PlaceKeys.ALL) {
            val row = actionRow(placesContent, name, "未設定", showChevron = false) {
                val i = Intent(this, MapPickerActivity::class.java)
                i.putExtra("key", key)
                i.putExtra("label", name)
                startActivity(i)
            }
            val subTv = (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
            placeRows[key] = subTv
            val delBtn = outlinedButton(row, "消去") {
                prefs.clearPlace(key)
                refresh()
            }
            row.addView(delBtn)
        }

        // --- ④ 見守りの実行 ---
        val runContent = card(root, R.drawable.ic_sparkle, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer, "見守りの実行", "②の声かけと⑤のしつこい通知がまとめて動きます")
        toggleSwitch = switchRow(runContent, "見守りを開始する", initial = NudgeService.running)
        toggleSwitch.setOnCheckedChangeListener { _, isChecked -> onWatchSwitchChanged(isChecked) }
        filledButton(runContent, "今すぐテスト表示") { testNow() }

        // --- ⑤ しつこい通知（在宅学習） ---
        val reminderContent = card(root, R.drawable.ic_bell, MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer, "しつこい通知", "在宅学習の催促")
        note(reminderContent, "設定した時間帯に「自宅」（③のピン）にいると声をかけ、対象アプリを開くまで数分おきに催促します。在宅の判定は③の「自宅」ピンを使います。")
        reminderEnabledSwitch = switchRow(reminderContent, "しつこい通知を有効にする", initial = reminderPrefs.enabled)
        reminderTargetView = TextView(this)
        reminderTargetView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        reminderTargetView.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        reminderTargetView.setPadding(dp(4f), dp(4f), dp(4f), 0)
        reminderContent.addView(reminderTargetView)
        outlinedButton(reminderContent, "対象アプリを選ぶ") {
            appPickerLauncher.launch(Intent(this, AppPickerActivity::class.java))
        }.also {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f))
            lp.topMargin = dp(6f)
            reminderContent.addView(it, lp)
        }
        val timeRow = LinearLayout(this)
        timeRow.orientation = LinearLayout.HORIZONTAL
        timeRow.setPadding(0, dp(6f), 0, 0)
        val startCol = LinearLayout(this)
        startCol.orientation = LinearLayout.VERTICAL
        reminderStartEdit = textField(startCol, "開始時刻（時）", (reminderPrefs.windowStartMinutes / 60).toString(), numeric = true)
        timeRow.addView(startCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val spacer = android.view.View(this)
        timeRow.addView(spacer, LinearLayout.LayoutParams(dp(12f), 0))
        val endCol = LinearLayout(this)
        endCol.orientation = LinearLayout.VERTICAL
        reminderEndEdit = textField(endCol, "終了時刻（時）", (reminderPrefs.windowEndMinutes / 60).toString(), numeric = true)
        timeRow.addView(endCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        reminderContent.addView(timeRow)
        filledButton(reminderContent, "この設定を保存") { saveReminderSettings() }
        reminderStatusView = TextView(this)
        reminderStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        reminderStatusView.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        reminderStatusView.setPadding(dp(4f), dp(10f), dp(4f), 0)
        reminderContent.addView(reminderStatusView)
        outlinedButton(reminderContent, "状態を確認する") { showReminderStatus() }.also {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f))
            lp.topMargin = dp(6f)
            reminderContent.addView(it, lp)
        }
        note(reminderContent, "「今日はもう休む」を選んだ日は、その日はもう催促しません。日付が変わると自動的にリセットされます。")

        // --- ⑥ 音声アシスタント ---
        val voiceContent = card(root, R.drawable.ic_mic, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer, "音声アシスタント", "「ヘイ、デイリー」で呼びかけ")
        note(
            voiceContent,
            "タイマー・アラームはAndroid標準の時計アプリにセットします。カレンダーへの予定追加（Googleカレンダーと同期）、" +
                "メールの作成画面を開く、Google Homeアプリを開く、ライト・音量などもハンズフリーで操作できます。" +
                "呼びかけの言葉はモデルに固定されており、アプリ名を変えても変更できません。"
        )
        voiceEnabledSwitch = switchRow(voiceContent, "音声アシスタントを有効にする", initial = VoiceListenerService.running)
        voiceEnabledSwitch.setOnCheckedChangeListener { _, isChecked -> onVoiceSwitchChanged(isChecked) }

        voiceApiKeyEdit = textField(voiceContent, "Gemini APIキー（空欄なら②と共通）", voicePrefs.apiKey)
        voiceModelEdit = textField(voiceContent, "モデル名", voicePrefs.model)
        voiceSpeakSwitch = switchRow(voiceContent, "声で読み上げる", initial = voicePrefs.speak)
        voiceBrainDropdown = dropdownField(voiceContent, "頭脳の選び方", brainOptions, voicePrefs.brain)
        voiceCallNameDropdown = dropdownField(voiceContent, "呼び方", callNameOptions, voicePrefs.callName)
        voiceToneDropdown = dropdownField(voiceContent, "話し方", toneOptions, voicePrefs.tone)
        voiceMusicAppEdit = textField(voiceContent, "音楽アプリ名（例: Spotify）", voicePrefs.musicApp)
        note(voiceContent, "Spotifyを使う場合のみ、下2つを入力すると曲の再生精度が上がります（任意・Spotify for Developersで無料取得）")
        voiceSpotifyIdEdit = textField(voiceContent, "Spotify Client ID（任意）", voicePrefs.spotifyClientId)
        voiceSpotifySecretEdit = textField(voiceContent, "Spotify Client Secret（任意）", voicePrefs.spotifyClientSecret)
        filledButton(voiceContent, "この設定を保存") { saveVoiceSettings() }

        val modelHeader = TextView(this)
        modelHeader.text = "端末内AI（オフラインモデル）"
        modelHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        modelHeader.setTypeface(modelHeader.typeface, Typeface.BOLD)
        modelHeader.setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        modelHeader.setPadding(dp(4f), dp(18f), dp(4f), dp(2f))
        voiceContent.addView(modelHeader)
        voiceModelStatusView = TextView(this)
        voiceModelStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        voiceModelStatusView.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        voiceModelStatusView.setPadding(dp(4f), 0, dp(4f), 0)
        voiceContent.addView(voiceModelStatusView)
        val modelRow = LinearLayout(this)
        modelRow.orientation = LinearLayout.HORIZONTAL
        modelRow.setPadding(0, dp(8f), 0, 0)
        val dlBtn = outlinedButton(modelRow, "ダウンロード（約2.1GB）") {
            LocalModel.start(this)
            Toast.makeText(this, "通知バーで進み具合を確認できます", Toast.LENGTH_LONG).show()
            handler.postDelayed({ refresh() }, 1500L)
        }
        modelRow.addView(dlBtn, LinearLayout.LayoutParams(0, dp(44f), 1f))
        val spacer2 = android.view.View(this)
        modelRow.addView(spacer2, LinearLayout.LayoutParams(dp(10f), 0))
        val delModelBtn = outlinedButton(modelRow, "削除") {
            LocalModel.deleteAll(this)
            refresh()
        }
        modelRow.addView(delModelBtn, LinearLayout.LayoutParams(0, dp(44f), 1f))
        voiceContent.addView(modelRow)
        note(voiceContent, "端末内AIはビルドが失敗しやすい部分です。エラーになる場合は build.gradle.kts の llama-android の行を削除して再ビルドしてください（クラウド版のみで動きます）。")

        voiceStatusView = TextView(this)
        voiceStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        voiceStatusView.setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        voiceStatusView.setPadding(dp(4f), dp(14f), dp(4f), 0)
        voiceContent.addView(voiceStatusView)
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
        list.add(Manifest.permission.WRITE_CALENDAR)
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

    private fun saveRelaySettings() {
        relayPrefs.enabled = relayEnabledSwitch.isChecked
        relayPrefs.dbUrl = relayDbUrlEdit.text.toString().trim()
        relayPrefs.secret = relaySecretEdit.text.toString().trim()
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
    }

    private fun testRelay() {
        saveRelaySettings()
        val dbUrl = relayDbUrlEdit.text.toString().trim()
        val secret = relaySecretEdit.text.toString().trim()
        if (dbUrl.isBlank() || secret.isBlank()) {
            relayStatusView.text = "データベースURLと合言葉の両方を入力してください。"
            return
        }
        relayStatusView.text = "接続テスト中…（パソコン側でページを開いておいてください）"
        Thread {
            try {
                val reply = RelayClient.ask(dbUrl, secret, null, "これは接続テストです。「テスト成功」とだけ返してください。", timeoutMs = 30_000L)
                handler.post { relayStatusView.text = "成功しました: " + reply.take(80) }
            } catch (e: Exception) {
                handler.post { relayStatusView.text = "失敗しました: " + e.message }
            }
        }.start()
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
        voicePrefs.brain = dropdownValue(voiceBrainDropdown, brainOptions)
        voicePrefs.callName = dropdownValue(voiceCallNameDropdown, callNameOptions)
        voicePrefs.tone = dropdownValue(voiceToneDropdown, toneOptions)
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

    private fun onWatchSwitchChanged(wantOn: Boolean) {
        if (wantOn == NudgeService.running) return
        saveSettings()
        saveReminderSettings()
        if (wantOn) {
            if (!canStart()) {
                toggleSwitch.isChecked = false
                return
            }
            prefs.wasRunning = true
            ContextCompat.startForegroundService(this, Intent(this, NudgeService::class.java))
        } else {
            prefs.wasRunning = false
            stopService(Intent(this, NudgeService::class.java))
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
        line(Perm.hasCalendar(this), "カレンダー（読み取り）")
        line(Perm.hasCalendarWrite(this), "カレンダー（予定の追加）")
        line(Perm.hasMic(this), "マイク")
        line(Perm.hasNotificationListener(this), "通知へのアクセス")
        line(Perm.hasExactAlarm(this), "正確なアラーム")
        line(Perm.isIgnoringBatteryOptimizations(this), "電池の最適化から除外")
        line(prefs.apiKey.isNotBlank(), "Gemini APIキー（勉強の声かけ）")
        line(relayPrefs.isConfigured(), "パソコンのローカルAI（設定済み・任意）")
        line(NudgeService.running, if (NudgeService.running) "見守り中" else "停止中")
        val err = prefs.lastError
        if (err.isNotEmpty()) sb.append("\n直近のエラー: ").append(err)
        statusView.text = sb.toString().trim()

        for ((key, name) in PlaceKeys.ALL) {
            val p = prefs.getPlace(key)
            placeRows[key]?.text = if (p == null) {
                "未設定"
            } else {
                String.format(Locale.US, "%.4f, %.4f（半径%dm）", p.lat, p.lng, p.radius)
            }
        }
        if (::toggleSwitch.isInitialized) toggleSwitch.isChecked = NudgeService.running

        // ⑤ しつこい通知
        val label = reminderPrefs.targetAppLabel
        reminderTargetView.text = "対象アプリ: ${label ?: "未設定"}"
        val rsb = StringBuilder()
        rsb.append(if (reminderPrefs.restedToday) "今日: 休み中\n" else "今日: 通常\n")
        rsb.append("警告レベル: ${reminderPrefs.warningCount}\n")
        if (reminderPrefs.studying) rsb.append("現在: 対象アプリを使用中\n")
        reminderStatusView.text = rsb.toString().trim()

        // ⑥ 音声アシスタント
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
        vsb.append(if (VoiceListenerService.running) "動作中\n" else "停止中\n")
        if (!Perm.hasMic(this)) vsb.append("※マイクの許可が必要です\n")
        if (!Perm.hasNotificationListener(this)) vsb.append("※音楽操作・ハンズフリー起動には「通知へのアクセス」が必要です\n")
        voiceStatusView.text = vsb.toString().trim()
    }
}
