package com.filmcraft.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ScrollView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.widget.SeekBar
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbFilter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Touch-first Android editing surface backed by the FilmCraft project session. */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val background = Color.rgb(17, 18, 20)
    private val surface = Color.rgb(29, 31, 35)
    private val muted = Color.rgb(150, 153, 160)
    private val accent = Color.rgb(167, 240, 74)
    private lateinit var selectionLabel: TextView
    private lateinit var playbackTimeLabel: TextView
    private lateinit var playbackSeek: SeekBar
    private lateinit var videoPreview: PlayerView
    private var previewPlayer: ExoPlayer? = null
    private val imagePreviewExecutor = Executors.newSingleThreadExecutor()
    private var imageLoadGeneration = 0
    private lateinit var imagePreview: ImageView
    private lateinit var emptyPreview: TextView
    private lateinit var videoClipStrip: LinearLayout
    private lateinit var audioClipStrip: LinearLayout
    private val videoClips = mutableListOf<Pair<Uri, String>>()
    private val audioClips = mutableListOf<Pair<Uri, String>>()
    private val timelineClipIds = mutableMapOf<Uri, Long>()
    private val effectCatalog = mutableListOf<JSONObject>()
    private val engineCommandCatalog = mutableListOf<JSONObject>()
    private var activeVideo: Uri? = null
    private var selectedClipUri: Uri? = null
    private var activeTimelineClipId: Long? = null
    private var selectedClipHasVideo = false
    private val livePreviewEffectIds = mutableListOf<String>()
    private var nativeSessionReady = false
    private var dialogReference: android.app.AlertDialog? = null
    private val importExecutor = Executors.newSingleThreadExecutor()

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach(::acceptMedia)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0
        restoreClips()
        setContentView(buildEditor())
        initializePreviewPlayer()
        renderClips()
        initializeRustSession()
    }

    private fun buildEditor(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(label("⌄", 25f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showProjectMenu() }
        }, LinearLayout.LayoutParams(dp(38), dp(48)))
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("New project", 15f, Color.WHITE, true))
            addView(label("Saved on this device", 10f, muted))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("↶", 23f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { runEngineAction("edit.undo", "Undone") }
        }, LinearLayout.LayoutParams(dp(40), dp(44)))
        top.addView(label("↷", 23f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { runEngineAction("edit.redo", "Redone") }
        }, LinearLayout.LayoutParams(dp(40), dp(44)))
        top.addView(label("Export", 13f, background, true).apply {
            gravity = Gravity.CENTER
            background = pill(accent, 18)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { startExport() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(5) })
        root.addView(top)

        val preview = FrameLayout(this).apply {
            background = rounded(Color.rgb(5, 6, 8), 12)
            clipToOutline = true
        }
        videoPreview = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        preview.addView(videoPreview, FrameLayout.LayoutParams(-1, -1))
        imagePreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        preview.addView(imagePreview, FrameLayout.LayoutParams(-1, -1))
        emptyPreview = label("＋\nAdd a video or photo\nYour preview appears here", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { openMediaPicker() }
            background = rounded(Color.rgb(9, 10, 12), 12)
        }
        emptyPreview.maxLines = 3
        preview.addView(emptyPreview, FrameLayout.LayoutParams(-1, -1))
        preview.addView(label("PREVIEW", 9f, Color.WHITE, true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(Color.argb(170, 30, 31, 34), 6)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(8); leftMargin = dp(8)
        })
        preview.addView(label("FIT  ·  720p", 9f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(Color.argb(170, 30, 31, 34), 8)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(8); rightMargin = dp(8)
        })
        preview.setOnClickListener { togglePreviewPlayback() }
        videoPreview.setOnClickListener { togglePreviewPlayback() }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f).apply {
            topMargin = dp(6); bottomMargin = dp(4)
        })

        val playback = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        playback.addView(label("⏮", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { previewPlayer?.seekTo(0) }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        playback.addView(label("▶", 19f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(surface, 22)
            setOnClickListener { togglePreviewPlayback() }
        }, LinearLayout.LayoutParams(dp(44), dp(38)))
        playback.addView(label("⏭", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { previewPlayer?.let { player -> player.seekTo((player.currentPosition + 5_000).coerceAtMost(player.duration.coerceAtLeast(0))) } }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        playbackTimeLabel = label("00:00 / 00:00", 10f, muted).apply { gravity = Gravity.CENTER }
        playback.addView(playbackTimeLabel, LinearLayout.LayoutParams(dp(82), dp(38)))
        playbackSeek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val player = previewPlayer ?: return
                        val duration = player.duration
                        if (duration > 0) player.seekTo(duration * progress / 1000L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        playback.addView(playbackSeek, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(playback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val timelineTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timelineTitle.addView(label("Timeline", 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(34), 1f))
        timelineTitle.addView(label("＋ Add media", 11f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(surface, 16)
            setOnClickListener { openMediaPicker() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        root.addView(timelineTitle)

        val tracks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 12)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        tracks.addView(mediaTrackRow("VIDEO", true))
        tracks.addView(trackRow("OVERLAY", listOf("＋ Add overlay"), listOf(Color.rgb(53, 45, 67))))
        tracks.addView(mediaTrackRow("AUDIO", false))
        root.addView(tracks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.30f))

        selectionLabel = label("Ready · add media to start", 11f, muted).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(selectionLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        root.addView(toolBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        return root
    }

    private fun updatePlaybackPosition() {
        val player = previewPlayer ?: return
        if (!::playbackTimeLabel.isInitialized) return
        val duration = player.duration.coerceAtLeast(0)
        val position = player.currentPosition.coerceAtLeast(0)
        playbackTimeLabel.text = "${clockText(position)} / ${clockText(duration)}"
        if (!playbackSeek.isPressed && duration > 0) playbackSeek.progress = ((position * 1000L) / duration).toInt()
        playbackSeek.isEnabled = duration > 0
        playbackSeek.postDelayed({ if (!isFinishing && !isDestroyed && player.isPlaying) updatePlaybackPosition() }, 250)
    }

    private fun clockText(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun runEngineAction(command: String, success: String) {
        executeEngineCommand(command) { response ->
            selectionLabel.text = if (response.optBoolean("ok")) success else response.optString("error", "Could not $success")
        }
    }

    private fun showProjectMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Project")
            .setItems(arrayOf("Add media", "Save draft", "Project information")) { _, which ->
                when (which) {
                    0 -> openMediaPicker()
                    1 -> selectionLabel.text = "Draft autosaves on this device"
                    else -> selectionLabel.text = "FilmCraft mobile project"
                }
            }.show()
    }

    private fun openToolPanel(tool: String) {
        when (tool) {
            "Effects" -> openEffectsBrowser()
            "Audio" -> openMediaPicker()
            "Edit" -> showEditPanel()
            "Overlay" -> openMediaPicker()
            "Text" -> Toast.makeText(this, "Text overlays are not available in this build", Toast.LENGTH_SHORT).show()
            "Filters" -> openEffectsBrowser()
            "Canvas" -> showCanvasPanel()
            else -> Unit
        }
    }

    private fun showEditPanel() {
        val items = arrayOf("Undo", "Redo", "Remove selected clip", "Add keyframe")
        android.app.AlertDialog.Builder(this).setTitle("Edit clip").setItems(items) { _, which ->
            when (which) {
                0 -> runEngineAction("edit.undo", "Undone")
                1 -> runEngineAction("edit.redo", "Redone")
                2 -> selectedClipUri?.let { confirmRemove(it, videoClips.any { clip -> clip.first == it }) }
                    ?: Toast.makeText(this, "Select a clip in the timeline first", Toast.LENGTH_SHORT).show()
                3 -> Toast.makeText(this, "Select a keyframeable clip and position the playhead first", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun showCanvasPanel() {
        val choices = arrayOf("Fit preview", "Fill preview", "Original size")
        android.app.AlertDialog.Builder(this).setTitle("Canvas")
            .setItems(choices) { _, which ->
                videoPreview.resizeMode = when (which) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                imagePreview.scaleType = when (which) {
                    1 -> ImageView.ScaleType.CENTER_CROP
                    2 -> ImageView.ScaleType.CENTER
                    else -> ImageView.ScaleType.FIT_CENTER
                }
                selectionLabel.text = "Canvas · ${choices[which]}"
            }.show()
    }

    private fun startExport() {
        if (!nativeSessionReady || activeTimelineClipId == null) {
            Toast.makeText(this, "Add media and wait for the FilmCraft timeline before exporting", Toast.LENGTH_LONG).show()
            return
        }
        val output = File(filesDir, "exports/FilmCraft-${System.currentTimeMillis()}.mp4")
        output.parentFile?.mkdirs()
        selectionLabel.text = "Preparing export · ${output.name}"
        importExecutor.execute {
            val result = runCatching {
                JSONObject(nativeExecute("export.quick", JSONObject().put("path", output.absolutePath).toString()))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { response ->
                    if (response.optBoolean("ok")) selectionLabel.text = "Export queued · ${output.name} in app storage"
                    else selectionLabel.text = response.optString("error", "Export could not start")
                }.onFailure { error -> selectionLabel.text = "Export failed · ${error.localizedMessage ?: "engine error"}" }
            }
        }
    }

    private fun mediaTrackRow(name: String, video: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(name, 9f, muted, true), LinearLayout.LayoutParams(dp(58), dp(48)))
        val lane = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (video) videoClipStrip = strip else audioClipStrip = strip
        lane.addView(strip)
        row.addView(lane, LinearLayout.LayoutParams(0, dp(48), 1f))
        return row
    }

    private fun renderClips() {
        if (!::videoClipStrip.isInitialized || !::audioClipStrip.isInitialized) return
        videoClipStrip.removeAllViews()
        audioClipStrip.removeAllViews()
        videoClips.forEachIndexed { index, clip ->
            videoClipStrip.addView(clipChip(clip.second, Color.rgb(48, 52, 58), { confirmRemove(clip.first, true) }) {
                selectedClipUri = clip.first
                activeTimelineClipId = timelineClipIds[clip.first]
                selectedClipHasVideo = true
                if (contentResolver.getType(clip.first)?.startsWith("image/") == true) showImage(clip.first)
                else showVideo(clip.first)
                selectionLabel.text = "Clip ${index + 1} · ${clip.second}"
            })
        }
        audioClips.forEachIndexed { index, clip ->
            audioClipStrip.addView(clipChip(clip.second, Color.rgb(39, 61, 52), { confirmRemove(clip.first, false) }) {
                selectedClipUri = clip.first
                activeTimelineClipId = timelineClipIds[clip.first]
                selectedClipHasVideo = false
                selectionLabel.text = "Audio ${index + 1} · ${clip.second}"
            })
        }
        if (videoClips.isEmpty()) videoClipStrip.addView(clipChip("＋ Import video", Color.rgb(48, 52, 58)) { openMediaPicker() })
        if (audioClips.isEmpty()) audioClipStrip.addView(clipChip("＋ Add audio", Color.rgb(39, 61, 52)) { openMediaPicker() })
    }

    private fun clipChip(title: String, color: Int, remove: (() -> Unit)? = null, action: () -> Unit) =
        label(title, 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(color, 8)
            setOnClickListener { action() }
            if (remove != null) setOnLongClickListener {
                remove()
                true
            }
            layoutParams = LinearLayout.LayoutParams(dp(172), dp(40)).apply { marginEnd = dp(6) }
        }

    private fun trackRow(name: String, clips: List<String>, colors: List<Int>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(name, 9f, muted, true), LinearLayout.LayoutParams(dp(58), dp(48)))
        val clipLane = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val clipStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clips.forEachIndexed { index, title ->
            clipStrip.addView(label(title, 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(colors[index % colors.size], 8)
                setOnClickListener { if (title.startsWith("＋") || title.startsWith("Add")) openMediaPicker() }
            }, LinearLayout.LayoutParams(dp(172), dp(40)).apply { marginEnd = dp(6) })
        }
        clipLane.addView(clipStrip)
        row.addView(clipLane, LinearLayout.LayoutParams(0, dp(48), 1f))
        return row
    }

    private fun toolBar(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("Edit", "Audio", "Overlay", "Text", "Effects", "Filters", "Canvas").forEach { title ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { openToolPanel(title) }
            }
            item.addView(label(toolGlyph(title), 20f, Color.WHITE))
            item.addView(label(title, 10f, muted))
            tools.addView(item, LinearLayout.LayoutParams(dp(64), dp(60)))
        }
        scroll.addView(tools)
        return scroll
    }

    private fun toolGlyph(name: String): String = when (name) {
        "Edit" -> "✂"
        "Audio" -> "♫"
        "Overlay" -> "▧"
        "Text" -> "T"
        "Effects" -> "✧"
        "Filters" -> "◐"
        else -> "▣"
    }

    private fun openMediaPicker() {
        mediaPicker.launch(arrayOf("video/*", "image/*", "audio/*"))
    }

    private fun confirmRemove(uri: Uri, video: Boolean) {
        val list = if (video) videoClips else audioClips
        val clip = list.firstOrNull { it.first == uri } ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove clip?")
            .setMessage("Remove ${clip.second} from this draft?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                list.removeAll { it.first == uri }
                timelineClipIds.remove(uri)
                if (video && activeVideo == uri) {
                    previewPlayer?.stop()
                    activeVideo = null
                    activeTimelineClipId = null
                    selectedClipHasVideo = false
                    videoClips.firstOrNull()?.first?.let(::showVideo) ?: run {
                        videoPreview.visibility = View.GONE
                        emptyPreview.visibility = View.VISIBLE
                    }
                }
                if (selectedClipUri == uri) {
                    selectedClipUri = videoClips.firstOrNull()?.first ?: audioClips.firstOrNull()?.first
                    activeTimelineClipId = selectedClipUri?.let(timelineClipIds::get)
                    selectedClipHasVideo = selectedClipUri?.let { selected -> videoClips.any { it.first == selected } } == true
                }
                persistClips()
                renderClips()
                selectionLabel.text = "Clip removed from draft"
            }
            .show()
    }

    private fun acceptMedia(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers grant access only for the current activity session.
        }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Untitled media"
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
        when {
            mimeType?.startsWith("video/") == true -> {
                if (videoClips.any { it.first == uri }) return
                videoClips.add(uri to name)
                selectedClipUri = uri
                activeTimelineClipId = timelineClipIds[uri]
                selectedClipHasVideo = true
                showVideo(uri)
                selectionLabel.text = "Added video · $name"
            }
            mimeType?.startsWith("image/") == true -> {
                if (videoClips.any { it.first == uri }) return
                videoClips.add(uri to name)
                selectedClipUri = uri
                showImage(uri)
                selectionLabel.text = "Photo preview · $name"
            }
            mimeType?.startsWith("audio/") == true -> {
                if (audioClips.any { it.first == uri }) return
                audioClips.add(uri to name)
                selectedClipUri = uri
                activeTimelineClipId = timelineClipIds[uri]
                selectedClipHasVideo = false
                selectionLabel.text = "Added audio · $name"
            }
            else -> selectionLabel.text = "Unsupported media type · $name"
        }
        persistClips()
        renderClips()
        queueNativeImport(uri, name)
    }

    private fun initializeRustSession() {
        val response = runCatching {
            System.loadLibrary("filmcraft_android")
            JSONObject(nativeInitialize(filesDir.absolutePath))
        }.getOrElse { error ->
            selectionLabel.text = "Rust editing core is not available in this build"
            Log.i("FilmCraftAndroid", "Rust core unavailable", error)
            return
        }
        nativeSessionReady = response.optBoolean("ok")
        if (!nativeSessionReady) {
            selectionLabel.text = response.optString("error", "Could not open the FilmCraft draft")
        } else {
            selectionLabel.text = "FilmCraft project ready · import media to build the timeline"
            runCatching { JSONObject(nativeListCommands()) }.onSuccess { catalog ->
                if (catalog.optBoolean("ok")) {
                    engineCommandCatalog.clear()
                    catalog.optJSONArray("result")?.let { commands ->
                        for (index in 0 until commands.length()) commands.optJSONObject(index)?.let(engineCommandCatalog::add)
                        selectionLabel.text = "FilmCraft ready · ${engineCommandCatalog.size} engine operations available"
                    }
                }
            }
            runCatching { JSONObject(nativeListVideoEffects()) }.onSuccess { catalog ->
                if (catalog.optBoolean("ok")) {
                    val list = catalog.optJSONArray("result")
                    effectCatalog.clear()
                    if (list != null) for (index in 0 until list.length()) {
                        list.optJSONObject(index)?.let(effectCatalog::add)
                    }
                }
            }
        }
    }

    private fun queueNativeImport(uri: Uri, name: String) {
        if (!nativeSessionReady) {
            selectionLabel.text = "Added to the Android draft list · Rust core not packaged"
            return
        }
        importExecutor.execute {
            val result = runCatching {
                val localPath = copyIntoAppStorage(uri, name)
                JSONObject(nativeImportMedia(localPath))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { response ->
                    if (response.optBoolean("ok")) {
                        val result = response.optJSONObject("result")
                        val timeline = result?.optJSONObject("timeline")
                        val clipId = timeline?.optJSONObject("result")?.optJSONArray("clips")?.optLong(0, -1L)?.takeIf { it > 0L }
                        if (clipId != null) {
                            timelineClipIds[uri] = clipId
                            if (selectedClipUri == uri) {
                                activeTimelineClipId = clipId
                                selectedClipHasVideo = videoClips.any { it.first == uri }
                            }
                            persistClips()
                        }
                        val detail = timeline?.optString("error", "timeline unavailable") ?: "timeline unavailable"
                        selectionLabel.text = if (timeline?.optBoolean("ok") == true) "Added to FilmCraft timeline · $name"
                        else "Imported to project bin · $detail"
                    } else selectionLabel.text = response.optString("error", "FilmCraft could not import $name")
                }.onFailure { error ->
                    selectionLabel.text = "Could not import $name · ${error.localizedMessage ?: "read error"}"
                    Log.w("FilmCraftAndroid", "Could not copy selected media", error)
                }
            }
        }
    }


    private fun openEffectsBrowser() {
        if (!nativeSessionReady || effectCatalog.isEmpty()) {
            Toast.makeText(this, "The FilmCraft effects catalog is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val clipId = activeTimelineClipId
        if (!selectedClipHasVideo || clipId == null) {
            Toast.makeText(this, "Select a video or photo clip first", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(background)
        }
        val search = EditText(this).apply {
            hint = "Search effects"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            background = rounded(surface, 10)
            setPadding(dp(12), 0, dp(12), 0)
        }
        dialogContent.addView(search, LinearLayout.LayoutParams(-1, dp(46)))

        val categoryStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryStrip)
        }
        dialogContent.addView(categoryScroll, LinearLayout.LayoutParams(-1, dp(48)))

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultsScroll = ScrollView(this).apply { addView(rows) }
        dialogContent.addView(resultsScroll, LinearLayout.LayoutParams(-1, dp(390)))

        var selectedFolder = "All"
        fun renderEffects() {
            rows.removeAllViews()
            val needle = search.text.toString().trim().lowercase()
            val visible = effectCatalog.filter { effect ->
                val matchesFolder = selectedFolder == "All" || effect.optString("folder") == selectedFolder
                val searchable = "${effect.optString("name")} ${effect.optString("path")}".lowercase()
                matchesFolder && (needle.isEmpty() || searchable.contains(needle))
            }
            visible.forEach { effect ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = rounded(surface, 10)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialogReference?.let { applyNativeEffect(clipId, effect, it) } }
                }
                row.addView(label(effect.optString("name"), 14f, Color.WHITE, true))
                val effectId = effect.optString("id")
                val previewTag = if (effectId == "black_white" || effectId == "invert") "Live preview" else "Project effect"
                row.addView(label("${effect.optString("folder", "Video Effects")}  ·  $previewTag", 10f, muted))
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) })
            }
            if (visible.isEmpty()) rows.addView(label("No matching effects", 13f, muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(64)))
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("CraftEffects · Video")
            .setView(dialogContent)
            .setNegativeButton("Done", null)
            .create()
        val folders = listOf("All") + effectCatalog.map { it.optString("folder") }.filter { it.isNotBlank() }.distinct().sorted()
        folders.forEach { folder ->
            categoryStrip.addView(label(folder, 11f, if (folder == "All") accent else Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    selectedFolder = folder
                    renderEffects()
                    for (index in 0 until categoryStrip.childCount) {
                        (categoryStrip.getChildAt(index) as? TextView)?.setTextColor(if (folders.getOrNull(index) == selectedFolder) accent else Color.WHITE)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderEffects()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderEffects()
        dialogReference = dialog
        dialog.setOnDismissListener { dialogReference = null }
        dialog.show()
    }

    private fun executeEngineCommand(commandId: String, parameters: JSONObject = JSONObject(), onComplete: (JSONObject) -> Unit = {}) {
        if (!nativeSessionReady) {
            selectionLabel.text = "FilmCraft engine is not ready"
            return
        }
        importExecutor.execute {
            val result = runCatching { JSONObject(nativeExecute(commandId, parameters.toString())) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(onComplete).onFailure { error ->
                    selectionLabel.text = "${commandId} failed · ${error.localizedMessage ?: "engine error"}"
                }
            }
        }
    }

    private fun applyNativeEffect(clipId: Long, effect: JSONObject, dialog: android.app.AlertDialog) {
        val id = effect.optString("id")
        val name = effect.optString("name", id)
        if (id.isBlank()) return
        dialog.dismiss()
        importExecutor.execute {
            val result = runCatching { JSONObject(nativeApplyEffect(clipId, id)) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { response ->
                    if (response.optBoolean("ok")) {
                        val previewSupported = applyPreviewEffect(id)
                        selectionLabel.text = if (previewSupported) "Applied $name · live preview"
                        else "Applied $name · saved for FilmCraft render"
                    } else selectionLabel.text = response.optString("error", "Could not apply $name")
                }.onFailure { error ->
                    selectionLabel.text = "Could not apply $name · ${error.localizedMessage ?: "native error"}"
                }
            }
        }
    }

    private fun applyPreviewEffect(effectId: String): Boolean {
        if (effectId != "black_white" && effectId != "invert") return false
        livePreviewEffectIds.add(effectId)
        if (activeVideo == null && imagePreview.visibility == View.VISIBLE) {
            val matrix = ColorMatrix()
            livePreviewEffectIds.forEach { id ->
                val next = when (id) {
                    "black_white" -> ColorMatrix().apply { setSaturation(0f) }
                    else -> ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ))
                }
                matrix.postConcat(next)
            }
            imagePreview.colorFilter = ColorMatrixColorFilter(matrix)
            return true
        }
        if (activeVideo == null || previewPlayer == null) return false
        val mapped = livePreviewEffectIds.map { id ->
            if (id == "black_white") RgbFilter.createGrayscaleFilter() else RgbFilter.createInvertedFilter()
        }
        previewPlayer?.setVideoEffects(mapped)
        return true
    }

    private fun applyPreviewEffectForClip() {
        livePreviewEffectIds.clear()
        previewPlayer?.setVideoEffects(emptyList())
        imagePreview.colorFilter = null
    }

    private fun showImage(uri: Uri) {
        imageLoadGeneration++
        selectedClipUri = uri
        activeVideo = null
        activeTimelineClipId = timelineClipIds[uri]
        selectedClipHasVideo = true
        applyPreviewEffectForClip()
        imagePreview.setImageDrawable(null)
        imagePreview.visibility = View.VISIBLE
        videoPreview.visibility = View.GONE
        emptyPreview.visibility = View.GONE
        previewPlayer?.stop()
        val request = ++imageLoadGeneration
        imagePreviewExecutor.execute {
            val bitmap = runCatching { decodePreviewBitmap(uri) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || request != imageLoadGeneration) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) selectionLabel.text = "This photo could not be opened"
                else imagePreview.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodePreviewBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) && sample < (1 shl 20)) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun copyIntoAppStorage(uri: Uri, displayName: String): String {
        val mediaDir = File(filesDir, "media")
        if (!mediaDir.isDirectory && !mediaDir.mkdirs()) throw java.io.IOException("Could not create app media folder")
        val rawExtension = displayName.substringAfterLast('.', "")
        val extension = rawExtension.takeIf { it.length in 1..10 && it.all { character -> character.isLetterOrDigit() } }?.let { ".$it" } ?: ""
        val target = File(mediaDir, "${UUID.randomUUID()}$extension")
        try {
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("The selected document could not be opened")
            input.use { source -> FileOutputStream(target).use { destination -> source.copyTo(destination, 64 * 1024) } }
            return target.absolutePath
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun togglePreviewPlayback() {
        val player = previewPlayer
        if (player?.isPlaying == true) player.pause()
        else if (activeVideo != null) player?.play() ?: openMediaPicker()
        else openMediaPicker()
    }

    private fun initializePreviewPlayer() {
        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(1280, 720)
                .setExceedVideoConstraintsIfNecessary(true)
                .build()
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 3_000, 500, 1_000)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        previewPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build().also { player ->
            player.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    selectionLabel.text = "Preview playback error · ${error.errorCodeName}"
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlaybackPosition()
                }
            })
            videoPreview.player = player
        }
    }

    private fun showVideo(uri: Uri) {
        imageLoadGeneration++
        selectedClipUri = uri
        activeTimelineClipId = timelineClipIds[uri]
        selectedClipHasVideo = true
        if (activeVideo != uri) applyPreviewEffectForClip()
        activeVideo = uri
        imagePreview.visibility = View.GONE
        emptyPreview.visibility = View.GONE
        videoPreview.visibility = View.VISIBLE
        previewPlayer?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }

    private fun persistClips() {
        val prefs = getSharedPreferences("mobile_project", MODE_PRIVATE)
        fun encode(items: List<Pair<Uri, String>>) = JSONArray().apply {
            items.forEach { (uri, name) ->
                put(JSONArray().put(uri.toString()).put(name).put(timelineClipIds[uri] ?: JSONObject.NULL))
            }
        }.toString()
        prefs.edit().putString("video_clips", encode(videoClips)).putString("audio_clips", encode(audioClips)).apply()
    }

    private fun restoreClips() {
        val prefs = getSharedPreferences("mobile_project", MODE_PRIVATE)
        fun decode(key: String, into: MutableList<Pair<Uri, String>>) {
            val data = prefs.getString(key, null) ?: return
            runCatching {
                val array = JSONArray(data)
                for (index in 0 until array.length()) {
                    val item = array.optJSONArray(index) ?: continue
                    val value = item.optString(0)
                    val name = item.optString(1, "Untitled media")
                    if (value.isNotBlank()) {
                        val uri = Uri.parse(value)
                        into.add(uri to name)
                        item.optLong(2, -1L).takeIf { it > 0L }?.let { timelineClipIds[uri] = it }
                    }
                }
            }
        }
        decode("video_clips", videoClips)
        decode("audio_clips", audioClips)
        val lastVideo = videoClips.lastOrNull()?.first
        selectedClipUri = lastVideo
        activeTimelineClipId = lastVideo?.let(timelineClipIds::get)
        selectedClipHasVideo = activeTimelineClipId != null
    }

    override fun onPause() {
        previewPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        importExecutor.shutdown()
        imageLoadGeneration++
        imagePreviewExecutor.shutdownNow()
        videoPreview.player = null
        previewPlayer?.release()
        previewPlayer = null
        super.onDestroy()
    }

    private external fun nativeInitialize(dataDir: String): String
    private external fun nativeImportMedia(path: String): String
    private external fun nativeListVideoEffects(): String
    private external fun nativeApplyEffect(clipId: Long, effectId: String): String
    private external fun nativeListCommands(): String
    private external fun nativeExecute(commandId: String, parametersJson: String): String

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
    }

    private fun buttonParams() = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pill(color: Int, radius: Int) = rounded(color, radius)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
