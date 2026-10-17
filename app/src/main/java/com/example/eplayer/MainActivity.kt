package com.example.eplayer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var content: TextView
    private lateinit var loadButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val padding = (resources.displayMetrics.density * 32).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            isFocusableInTouchMode = true
        }

        column.addView(TextView(this).apply {
            text = "EPlayer"
            textSize = 28f
        })
        column.addView(TextView(this).apply {
            text = "HTTPS 上のテキストファイルを開く"
            textSize = 16f
            setPadding(0, padding / 3, 0, padding / 3)
        })

        urlInput = EditText(this).apply {
            hint = "https://example.com/file.txt"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 18f
        }
        column.addView(urlInput, matchWidth())

        loadButton = Button(this).apply {
            text = "読み込む"
            setOnClickListener { loadRemoteFile() }
        }
        column.addView(loadButton)
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadRemoteFile()
                true
            } else false
        }

        status = TextView(this).apply {
            text = "URL を入力してください"
            textSize = 16f
            setPadding(0, padding / 3, 0, padding / 3)
        }
        column.addView(status)
        content = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        column.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(column)
        urlInput.requestFocus()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun loadRemoteFile() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("https://", ignoreCase = true)) {
            status.text = "https:// で始まる URL を入力してください"
            return
        }

        loadButton.isEnabled = false
        status.text = "読み込み中…"
        content.text = ""
        executor.execute {
            val result = runCatching { RemoteFileReader.readText(url) }
            runOnUiThread {
                loadButton.isEnabled = true
                result.onSuccess {
                    status.text = "読み込み完了"
                    content.text = it
                }.onFailure {
                    status.text = "読み込みに失敗しました: ${it.localizedMessage ?: "通信エラー"}"
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
