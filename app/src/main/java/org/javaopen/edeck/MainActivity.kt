package org.javaopen.edeck

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.graphics.Rect
import android.text.InputType
import android.text.method.TransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var videos: ListView
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var browserList: ListView
    private lateinit var browserAdapter: BrowserAdapter
    private lateinit var browserUpButton: MaterialButton
    private lateinit var videoListHeader: View
    private lateinit var listButton: MaterialButton
    private lateinit var browseButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var startupBanner: View
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var refreshVideos: (() -> List<RemoteVideo>)? = null
    private var currentVideos: List<RemoteVideo> = emptyList()
    private var browserSource: RecentSource? = null
    private val browserPath = mutableListOf<String>()
    private var startupRestoreLoading = false
    private val showStartupBannerRunnable = Runnable {
        if (startupRestoreLoading) startupBanner.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        restoreRecentSource()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::videoAdapter.isInitialized) videoAdapter.refreshMarks()
        if (::browserAdapter.isInitialized) browserAdapter.refreshMarks()
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun buildScreen() {
        val padding = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.edeck_background))
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setBackgroundColor(getColor(R.color.edeck_surface_container))
        }
        menu.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(getColor(R.color.edeck_primary))
            setPadding(dp(12), 0, 0, dp(24))
        })
        refreshButton = MaterialButton(this).apply {
            text = ""
            contentDescription = getString(R.string.action_refresh)
            icon = getDrawable(R.drawable.ic_refresh_24)
            iconSize = dp(24)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
            setOnClickListener { refreshSource() }
        }
        styleRemoteButton(refreshButton)
        listButton = MaterialButton(this).apply {
            text = getString(R.string.action_video_list)
            contentDescription = text
            icon = getDrawable(R.drawable.ic_video_list_24)
            iconSize = dp(22)
            setOnClickListener { showVideoList() }
        }
        styleRemoteButton(listButton)
        menu.addView(listButton, menuButtonParams())
        browseButton = MaterialButton(this).apply {
            text = getString(R.string.action_browse)
            contentDescription = text
            icon = getDrawable(R.drawable.ic_folder_open_24)
            iconSize = dp(22)
            setOnClickListener { openBrowser() }
        }
        styleRemoteButton(browseButton)
        settingsButton = MaterialButton(this).apply {
            text = getString(R.string.action_settings)
            contentDescription = getString(R.string.action_settings)
            icon = getDrawable(R.drawable.ic_settings_24)
            iconSize = dp(22)
            setOnClickListener { showSettingsDialog() }
        }
        styleRemoteButton(settingsButton)
        menu.addView(browseButton, menuButtonParams())
        menu.addView(settingsButton, menuButtonParams())
        root.addView(menu, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, 0, 0)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.home_description)
            textSize = 15f
            setTextColor(getColor(R.color.edeck_on_surface_variant))
            setPadding(dp(8), dp(8), 0, dp(8))
        })
        status = TextView(this).apply {
            text = getString(R.string.status_choose_source)
            textSize = 16f
            setTextColor(getColor(R.color.edeck_on_surface))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val statusCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.edeck_surface_container_high))
            addView(status)
        }
        content.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        browserUpButton = MaterialButton(this).apply {
            text = getString(R.string.action_parent_directory)
            icon = getDrawable(R.drawable.ic_arrow_up_24)
            iconSize = dp(20)
            setOnClickListener { navigateBrowserUp() }
            visibility = View.GONE
        }
        styleRemoteButton(browserUpButton)
        content.addView(browserUpButton, menuButtonParams())
        videoAdapter = VideoAdapter()
        videos = ListView(this).apply {
            adapter = videoAdapter
            isFocusable = true
            isFocusableInTouchMode = true
            itemsCanFocus = false
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            dividerHeight = dp(8)
            setOnItemClickListener { _, _, position, _ -> playVideo(videoAdapter.getItem(position)) }
        }
        videoListHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(refreshButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        }
        content.addView(videoListHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(videos, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        browserAdapter = BrowserAdapter()
        browserList = ListView(this).apply {
            adapter = browserAdapter
            isFocusable = true
            isFocusableInTouchMode = true
            itemsCanFocus = false
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            dividerHeight = dp(8)
            visibility = View.GONE
            setOnItemClickListener { _, _, position, _ ->
                val item = browserAdapter.getItem(position)
                when {
                    item.directoryId != null -> {
                        browserPath += item.directoryId
                        loadBrowserDirectory(item.directoryId)
                    }
                    item.video != null -> playVideo(item.video)
                }
            }
        }
        content.addView(browserList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        startupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding * 2, padding * 2, padding * 2, padding * 2)
            setBackgroundColor(getColor(R.color.edeck_background))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 42f
                setTextColor(getColor(R.color.edeck_primary))
                gravity = Gravity.CENTER
            })
            addView(ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                topMargin = padding
                bottomMargin = padding
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.status_cache_loading)
                textSize = 18f
                setTextColor(getColor(R.color.edeck_on_surface))
                gravity = Gravity.CENTER
            })
        }
        setContentView(FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(startupBanner, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })
        refreshButton.requestFocus()
    }

    private fun beginStartupRestore() {
        startupRestoreLoading = true
        startupBanner.removeCallbacks(showStartupBannerRunnable)
        startupBanner.postDelayed(showStartupBannerRunnable, STARTUP_BANNER_DELAY_MS)
    }

    private fun finishStartupRestore() {
        startupRestoreLoading = false
        startupBanner.removeCallbacks(showStartupBannerRunnable)
        startupBanner.visibility = View.GONE
    }

    private fun menuButtonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_settings)
            .setItems(arrayOf(
                getString(R.string.settings_saved_sources),
                getString(R.string.settings_upnp),
                getString(R.string.settings_smb),
                getString(R.string.settings_video_sort_order)
            )) { _, choice ->
                when (choice) {
                    0 -> showSavedSourcesDialog()
                    1 -> discoverUpnpServers()
                    2 -> showSmbDialog()
                    3 -> showVideoSortOrderDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showVideoSortOrderDialog() {
        val sortOrders = VideoSortOrder.values()
        val selectedSortOrder = selectedVideoSortOrder()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_video_sort_order)
            .setSingleChoiceItems(
                sortOrders.map { getString(it.labelResource) }.toTypedArray(),
                sortOrders.indexOf(selectedSortOrder)
            ) { dialog, index ->
                preferences.edit().putString(VIDEO_SORT_ORDER_KEY, sortOrders[index].name).apply()
                if (currentVideos.isNotEmpty()) showVideos(currentVideos)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectedVideoSortOrder(): VideoSortOrder = runCatching {
        VideoSortOrder.valueOf(preferences.getString(VIDEO_SORT_ORDER_KEY, null) ?: "")
    }.getOrDefault(VideoSortOrder.MODIFIED_DESC)

    private fun showSavedSourcesDialog() {
        val sources = RecentSourceStore.sources(this)
        if (sources.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_saved_sources)
                .setMessage(R.string.status_no_saved_sources)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_saved_sources)
            .setItems(sources.map(RecentSource::label).toTypedArray()) { _, index ->
                activateSource(sources[index])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showVideoList() {
        browserList.visibility = View.GONE
        browserUpButton.visibility = View.GONE
        videoListHeader.visibility = View.VISIBLE
        refreshButton.visibility = View.VISIBLE
        videos.visibility = View.VISIBLE
        status.text = getString(R.string.status_videos_found, currentVideos.size)
        videos.requestFocus()
    }

    private fun openBrowser() {
        val source = RecentSourceStore.load(this)
        if (source == null) {
            status.text = getString(R.string.status_open_source_first)
            return
        }
        browserSource = source
        browserPath.clear()
        val root = when (source) {
            is RecentSource.Smb -> source.address.let { if (it.endsWith('/')) it else "$it/" }
            is RecentSource.Upnp -> "0"
        }
        browserPath += root
        videos.visibility = View.GONE
        videoListHeader.visibility = View.GONE
        refreshButton.visibility = View.GONE
        browserList.visibility = View.VISIBLE
        browserUpButton.visibility = View.VISIBLE
        browserUpButton.isEnabled = false
        loadBrowserDirectory(root)
    }

    private fun navigateBrowserUp() {
        if (browserPath.size <= 1) return
        browserPath.removeAt(browserPath.lastIndex)
        browserUpButton.isEnabled = browserPath.size > 1
        loadBrowserDirectory(browserPath.last())
    }

    private fun loadBrowserDirectory(directoryId: String) {
        val source = browserSource ?: return
        browserUpButton.isEnabled = browserPath.size > 1
        status.text = getString(R.string.status_browsing)
        browserAdapter.replaceItems(emptyList())
        browserList.requestFocus()
        executor.execute {
            val result = runCatching {
                when (source) {
                    is RecentSource.Smb -> {
                        val credentials = SmbCredentialsStore.credentialsForResource(this, source.address)
                            ?: error(getString(R.string.error_restore_source))
                        val address = if (directoryId.startsWith("smb://", true)) directoryId else source.address
                        SmbRepository.listDirectory(address, credentials.domain, credentials.username, credentials.password)
                    }
                    is RecentSource.Upnp -> UpnpRepository.browseDirectory(
                        UpnpMediaServer(source.name, source.contentDirectoryUrl, source.contentDirectoryUrl, source.serviceType),
                        directoryId
                    )
                }
            }
            runOnUiThread {
                result.onSuccess { items ->
                    browserAdapter.replaceItems(items)
                    status.text = getString(R.string.status_browser_items, items.size)
                    browserList.requestFocus()
                    if (items.isNotEmpty()) browserList.setSelection(0)
                }.onFailure {
                    status.text = getString(R.string.error_load, it.localizedMessage ?: getString(R.string.network_error))
                    browserList.requestFocus()
                }
            }
        }
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
                        MaterialAlertDialogBuilder(this)
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
        val passwordVisibility = MaterialButton(this).apply {
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
        MaterialAlertDialogBuilder(this)
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
        videoAdapter.replaceItems(emptyList())
        executor.execute {
            val result = runCatching {
                cachingLoader().let { items -> items to sortVideos(items, selectedVideoSortOrder()) }
            }
            runOnUiThread {
                result.onSuccess { (items, sortedItems) -> showVideos(items, sortedItems) }.onFailure {
                    status.text = getString(R.string.error_load, it.localizedMessage ?: getString(R.string.network_error))
                }
            }
        }
    }

    private fun showVideos(items: List<RemoteVideo>) {
        showVideos(items, sortVideos(items, selectedVideoSortOrder()))
    }

    private fun showVideos(items: List<RemoteVideo>, sortedItems: List<RemoteVideo>) {
        currentVideos = items
        if (items.isEmpty()) {
            videoAdapter.replaceItems(emptyList())
            status.text = getString(R.string.status_no_videos)
            return
        }
        status.text = getString(R.string.status_videos_found, items.size)
        videoAdapter.replaceItems(sortedItems)
    }

    private fun sortVideos(items: List<RemoteVideo>, sortOrder: VideoSortOrder): List<RemoteVideo> {
        val fileNameComparator = Comparator<RemoteVideo> { first, second ->
            first.fileNameSortKey().compareTo(second.fileNameSortKey(), ignoreCase = true)
        }
        return when (sortOrder) {
            VideoSortOrder.FILE_NAME_ASC -> items.sortedWith(fileNameComparator)
            VideoSortOrder.FILE_NAME_DESC -> items.sortedWith(fileNameComparator.reversed())
            VideoSortOrder.MODIFIED_ASC -> items.sortedBy(RemoteVideo::lastModified)
            VideoSortOrder.MODIFIED_DESC -> items.sortedByDescending(RemoteVideo::lastModified)
        }
    }

    private fun RemoteVideo.fileNameSortKey(): String =
        uri.lastPathSegment?.takeIf(String::isNotBlank) ?: title

    private enum class VideoSortOrder(val labelResource: Int) {
        FILE_NAME_ASC(R.string.sort_file_name_ascending),
        FILE_NAME_DESC(R.string.sort_file_name_descending),
        MODIFIED_ASC(R.string.sort_modified_ascending),
        MODIFIED_DESC(R.string.sort_modified_descending)
    }

    companion object {
        private const val VIDEO_SORT_ORDER_KEY = "video_sort_order"
        private const val STARTUP_BANNER_DELAY_MS = 250L
    }

    private inner class VideoAdapter : BaseAdapter() {
        private var items: List<RemoteVideo> = emptyList()

        fun replaceItems(newItems: List<RemoteVideo>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun refreshMarks() = notifyDataSetChanged()

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): RemoteVideo = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val row = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = 20f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                minHeight = dp(68)
                isFocusable = false
                isClickable = false
                setTextColor(getColor(R.color.edeck_on_surface))
                setBackgroundResource(R.drawable.list_row_background)
            }
            row.text = videoLabel(item)
            return row
        }
    }

    private inner class BrowserAdapter : BaseAdapter() {
        private var items: List<RemoteBrowserItem> = emptyList()

        fun replaceItems(newItems: List<RemoteBrowserItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun refreshMarks() = notifyDataSetChanged()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RemoteBrowserItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = 20f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                minHeight = dp(68)
                isFocusable = false
                isClickable = false
                setTextColor(getColor(R.color.edeck_on_surface))
                setBackgroundResource(R.drawable.list_row_background)
            }
            val item = getItem(position)
            row.text = item.directoryId?.let { getString(R.string.browser_folder, item.title) }
                ?: item.video?.let(::videoLabel).orEmpty()
            return row
        }
    }

    private fun videoLabel(item: RemoteVideo): String {
        val completed = preferences.getBoolean("completed:${item.uri}", false)
        val position = preferences.getLong("position:${item.uri}", 0L)
        val watched = preferences.getBoolean("watched:${item.uri}", false)
        return when {
            completed -> getString(R.string.video_mark_completed, item.title)
            position > 0L -> getString(R.string.video_mark_position, item.title, formatPosition(position))
            watched -> getString(R.string.video_mark_watched, item.title)
            else -> getString(R.string.video_mark_unwatched, item.title)
        }
    }

    private fun formatPosition(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun playVideo(item: RemoteVideo) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URI, item.uri.toString())
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        })
    }

    private fun restoreRecentSource() {
        RecentSourceStore.load(this)?.let {
            activateSource(it, select = false, isStartupRestore = true)
        }
    }

    private fun activateSource(
        source: RecentSource,
        select: Boolean = true,
        isStartupRestore: Boolean = false
    ) {
        if (select) RecentSourceStore.select(this, source)
        val loader: (() -> List<RemoteVideo>)? = when (source) {
            is RecentSource.Smb -> SmbCredentialsStore.credentialsFor(this, source.address)?.let {
                smbVideoLoader(source.address, it)
            }
            is RecentSource.Upnp -> {
                upnpVideoLoader(UpnpMediaServer(
                    source.name,
                    source.contentDirectoryUrl,
                    source.contentDirectoryUrl,
                    source.serviceType
                ))
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
        videoAdapter.replaceItems(emptyList())
        if (isStartupRestore) beginStartupRestore()
        executor.execute {
            val cachedVideos = runCatching {
                VideoCacheStore.load(this, source.cacheKey)?.let { items ->
                    items to sortVideos(items, selectedVideoSortOrder())
                }
            }.getOrNull()
            runOnUiThread {
                when {
                    cachedVideos != null -> showVideos(cachedVideos.first, cachedVideos.second)
                    loader == null -> status.text = getString(R.string.error_restore_source)
                    else -> status.text = getString(R.string.status_cache_empty)
                }
                if (isStartupRestore) finishStartupRestore()
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

    private fun styleRemoteButton(button: MaterialButton) {
        button.isAllCaps = false
        button.textSize = 16f
        button.cornerRadius = dp(14)
        button.insetTop = dp(3)
        button.insetBottom = dp(3)
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        button.iconPadding = dp(12)
        button.setTextColor(getColorStateList(R.color.edeck_button_text))
        button.iconTint = getColorStateList(R.color.edeck_button_text)
        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.edeck_surface_container_high))
        button.setOnFocusChangeListener { _, focused ->
            button.backgroundTintList = ColorStateList.valueOf(
                if (focused) getColor(R.color.edeck_primary_container)
                else getColor(R.color.edeck_surface_container_high)
            )
            button.setTextColor(getColor(if (focused) R.color.edeck_on_primary_container else R.color.edeck_on_surface))
            button.iconTint = ColorStateList.valueOf(
                getColor(if (focused) R.color.edeck_on_primary_container else R.color.edeck_on_surface)
            )
            button.scaleX = if (focused) 1.03f else 1f
            button.scaleY = if (focused) 1.03f else 1f
        }
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
