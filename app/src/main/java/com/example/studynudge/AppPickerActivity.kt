package com.example.studynudge

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AppPickerActivity : AppCompatActivity() {

    private data class AppEntry(val label: String, val pkg: String, val icon: Drawable?)

    private lateinit var listContainer: LinearLayout
    private var allApps: List<AppEntry> = emptyList()

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(32f), dp(16f), dp(16f))

        val title = TextView(this)
        title.text = "対象のアプリを選ぶ"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        root.addView(title)

        val search = EditText(this)
        search.hint = "アプリ名で絞り込み"
        root.addView(search)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this)
        listContainer.orientation = LinearLayout.VERTICAL
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        loadApps()
        render(search.text.toString())

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                render(s.toString())
            }
        })
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            emptyList()
        }
        allApps = resolved.mapNotNull { ri ->
            try {
                AppEntry(
                    ri.loadLabel(pm).toString(),
                    ri.activityInfo.packageName,
                    ri.loadIcon(pm)
                )
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.pkg }.sortedBy { it.label }
    }

    private fun render(filter: String) {
        listContainer.removeAllViews()
        val f = filter.trim()
        val shown = if (f.isEmpty()) allApps else allApps.filter { it.label.contains(f, ignoreCase = true) }
        for (app in shown.take(200)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))

            val icon = ImageView(this)
            icon.setImageDrawable(app.icon)
            row.addView(icon, LinearLayout.LayoutParams(dp(36f), dp(36f)))

            val label = TextView(this)
            label.text = app.label
            label.setPadding(dp(12f), 0, 0, 0)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener {
                val data = Intent()
                data.putExtra("package", app.pkg)
                data.putExtra("label", app.label)
                setResult(RESULT_OK, data)
                finish()
            }
            listContainer.addView(row)
        }
    }
}
