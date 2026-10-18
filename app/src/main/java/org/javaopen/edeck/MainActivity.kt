package org.javaopen.edeck

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.graphics.Rect
import android.text.InputType
import android.text.method.TransformationMethod
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
    private lateinit var refreshButton: Button
    private lateinit var settingsButton: Button
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var refreshVideos: (() -> List<RemoteVideo>)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        restoreRecentSource()
    }

    private fun buildScreen() {
        val padding = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, padding, 0)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.rgb(32, 36, 42))
        }
        menu.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setPadding(0, 0, 0, padding)
        })
        refreshButton = Button(this).apply {
            text = getString(R.string.action_refresh)
            contentDescription = getString(R.string.action_refresh)
            setOnClickListener { refreshSource() }
        }
        styleRemoteButton(refreshButton)
        menu.addView(refreshButton, menuButtonParams())
        settingsButton = Button(this).apply {
            text = getString(R.string.action_settings)
            contentDescription = getString(R.string.action_settings)
            setOnClickListener { showSettingsDialog() }
        }
        styleRemoteButton(settingsButton)
        menu.addView(settingsButton, menuButtonParams())
        root.addView(menu, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, 0, 0)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.home_description)
            textSize = 18f
            setPadding(0, 0, 0, padding / 3)
        })
        status = TextView(this).apply {
            text = getString(R.string.status_choose_source)
            textSize = 16f
            setPadding(0, padding / 3, 0, padding / 3)
        }
        content.addView(status)
        videos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(videos) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
        refreshButton.requestFocus()
    }

    private fun menuButtonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private fun showSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.action_settings)
            .setItems(arrayOf(getString(R.string.settings_upnp), getString(R.string.settings_smb))) { _, choice ->
                when (choice) {
                    0 -> discoverUpnpServers()
                    1 -> showSmbDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                                loadVideos(
                                    getString(R.string.status_searching_server, server.name),
                                    onSuccess = { items ->
                                        RecentSourceStore.saveUpnp(this, server)
                                        VideoCacheStore.save(this, RecentSource.Upnp(
                                            server.name, server.contentDirectoryUrl, server.serviceType
                                        ).cacheKey, items)
                                    }
                                ) {
                                    UpnpRepository.listVideos(server)
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }.onFailure {
                    status.text = getString(R.string.error_upnp_search, it.localizedMessage ?: getString(R.string.network_error))
                }
            }
        }
    }

    private fun showSmbDialog() {
        val padding = dp(20)
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
        val address = field(getString(R.string.hint_smb_uri), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val domain = field(getString(R.string.hint_domain))
        val username = field(getString(R.string.hint_username))
        val password = field(getString(R.string.hint_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fields.removeView(password)
        val passwordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        password.transformationMethod = AsteriskPasswordTransformationMethod
        passwordRow.addView(password, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val passwordVisibility = Button(this).apply {
            text = getString(R.string.password_show)
            contentDescription = getString(R.string.password_show)
            setOnClickListener {
                val visible = password.transformationMethod == AsteriskPasswordTransformationMethod
                password.transformationMethod = if (visible) null else AsteriskPasswordTransformationMethod
                val action = if (visible) R.string.password_hide else R.string.password_show
                text = getString(action)
                contentDescription = getString(action)
                password.setSelection(password.text?.length ?: 0)
            }
        }
        styleRemoteButton(passwordVisibility)
        passwordRow.addView(passwordVisibility, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        fields.addView(passwordRow)

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
                    val source = RecentSource.Smb(path)
                    loadVideos(getString(R.string.status_searching_smb), onSuccess = { items ->
                        SmbCredentialsStore.remember(this, path, SmbCredentials(authDomain, user, secret))
                        RecentSourceStore.saveSmb(this, path)
                        VideoCacheStore.save(this, source.cacheKey, items)
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
        onSuccess: ((List<RemoteVideo>) -> Unit)? = null,
        loader: () -> List<RemoteVideo>
    ) {
        val cachingLoader: () -> List<RemoteVideo> = {
            loader().also { items -> runCatching { onSuccess?.invoke(items) } }
        }
        refreshVideos = cachingLoader
        status.text = loadingMessage
        videos.removeAllViews()
        executor.execute {
            val result = runCatching(cachingLoader)
            runOnUiThread {
                result.onSuccess(::showVideos).onFailure {
                    status.text = getString(R.string.error_load, it.localizedMessage ?: getString(R.string.network_error))
                }
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

    private fun restoreRecentSource() {
        val source = RecentSourceStore.load(this) ?: return
        val loader: (() -> List<RemoteVideo>)? = when (source) {
            is RecentSource.Smb -> {
                val credentials = SmbCredentialsStore.credentialsFor(this, source.address)
                if (credentials == null) null else {
                    smbVideoLoader(source.address, credentials)
                }
            }
            is RecentSource.Upnp -> {
                val server = UpnpMediaServer(
                    source.name,
                    source.contentDirectoryUrl,
                    source.contentDirectoryUrl,
                    source.serviceType
                )
                upnpVideoLoader(server)
            }
        }
        refreshVideos = loader?.let { sourceLoader ->
            {
                sourceLoader().also { items ->
                    runCatching { VideoCacheStore.save(this, source.cacheKey, items) }
                }
            }
        }
        status.text = getString(R.string.status_cache_loading)
        executor.execute {
            val cachedVideos = runCatching { VideoCacheStore.load(this, source.cacheKey) }.getOrNull()
            runOnUiThread {
                if (cachedVideos != null) {
                    showVideos(cachedVideos)
                } else if (loader == null) {
                    status.text = getString(R.string.error_restore_source)
                } else {
                    status.text = getString(R.string.status_cache_empty)
                }
            }
        }
    }

    private fun smbVideoLoader(address: String, credentials: SmbCredentials): () -> List<RemoteVideo> = {
        SmbRepository.scanVideos(address, credentials.domain, credentials.username, credentials.password)
    }

    private fun upnpVideoLoader(server: UpnpMediaServer): () -> List<RemoteVideo> = {
        UpnpRepository.listVideos(server)
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

private object AsteriskPasswordTransformationMethod : TransformationMethod {
    override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
        override val length: Int get() = source.length
        override fun get(index: Int): Char = if (index in 0 until length) '*' else throw IndexOutOfBoundsException()
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= length)
            return "*".repeat(endIndex - startIndex)
        }
        override fun toString(): String = "*".repeat(length)
    }

    override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) = Unit
}
