package org.javaopen.edeck

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var videos: LinearLayout
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var refreshVideos: (() -> List<RemoteVideo>)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val padding = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 30f })
        root.addView(TextView(this).apply {
            text = getString(R.string.home_description)
            textSize = 16f
            setPadding(0, padding / 3, 0, padding / 3)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val upnpButton = Button(this).apply {
            text = getString(R.string.action_search_upnp)
            setOnClickListener { discoverUpnpServers() }
        }
        styleRemoteButton(upnpButton)
        actions.addView(upnpButton)
        val smbButton = Button(this).apply {
            text = getString(R.string.action_open_smb)
            setOnClickListener { showSmbDialog() }
        }
        styleRemoteButton(smbButton)
        actions.addView(smbButton)
        val refreshButton = Button(this).apply {
            text = getString(R.string.action_refresh)
            setOnClickListener { refreshSource() }
        }
        styleRemoteButton(refreshButton)
        actions.addView(refreshButton)
        root.addView(actions)

        status = TextView(this).apply {
            text = getString(R.string.status_choose_source)
            textSize = 16f
            setPadding(0, padding / 3, 0, padding / 3)
        }
        root.addView(status)
        videos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(videos) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        upnpButton.requestFocus()
    }

    private fun discoverUpnpServers() {
        status.text = getString(R.string.status_searching_upnp)
        executor.execute {
            val result = runCatching { UpnpRepository.discover(this) }
            runOnUiThread {
                result.onSuccess { servers ->
                    if (servers.isEmpty()) {
                        status.text = getString(R.string.status_no_upnp)
                    } else {
                        status.text = getString(R.string.status_upnp_found, servers.size)
                        val names = servers.map { it.name }.toTypedArray()
                        android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.title_upnp_servers)
                            .setItems(names) { _, index ->
                                val server = servers[index]
                                loadVideos(getString(R.string.status_searching_server, server.name)) { UpnpRepository.listVideos(server) }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }.onFailure { status.text = getString(R.string.error_upnp_search, it.localizedMessage ?: getString(R.string.network_error)) }
            }
        }
    }

    private fun showSmbDialog() {
        val padding = (resources.displayMetrics.density * 20).toInt()
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            hint = hintText
            inputType = type
            setSingleLine(true)
            textSize = 18f
            fields.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val address = field("smb://192.168.1.10/Videos", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val domain = field(getString(R.string.hint_domain))
        val username = field(getString(R.string.hint_username))
        val password = field(getString(R.string.hint_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        SmbCredentialsStore.lastSource(this)?.let { address.setText(it) }
        var loadedFor = address.text.toString()
        fun loadCredentialsForAddress() {
            val currentAddress = address.text.toString().trim()
            if (currentAddress == loadedFor) return
            loadedFor = currentAddress
            val saved = SmbCredentialsStore.credentialsFor(this, currentAddress)
            domain.setText(saved?.domain.orEmpty())
            username.setText(saved?.username.orEmpty())
            password.setText(saved?.password.orEmpty())
        }
        address.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) loadCredentialsForAddress()
        }
        address.setOnEditorActionListener { _, _, _ ->
            loadCredentialsForAddress()
            false
        }
        SmbCredentialsStore.credentialsFor(this, loadedFor)?.let { saved ->
            domain.setText(saved.domain)
            username.setText(saved.username)
            password.setText(saved.password)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.title_connect_smb)
            .setMessage(R.string.message_connect_smb)
            .setView(fields)
            .setPositiveButton(R.string.connect) { _, _ ->
                val path = address.text.toString().trim()
                if (path.isBlank()) {
                    status.text = getString(R.string.error_smb_address_required)
                } else {
                    val authDomain = domain.text.toString()
                    val user = username.text.toString()
                    val secret = password.text.toString()
                    loadVideos(getString(R.string.status_searching_smb), onSuccess = {
                        SmbCredentialsStore.remember(this, path, SmbCredentials(authDomain, user, secret))
                    }) {
                        SmbRepository.scanVideos(path, authDomain, user, secret)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        address.requestFocus()
    }

    private fun loadVideos(
        loadingMessage: String,
        onSuccess: (() -> Unit)? = null,
        loader: () -> List<RemoteVideo>
    ) {
        refreshVideos = loader
        status.text = loadingMessage
        videos.removeAllViews()
        executor.execute {
            val result = runCatching(loader)
            runOnUiThread {
                result.onSuccess { items ->
                    onSuccess?.invoke()
                    showVideos(items)
                }.onFailure { status.text = getString(R.string.error_load, it.localizedMessage ?: getString(R.string.network_error)) }
            }
        }
    }

    private fun showVideos(items: List<RemoteVideo>) {
        videos.removeAllViews()
        if (items.isEmpty()) {
            status.text = getString(R.string.status_no_videos)
            return
        }
        status.text = getString(R.string.status_videos_found, items.size)
        items.forEach { item ->
            val watched = preferences.getBoolean("watched:${item.uri}", false)
            val button = Button(this).apply {
                text = (if (watched) "✓  " else "▶  ") + item.title
                textSize = 18f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URI, item.uri.toString())
                        putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                    })
                }
            }
            styleRemoteButton(button)
            videos.addView(button)
        }
    }

    private fun refreshSource() {
        val loader = refreshVideos
        if (loader == null) {
            status.text = getString(R.string.status_open_source_first)
        } else {
            loadVideos(getString(R.string.status_refreshing), loader = loader)
        }
    }

    private fun styleRemoteButton(button: Button) {
        button.setOnFocusChangeListener { view, focused ->
            view.backgroundTintList = ColorStateList.valueOf(
                if (focused) android.graphics.Color.rgb(21, 101, 192)
                else android.graphics.Color.rgb(80, 80, 80)
            )
            view.scaleX = if (focused) 1.04f else 1f
            view.scaleY = if (focused) 1.04f else 1f
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> return moveFocus(View.FOCUS_UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> return moveFocus(View.FOCUS_DOWN)
                KeyEvent.KEYCODE_DPAD_LEFT -> return moveFocus(View.FOCUS_LEFT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return moveFocus(View.FOCUS_RIGHT)
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0 && window.currentFocus?.performClick() == true) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveFocus(direction: Int): Boolean {
        val current = window.currentFocus ?: return false
        return current.focusSearch(direction)?.requestFocus() ?: false
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
