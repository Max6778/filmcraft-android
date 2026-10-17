package com.filmcraft.mobile

import androidx.activity.ComponentActivity
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
import android.view.MotionEvent
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
class MainActivity : ComponentActivity() {
    private val background = Color.rgb(17, 18, 20)
    private val surface = Color.rgb(29, 31, 35)
    private val muted = Color.rgb(150, 153, 160)
    private val accent = Color.rgb(167, 240, 74)
    private val TICKS_PER_SECOND = 254_016_000_000L
    private val TICKS_PER_MILLISECOND = TICKS_PER_SECOND / 1_000L
    private val TIMELINE_PX_PER_SECOND = dp(46).toFloat()
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
    private lateinit var overlayClipStrip: LinearLayout
    private lateinit var audioClipStrip: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var overlayPreview: ImageView
    private val videoClips = mutableListOf<Pair<Uri, String>>()
    private val overlayClips = mutableListOf<Pair<Uri, String>>()
    private val audioClips = mutableListOf<Pair<Uri, String>>()
    private var pickingOverlay = false
    private var overlayLoadGeneration = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var gestureStartDistance = 1f
    private var gestureStartAngle = 0f
    private var gestureStartRotation = 0f
    private var gestureStartScale = 1f
    private val timelineClipIds = mutableMapOf<Uri, Long>()
    private val timelineStartTicks = mutableMapOf<Uri, Long>()
    private val timelineDurationTicks = mutableMapOf<Uri, Long>()
    private val selectedClipUris = linkedSetOf<Uri>()
    private data class TimelineSegment(val id: Long, val itemId: Long, val uri: Uri, val name: String, val kind: String, val start: Long, val duration: Long, val sourceIn: Long)
    private val timelineSegments = mutableListOf<TimelineSegment>()
    private val itemUris = mutableMapOf<Long, Uri>()
    private val selectedSegmentIds = linkedSetOf<Long>()
    private var selectedSegmentId: Long? = null
    private val effectCatalog = mutableListOf<JSONObject>()
    private val effectCraftCatalog = mutableListOf<JSONObject>()
    private val effectCraftCommandCatalog = mutableListOf<JSONObject>()
    private val engineCommandCatalog = mutableListOf<JSONObject>()
    private var activeVideo: Uri? = null
    private var selectedClipUri: Uri? = null
    private var activeTimelineClipId: Long? = null
    private var selectedClipHasVideo = false
    private val livePreviewEffectIds = mutableListOf<String>()
    private var nativeSessionReady = false
    private var effectCraftSessionReady = false
    private var selectedEffectCraftLayerId: Long? = null
    private var dialogReference: android.app.AlertDialog? = null
    private val importExecutor = Executors.newSingleThreadExecutor()

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val asOverlay = pickingOverlay
        pickingOverlay = false
        uris.forEach { uri -> acceptMedia(uri, asOverlay) }
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
        videoClips.lastOrNull()?.first?.let { uri ->
            if (contentResolver.getType(uri)?.startsWith("image/") == true) showImage(uri) else showVideo(uri, autoPlay = false)
        }
        overlayClips.lastOrNull()?.first?.let(::showOverlayPreview)
        initializeRustSession()
        initializeEffectCraftSession()
    }

    private fun buildEditor(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
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

        previewFrame = FrameLayout(this).apply {
            background = rounded(Color.rgb(5, 6, 8), 12)
            clipToOutline = true
        }
        videoPreview = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        previewFrame.addView(videoPreview, FrameLayout.LayoutParams(-1, -1))
        imagePreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        previewFrame.addView(imagePreview, FrameLayout.LayoutParams(-1, -1))
        overlayPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setOnTouchListener(::handleOverlayTouch)
        }
        previewFrame.addView(overlayPreview, FrameLayout.LayoutParams(dp(150), dp(110), Gravity.CENTER))
        emptyPreview = label("＋\nAdd a video or photo\nYour preview appears here", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { openMediaPicker() }
            background = rounded(Color.rgb(9, 10, 12), 12)
        }
        emptyPreview.maxLines = 3
        previewFrame.addView(emptyPreview, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(label("PREVIEW", 9f, Color.WHITE, true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(Color.argb(170, 30, 31, 34), 6)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(8); leftMargin = dp(8)
        })
        previewFrame.addView(label("FIT  ·  720p", 9f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(Color.argb(170, 30, 31, 34), 8)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(8); rightMargin = dp(8)
        })
        previewFrame.setOnClickListener { togglePreviewPlayback() }
        videoPreview.setOnClickListener { togglePreviewPlayback() }
        root.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f).apply {
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
        root.addView(timelineActionBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val tracks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 12)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        tracks.addView(mediaTrackRow("VIDEO", "video"))
        tracks.addView(mediaTrackRow("OVERLAY", "overlay"))
        tracks.addView(mediaTrackRow("AUDIO", "audio"))
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
            "Motion" -> showEffectCraftPanel()
            "Audio" -> openMediaPicker()
            "Edit" -> showEditPanel()
            "Overlay" -> openOverlayPicker()
            "Text" -> Toast.makeText(this, "Text overlays are not available in this build", Toast.LENGTH_SHORT).show()
            "Filters" -> openEffectsBrowser()
            "Canvas" -> showCanvasPanel()
            else -> Unit
        }
    }

    private fun showEditPanel() {
        val items = arrayOf("Undo", "Redo", "Remove selected clip", "Add transform keyframe", "Create scale bounce")
        android.app.AlertDialog.Builder(this).setTitle("Edit clip").setItems(items) { _, which ->
            when (which) {
                0 -> runEngineAction("edit.undo", "Undone")
                1 -> runEngineAction("edit.redo", "Redone")
                2 -> selectedClipUri?.let { confirmRemove(it, videoClips.any { clip -> clip.first == it }) }
                    ?: Toast.makeText(this, "Select a clip in the timeline first", Toast.LENGTH_SHORT).show()
                3 -> addTransformKeyframes()
                4 -> createScaleBounce()
            }
        }.show()
    }

    private fun playheadTicks(): Long = ((previewPlayer?.currentPosition ?: 0L).coerceAtLeast(0L) * 254_016_000L)

    private fun addTransformKeyframes() {
        val clip = activeTimelineClipId
        if (!nativeSessionReady || clip == null || !selectedClipHasVideo) {
            Toast.makeText(this, "Select a video or photo clip first", Toast.LENGTH_SHORT).show()
            return
        }
        val time = playheadTicks()
        selectionLabel.text = "Adding transform keyframes…"
        importExecutor.execute {
            val result = runCatching {
                listOf("position", "scale", "rotation").forEach { parameter ->
                    val response = JSONObject(nativeExecute("effects.addKeyframe", JSONObject()
                        .put("clip", clip).put("effect", "motion").put("param", parameter).put("time", time).toString()))
                    if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Could not keyframe $parameter"))
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selectionLabel.text = result.fold({ "Transform keys added at ${clockText(previewPlayer?.currentPosition ?: 0L)}" },
                    { error -> "Keyframe failed · ${error.localizedMessage ?: "FilmCraft error"}" })
            }
        }
    }

    private fun createScaleBounce() {
        val clip = activeTimelineClipId
        if (!nativeSessionReady || clip == null || !selectedClipHasVideo) {
            Toast.makeText(this, "Select a video or photo clip first", Toast.LENGTH_SHORT).show()
            return
        }
        val start = playheadTicks()
        val beats = listOf(0L to 100.0, 220L to 124.0, 420L to 92.0, 680L to 110.0, 920L to 100.0)
        selectionLabel.text = "Creating scale bounce…"
        importExecutor.execute {
            val result = runCatching {
                beats.forEachIndexed { index, (offsetMs, scale) ->
                    val response = JSONObject(nativeExecute("effects.setParam", JSONObject()
                        .put("clip", clip).put("effect", "motion").put("param", "scale")
                        .put("value", scale).put("time", start + offsetMs * 254_016_000L)
                        .put("keyframe", true).put("begin", index == 0).toString()))
                    if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Could not create bounce keyframe"))
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selectionLabel.text = result.fold({ "Scale bounce added · preview the clip to see the animation" },
                    { error -> "Bounce failed · ${error.localizedMessage ?: "FilmCraft error"}" })
            }
        }
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

    private fun timelineActionBar(): View {
        val lane = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(lane)
        }
        val actions = listOf(
            Triple(TimelineIconView.Glyph.SELECT, "Select", ::selectPrimaryTimelineClip),
            Triple(TimelineIconView.Glyph.RAZOR, "Split", ::splitSelectedClipAtPlayhead),
            Triple(TimelineIconView.Glyph.TRIM_IN, "Trim start", { trimSelectedClipToPlayhead(trimIn = true) }),
            Triple(TimelineIconView.Glyph.TRIM_OUT, "Trim end", { trimSelectedClipToPlayhead(trimIn = false) }),
            Triple(TimelineIconView.Glyph.GROUP, "Group", ::groupSelectedClips),
            Triple(TimelineIconView.Glyph.NEST, "Nest", ::nestSelectedClips),
            Triple(TimelineIconView.Glyph.EFFECTS, "Effects", ::openEffectsBrowser),
            Triple(TimelineIconView.Glyph.DELETE, "Delete", ::deleteSelectedClips),
        )
        actions.forEach { (glyph, title, action) ->
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                addView(TimelineIconView(this@MainActivity, glyph, if (title == "Effects") accent else Color.WHITE), LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(label(title, 9f, muted, true))
                setOnClickListener { action() }
                contentDescription = title
            }
            lane.addView(button, LinearLayout.LayoutParams(dp(66), dp(50)))
        }
        return scroll
    }

    private fun mediaTrackRow(name: String, track: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = when (track) {
            "video" -> TimelineIconView.Glyph.SELECT
            "overlay" -> TimelineIconView.Glyph.NEST
            else -> TimelineIconView.Glyph.EFFECTS
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TimelineIconView(this@MainActivity, icon, muted), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(label(name, 8f, muted, true))
        }, LinearLayout.LayoutParams(dp(58), dp(48)))
        val lane = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), 0, dp(8), 0)
        }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        when (track) {
            "video" -> videoClipStrip = strip
            "overlay" -> overlayClipStrip = strip
            else -> audioClipStrip = strip
        }
        lane.addView(strip)
        row.addView(lane, LinearLayout.LayoutParams(0, dp(48), 1f))
        return row
    }

    private fun renderClips() {
        if (!::videoClipStrip.isInitialized || !::audioClipStrip.isInitialized) return
        videoClipStrip.removeAllViews(); overlayClipStrip.removeAllViews(); audioClipStrip.removeAllViews()
        fun renderTrack(kind: String, strip: LinearLayout, tint: Int) {
            val segments = timelineSegments.filter { it.kind == kind }.sortedBy { it.start }
            var previousEnd = 0L
            segments.forEachIndexed { index, segment ->
                val gap = (segment.start - previousEnd).coerceAtLeast(0L)
                val gapMargin = if (index == 0) 0 else (gap.toDouble() / TICKS_PER_SECOND * TIMELINE_PX_PER_SECOND).toInt().coerceAtMost(dp(180))
                if (gapMargin > 0) strip.addView(View(this), LinearLayout.LayoutParams(gapMargin, 1))
                val seconds = segment.duration.toDouble() / TICKS_PER_SECOND
                val width = (seconds * TIMELINE_PX_PER_SECOND).toInt().coerceIn(dp(96), dp(340))
                val chosen = segment.id in selectedSegmentIds
                strip.addView(clipBlock(segment.name, tint, chosen, kind, width, segment.duration, {
                    selectTimelineSegment(segment)
                }, {
                    if (segment.id in selectedSegmentIds) selectedSegmentIds.remove(segment.id) else selectedSegmentIds.add(segment.id)
                    if (selectedSegmentIds.isEmpty()) {
                        selectedSegmentId = null; selectedClipUri = null; activeTimelineClipId = null; selectedClipHasVideo = false
                        selectionLabel.text = "No clip selected"; renderClips()
                    } else selectTimelineSegment(segment, preserve = true)
                }))
                previousEnd = maxOf(previousEnd, segment.start + segment.duration)
            }
            if (segments.isEmpty()) {
                val label = when (kind) { "video" -> "＋ Import video"; "overlay" -> "＋ Add overlay"; else -> "＋ Add audio" }
                strip.addView(clipBlock(label, tint, false, kind, dp(140), null, { if (kind == "overlay") openOverlayPicker() else openMediaPicker() }, {}))
            }
        }
        renderTrack("video", videoClipStrip, Color.rgb(48, 78, 96))
        renderTrack("overlay", overlayClipStrip, Color.rgb(74, 55, 97))
        renderTrack("audio", audioClipStrip, Color.rgb(43, 83, 64))
    }

    private fun clipBlock(title: String, tint: Int, selected: Boolean, kind: String, width: Int, durationTicks: Long?, tap: () -> Unit, longPress: () -> Unit): View {
        val icon = when (kind) {
            "video" -> TimelineIconView.Glyph.SELECT
            "overlay" -> TimelineIconView.Glyph.NEST
            else -> TimelineIconView.Glyph.EFFECTS
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(4))
            background = rounded(if (selected) Color.rgb(49, 70, 48) else tint, 7).apply {
                setStroke(dp(if (selected) 2 else 1), if (selected) accent else Color.argb(120, 180, 185, 190))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { tap() }
            setOnLongClickListener { longPress(); true }
            val heading = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TimelineIconView(this@MainActivity, icon, if (selected) accent else Color.WHITE), LinearLayout.LayoutParams(dp(15), dp(15)))
                addView(label(title, 10f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(5), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(heading, LinearLayout.LayoutParams(-1, dp(20)))
            val seconds = durationTicks?.let { String.format(java.util.Locale.US, "%.1fs", it.toDouble() / TICKS_PER_SECOND) } ?: "FilmCraft clip"
            addView(label(seconds, 8f, Color.argb(190, 235, 239, 240)))
            layoutParams = LinearLayout.LayoutParams(width, dp(40)).apply { marginEnd = dp(5) }
        }
    }

    private fun selectPrimaryTimelineClip() {
        val segment = selectedSegmentId?.let { id -> timelineSegments.firstOrNull { it.id == id } }
            ?: timelineSegments.firstOrNull { it.uri == selectedClipUri }
        if (segment == null) { Toast.makeText(this, "Tap a timeline clip first", Toast.LENGTH_SHORT).show(); return }
        selectTimelineSegment(segment, preserve = true)
    }

    private fun selectTimelineSegment(segment: TimelineSegment, preserve: Boolean = false) {
        if (!preserve) { selectedSegmentIds.clear(); selectedSegmentIds.add(segment.id) }
        selectedSegmentId = segment.id
        selectedClipUri = segment.uri
        activeTimelineClipId = segment.id
        selectedClipHasVideo = segment.kind != "audio"
        if (segment.kind == "overlay") showOverlayPreview(segment.uri)
        else if (segment.kind == "video") {
            if (contentResolver.getType(segment.uri)?.startsWith("image/") == true) showImage(segment.uri)
            else showVideo(segment.uri, startPositionMs = segment.sourceIn / TICKS_PER_MILLISECOND)
        }
        activeTimelineClipId = segment.id
        val ids = selectedSegmentIds.toList()
        if (nativeSessionReady && ids.isNotEmpty()) executeEngineCommand("timeline.select", JSONObject().put("clips", JSONArray(ids))) { }
        selectionLabel.text = if (ids.size > 1) "${ids.size} clips selected · Split, Group, Nest or Effects" else "Selected · ${segment.name}"
        renderClips()
    }

    private fun selectedTimelineId(): Long? = selectedSegmentId ?: activeTimelineClipId

    private fun currentTimelineTicks(): Long {
        val segment = timelineSegments.firstOrNull { it.id == selectedSegmentId } ?: return 0L
        val mediaPosition = previewPlayer?.currentPosition?.coerceAtLeast(0L)?.times(TICKS_PER_MILLISECOND) ?: segment.sourceIn
        return (segment.start + mediaPosition - segment.sourceIn).coerceIn(segment.start, segment.start + segment.duration)
    }

    private fun splitSelectedClipAtPlayhead() {
        val ids = selectedSegmentIds.toList()
        if (!nativeSessionReady || ids.isEmpty()) { Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show(); return }
        executeEngineCommand("timeline.select", JSONObject().put("clips", JSONArray(ids))) { selected ->
            if (!selected.optBoolean("ok")) return@executeEngineCommand
            executeEngineCommand("sequence.addEdit", JSONObject().put("time", currentTimelineTicks())) { response ->
                selectionLabel.text = if (response.optBoolean("ok")) "Split at playhead" else response.optString("error", "Could not split clip")
                refreshNativeTimeline()
            }
        }
    }

    private fun trimSelectedClipToPlayhead(trimIn: Boolean) {
        val segment = timelineSegments.firstOrNull { it.id == selectedSegmentId }
        val offset = (previewPlayer?.currentPosition ?: 0L).coerceAtLeast(0L) * TICKS_PER_MILLISECOND - (segment?.sourceIn ?: 0L)
        if (!nativeSessionReady || segment == null || segment.kind == "audio" || offset <= 0L || offset >= segment.duration) {
            Toast.makeText(this, "Select a video and move its playhead inside the clip", Toast.LENGTH_SHORT).show(); return
        }
        val delta = if (trimIn) offset else offset - segment.duration
        executeEngineCommand("timeline.trim", JSONObject().put("clip", segment.id).put("edge", if (trimIn) "in" else "out").put("mode", "regular").put("delta", delta)) { response ->
            selectionLabel.text = if (response.optBoolean("ok")) "Trimmed at playhead" else response.optString("error", "Could not trim clip")
            refreshNativeTimeline()
        }
    }

    private fun groupSelectedClips() {
        runSelectedClipCommand("clip.group", JSONObject(), "Grouped selected clips")
    }

    private fun nestSelectedClips() {
        if (selectedSegmentIds.isEmpty()) {
            Toast.makeText(this, "Select clips first · long-press another clip to add it", Toast.LENGTH_SHORT).show()
            return
        }
        val name = EditText(this).apply {
            hint = "Nested sequence name"
            setText("Nested Sequence")
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(surface, 10)
        }
        android.app.AlertDialog.Builder(this).setTitle("Nest selected clips").setView(name).setNegativeButton("Cancel", null)
            .setPositiveButton("Nest", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = name.text.toString().trim().ifEmpty { "Nested Sequence" }
                        dialog.dismiss()
                        runSelectedClipCommand("clip.nest", JSONObject().put("name", value), "Created $value")
                    }
                }
                dialog.show()
            }
    }

    private fun deleteSelectedClips() {
        if (selectedSegmentIds.isEmpty()) { Toast.makeText(this, "Tap a clip first", Toast.LENGTH_SHORT).show(); return }
        runSelectedClipCommand("edit.clear", JSONObject(), "Removed selected clip(s)")
    }

    private fun runSelectedClipCommand(command: String, parameters: JSONObject, success: String) {
        val ids = selectedSegmentIds.toList()
        if (!nativeSessionReady || ids.isEmpty()) {
            Toast.makeText(this, "Select one or more timeline clips first", Toast.LENGTH_SHORT).show()
            return
        }
        importExecutor.execute {
            val result = runCatching {
                val selected = JSONObject(nativeExecute("timeline.select", JSONObject().put("clips", JSONArray(ids)).toString()))
                if (!selected.optBoolean("ok")) throw IllegalStateException(selected.optString("error", "Selection failed"))
                JSONObject(nativeExecute(command, parameters.toString()))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { response ->
                    selectionLabel.text = if (response.optBoolean("ok")) success else response.optString("error", "$command failed")
                    refreshNativeTimeline()
                }.onFailure { error -> selectionLabel.text = error.localizedMessage ?: "$command failed" }
            }
        }
    }

    private fun refreshNativeTimeline() {
        if (!nativeSessionReady) return
        executeEngineCommand("sequence.inspect") { response ->
            if (!response.optBoolean("ok")) return@executeEngineCommand
            val result = response.optJSONObject("result") ?: return@executeEngineCommand
            val refreshed = mutableListOf<TimelineSegment>()
            fun readTracks(name: String, kind: String) {
                result.optJSONArray(name)?.let { tracks ->
                    for (trackIndex in 0 until tracks.length()) {
                        val items = tracks.optJSONObject(trackIndex)?.optJSONArray("items") ?: continue
                        for (itemIndex in 0 until items.length()) {
                            val item = items.optJSONObject(itemIndex) ?: continue
                            val clipId = item.optLong("clip", -1L)
                            val itemId = item.optLong("item", -1L)
                            val uri = itemUris[itemId] ?: timelineClipIds.entries.firstOrNull { it.value == clipId }?.key ?: continue
                            itemUris[itemId] = uri
                            val clipKind = if (kind == "audio") "audio" else if (overlayClips.any { it.first == uri }) "overlay" else "video"
                            val nameValue = (videoClips + overlayClips + audioClips).firstOrNull { it.first == uri }?.second ?: "Clip"
                            refreshed.add(TimelineSegment(clipId, itemId, uri, nameValue, clipKind, item.optLong("start"), item.optLong("duration"), item.optLong("sourceIn")))
                        }
                    }
                }
            }
            readTracks("video", "video"); readTracks("audio", "audio")
            timelineSegments.clear(); timelineSegments.addAll(refreshed)
            timelineStartTicks.clear(); timelineDurationTicks.clear()
            refreshed.groupBy { it.uri }.forEach { (uri, clips) ->
                timelineStartTicks[uri] = clips.minOf { it.start }
                timelineDurationTicks[uri] = clips.sumOf { it.duration }
            }
            val liveIds = refreshed.map { it.id }.toSet()
            selectedSegmentIds.retainAll(liveIds)
            if (selectedSegmentId != null && selectedSegmentId !in liveIds) {
                selectedSegmentId = null; selectedClipUri = null; activeTimelineClipId = null; selectedClipHasVideo = false
            }
            renderClips()
        }
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
        listOf("Edit", "Audio", "Overlay", "Text", "Effects", "Motion", "Filters", "Canvas").forEach { title ->
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
        "Motion" -> "◆"
        "Filters" -> "◐"
        else -> "▣"
    }

    private fun openMediaPicker() {
        pickingOverlay = false
        mediaPicker.launch(arrayOf("video/*", "image/*", "audio/*"))
    }

    private fun openOverlayPicker() {
        if (!nativeSessionReady || videoClips.isEmpty()) {
            Toast.makeText(this, "Add a main video or photo before adding an overlay", Toast.LENGTH_LONG).show()
            return
        }
        pickingOverlay = true
        mediaPicker.launch(arrayOf("image/*"))
    }

    private fun confirmRemove(uri: Uri, video: Boolean) {
        val list = if (video) videoClips else audioClips
        val clip = list.firstOrNull { it.first == uri } ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove clip?")
            .setMessage("Remove ${clip.second} from this draft?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeMediaFromDraft(uri, video) }
            .show()
    }

    private fun removeMediaFromDraft(uri: Uri, video: Boolean) {
        val clipId = timelineClipIds[uri]
        if (clipId == null || !nativeSessionReady) {
            forgetMediaLocally(uri, video)
            selectionLabel.text = "Removed from this device draft"
            return
        }
        selectionLabel.text = "Removing clip from FilmCraft timeline…"
        importExecutor.execute {
            val result = runCatching {
                val selected = JSONObject(nativeExecute("timeline.select", JSONObject().put("clips", JSONArray().put(clipId)).toString()))
                if (!selected.optBoolean("ok")) throw IllegalStateException(selected.optString("error", "Could not select timeline clip"))
                val removed = JSONObject(nativeExecute("edit.clear", JSONObject().toString()))
                if (!removed.optBoolean("ok")) throw IllegalStateException(removed.optString("error", "FilmCraft could not remove the clip"))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    forgetMediaLocally(uri, video)
                    selectionLabel.text = "Clip removed from draft"
                }.onFailure { error ->
                    selectionLabel.text = "Could not remove clip · ${error.localizedMessage ?: "FilmCraft error"}"
                }
            }
        }
    }

    private fun confirmRemoveOverlay(uri: Uri) {
        val clip = overlayClips.firstOrNull { it.first == uri } ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove overlay?")
            .setMessage("Remove ${clip.second} from this draft?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                val clipId = timelineClipIds[uri]
                if (clipId == null) {
                    forgetOverlayLocally(uri)
                    return@setPositiveButton
                }
                importExecutor.execute {
                    val result = runCatching {
                        val selected = JSONObject(nativeExecute("timeline.select", JSONObject().put("clips", JSONArray().put(clipId)).toString()))
                        if (!selected.optBoolean("ok")) throw IllegalStateException(selected.optString("error", "Could not select overlay"))
                        val removed = JSONObject(nativeExecute("edit.clear", JSONObject().toString()))
                        if (!removed.optBoolean("ok")) throw IllegalStateException(removed.optString("error", "FilmCraft could not remove the overlay"))
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { forgetOverlayLocally(uri) }
                            .onFailure { error -> selectionLabel.text = "Could not remove overlay · ${error.localizedMessage ?: "FilmCraft error"}" }
                    }
                }
            }.show()
    }

    private fun forgetOverlayLocally(uri: Uri) {
        overlayClips.removeAll { it.first == uri }
        timelineClipIds.remove(uri)
        overlayLoadGeneration++
        overlayPreview.setImageDrawable(null)
        overlayPreview.visibility = View.GONE
        if (selectedClipUri == uri) {
            selectedClipUri = videoClips.firstOrNull()?.first ?: audioClips.firstOrNull()?.first
            activeTimelineClipId = selectedClipUri?.let(timelineClipIds::get)
            selectedClipHasVideo = selectedClipUri?.let { selected -> videoClips.any { it.first == selected } } == true
        }
        persistClips()
        renderClips()
        selectionLabel.text = "Overlay removed from draft"
    }

    private fun forgetMediaLocally(uri: Uri, video: Boolean) {
        val list = if (video) videoClips else audioClips
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
    }

    private fun acceptMedia(uri: Uri, asOverlay: Boolean = false) {
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
            mimeType?.startsWith("image/") == true && asOverlay -> {
                if (overlayClips.any { it.first == uri }) return
                overlayClips.add(uri to name)
                selectedClipUri = uri
                selectedClipHasVideo = true
                showOverlayPreview(uri)
                selectionLabel.text = "Overlay added · $name"
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
        queueNativeImport(uri, name, asOverlay)
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
            refreshNativeTimeline()
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

    private fun initializeEffectCraftSession() {
        val response = runCatching {
            System.loadLibrary("effectcraft_android")
            JSONObject(nativeEffectCraftInitialize(filesDir.absolutePath))
        }.getOrElse { error ->
            Log.i("FilmCraftAndroid", "EffectCraft engine unavailable", error)
            return
        }
        effectCraftSessionReady = response.optBoolean("ok")
        if (!effectCraftSessionReady) {
            Log.w("FilmCraftAndroid", response.optString("error", "Could not open EffectCraft project"))
            return
        }
        runCatching { JSONObject(nativeEffectCraftListCommands()) }.onSuccess { catalog ->
            effectCraftCommandCatalog.clear()
            catalog.optJSONArray("result")?.let { commands ->
                for (index in 0 until commands.length()) commands.optJSONObject(index)?.let(effectCraftCommandCatalog::add)
            }
            Log.i("FilmCraftAndroid", "EffectCraft ready · ${effectCraftCommandCatalog.size} native operations")
        }
        runCatching { JSONObject(nativeEffectCraftListEffects()) }.onSuccess { catalog ->
            effectCraftCatalog.clear()
            catalog.optJSONArray("result")?.let { effects ->
                for (index in 0 until effects.length()) effects.optJSONObject(index)?.let(effectCraftCatalog::add)
            }
        }
    }

    private fun showEffectCraftPanel() {
        if (!effectCraftSessionReady) {
            Toast.makeText(this, "EffectCraft's native engine is not ready in this build", Toast.LENGTH_LONG).show()
            return
        }
        val items = arrayOf("Add solid layer", "Add shape layer", "Add text layer", "EffectCraft effects library", "Add scale-bounce keyframes", "Preview composition", "Save EffectCraft project", "All native commands")
        android.app.AlertDialog.Builder(this).setTitle("EffectCraft · Motion Graphics").setItems(items) { _, which ->
            when (which) {
                0 -> addEffectCraftLayer("layer.newSolid", JSONObject().put("name", "Mobile Solid").put("color", "#9ce849"))
                1 -> addEffectCraftLayer("layer.newShape", JSONObject().put("name", "Mobile Shape").put("kind", "rounded").put("size", JSONArray().put(420).put(420)).put("fill", "#9ce849"))
                2 -> addEffectCraftLayer("layer.newText", JSONObject().put("text", "EffectCraft"))
                3 -> showEffectCraftEffectsBrowser()
                4 -> createEffectCraftBounce()
                5 -> renderEffectCraftComposition()
                6 -> executeEffectCraftCommand("file.save", JSONObject()) { response ->
                    selectionLabel.text = if (response.optBoolean("ok")) "EffectCraft project saved" else response.optString("error", "EffectCraft could not save")
                }
                7 -> showEffectCraftCommandBrowser()
            }
        }.show()
    }

    private fun showEffectCraftCommandBrowser() {
        if (effectCraftCommandCatalog.isEmpty()) {
            Toast.makeText(this, "EffectCraft command list is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(this@MainActivity.background)
        }
        val search = EditText(this).apply {
            hint = "Search commands or parameters"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(surface, 10)
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(46)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, dp(390)))
        fun render() {
            rows.removeAllViews()
            val query = search.text.toString().trim().lowercase()
            val visible = effectCraftCommandCatalog.filter { command ->
                "${command.optString("id")} ${command.optString("label")} ${command.optString("params")}".lowercase().contains(query)
            }
            visible.forEach { command ->
                val id = command.optString("id")
                rows.addView(label("${command.optString("label", id)}  ·  $id", 12f, Color.WHITE, true).apply {
                    setPadding(dp(10), 0, dp(10), 0)
                    background = rounded(surface, 8)
                    setOnClickListener { promptEffectCraftCommand(command) }
                }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) })
            }
            if (visible.isEmpty()) rows.addView(label("No matching commands", 12f, muted))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render()
        android.app.AlertDialog.Builder(this).setTitle("EffectCraft · ${effectCraftCommandCatalog.size} commands")
            .setView(content).setNegativeButton("Done", null).show()
    }

    private fun promptEffectCraftCommand(command: JSONObject) {
        val commandId = command.optString("id")
        val params = EditText(this).apply {
            hint = "JSON parameters"
            setText("{}")
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 6
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(surface, 10)
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(label(command.optString("params", "No parameter guide"), 12f, muted))
            addView(params, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("${command.optString("label", commandId)} · $commandId")
            .setView(details).setNegativeButton("Cancel", null).setPositiveButton("Run", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = runCatching { JSONObject(params.text.toString()) }.getOrNull()
                if (parsed == null) {
                    params.error = "Enter a valid JSON object"
                    return@setOnClickListener
                }
                dialog.dismiss()
                executeEffectCraftCommand(commandId, parsed) { response ->
                    selectionLabel.text = if (response.optBoolean("ok")) "EffectCraft · $commandId completed"
                        else response.optString("error", "$commandId failed")
                }
            }
        }
        dialog.show()
    }

    private fun addEffectCraftLayer(command: String, params: JSONObject) {
        executeEffectCraftCommand(command, params) { response ->
            if (!response.optBoolean("ok")) {
                selectionLabel.text = response.optString("error", "Could not add EffectCraft layer")
                return@executeEffectCraftCommand
            }
            selectedEffectCraftLayerId = response.optJSONObject("result")?.optLong("layer", -1L)?.takeIf { it > 0L }
            selectionLabel.text = "EffectCraft layer added · add effects or transform keys in Motion"
        }
    }

    private fun showEffectCraftEffectsBrowser() {
        val layerId = selectedEffectCraftLayerId
        if (!effectCraftSessionReady || layerId == null || effectCraftCatalog.isEmpty()) {
            Toast.makeText(this, "Add an EffectCraft layer first", Toast.LENGTH_SHORT).show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(this@MainActivity.background)
        }
        val search = EditText(this).apply {
            hint = "Search EffectCraft effects"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(surface, 10)
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(46)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, dp(390)))
        fun render() {
            rows.removeAllViews()
            val query = search.text.toString().trim().lowercase()
            val visible = effectCraftCatalog.filter { effect ->
                "${effect.optString("name")} ${effect.optString("category")} ${effect.optString("id")}".lowercase().contains(query)
            }
            visible.forEach { effect ->
                rows.addView(label("${effect.optString("name")}  ·  ${effect.optString("category")}", 12f, Color.WHITE, true).apply {
                    setPadding(dp(10), 0, dp(10), 0)
                    background = rounded(surface, 8)
                    setOnClickListener {
                        val command = JSONObject().put("effect", effect.optString("id")).put("layers", JSONArray().put(layerId))
                        executeEffectCraftCommand("effect.apply", command) { response ->
                            selectionLabel.text = if (response.optBoolean("ok")) "Applied ${effect.optString("name")} in EffectCraft"
                                else response.optString("error", "Could not apply EffectCraft effect")
                        }
                    }
                }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) })
            }
            if (visible.isEmpty()) rows.addView(label("No matching EffectCraft effects", 12f, muted))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render()
        android.app.AlertDialog.Builder(this).setTitle("EffectCraft · ${effectCraftCatalog.size} effects")
            .setView(content).setNegativeButton("Done", null).show()
    }

    private fun renderEffectCraftComposition() {
        if (!effectCraftSessionReady) return
        selectionLabel.text = "Rendering EffectCraft preview · CPU compositor"
        importExecutor.execute {
            val result = runCatching {
                val bytes = nativeEffectCraftRender()
                if (bytes.size < 8) throw IllegalStateException("EffectCraft returned an empty preview")
                val header = java.nio.ByteBuffer.wrap(bytes, 0, 8).order(java.nio.ByteOrder.BIG_ENDIAN)
                val width = header.int
                val height = header.int
                val count = width.toLong() * height.toLong()
                if (width <= 0 || height <= 0 || width > 720 || height > 720 || count * 4L != bytes.size - 8L) {
                    throw IllegalStateException("EffectCraft returned invalid preview dimensions")
                }
                val pixels = IntArray(count.toInt())
                var offset = 8
                for (index in pixels.indices) {
                    val red = bytes[offset++].toInt() and 0xff
                    val green = bytes[offset++].toInt() and 0xff
                    val blue = bytes[offset++].toInt() and 0xff
                    val alpha = bytes[offset++].toInt() and 0xff
                    pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { bitmap ->
                    previewPlayer?.pause()
                    videoPreview.visibility = View.GONE
                    overlayPreview.visibility = View.GONE
                    emptyPreview.visibility = View.GONE
                    imagePreview.colorFilter = null
                    imagePreview.setImageBitmap(bitmap)
                    imagePreview.visibility = View.VISIBLE
                    selectionLabel.text = "EffectCraft composition preview · 720p CPU render"
                }.onFailure { error ->
                    selectionLabel.text = "EffectCraft preview failed · ${error.localizedMessage ?: "render error"}"
                }
            }
        }
    }

    private fun createEffectCraftBounce() {
        val layerId = selectedEffectCraftLayerId
        if (!effectCraftSessionReady || layerId == null) {
            Toast.makeText(this, "Create or select an EffectCraft layer first", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = listOf(0.0 to 100.0, 0.22 to 124.0, 0.42 to 92.0, 0.68 to 110.0, 0.92 to 100.0)
        importExecutor.execute {
            val result = runCatching {
                keys.forEach { (time, scale) ->
                    val params = JSONObject().put("layer", layerId).put("path", "transform/scale").put("time", time)
                        .put("value", JSONArray().put(scale).put(scale).put(100.0))
                    val response = JSONObject(nativeEffectCraftExecute("prop.addKey", params.toString()))
                    if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Could not animate EffectCraft scale"))
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selectionLabel.text = result.fold({ "EffectCraft scale bounce keyframes saved" },
                    { error -> "EffectCraft animation failed · ${error.localizedMessage ?: "engine error"}" })
            }
        }
    }

    private fun executeEffectCraftCommand(commandId: String, parameters: JSONObject = JSONObject(), onComplete: (JSONObject) -> Unit = {}) {
        if (!effectCraftSessionReady) {
            selectionLabel.text = "EffectCraft engine is not ready"
            return
        }
        importExecutor.execute {
            val result = runCatching { JSONObject(nativeEffectCraftExecute(commandId, parameters.toString())) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(onComplete).onFailure { error ->
                    selectionLabel.text = "EffectCraft $commandId failed · ${error.localizedMessage ?: "engine error"}"
                }
            }
        }
    }

    private fun queueNativeImport(uri: Uri, name: String, asOverlay: Boolean = false) {
        if (!nativeSessionReady) {
            selectionLabel.text = "Added to the Android draft list · Rust core not packaged"
            return
        }
        importExecutor.execute {
            val result = runCatching {
                val localPath = copyIntoAppStorage(uri, name)
                JSONObject(nativeImportMedia(localPath, asOverlay))
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
                                selectedClipHasVideo = videoClips.any { it.first == uri } || overlayClips.any { it.first == uri }
                            }
                            persistClips()
                            refreshNativeTimeline()
                        }
                        val detail = timeline?.optString("error", "timeline unavailable") ?: "timeline unavailable"
                        selectionLabel.text = if (timeline?.optBoolean("ok") == true) {
                            if (asOverlay) "Overlay added to timeline · drag, pinch, or rotate it" else "Added to FilmCraft timeline · $name"
                        } else "Imported to project bin · $detail"
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
        val clipId = activeTimelineClipId ?: selectedClipUri?.let(timelineClipIds::get)
        if (!selectedClipHasVideo || clipId == null) {
            Toast.makeText(this, "Select a video or photo clip first", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(this@MainActivity.background)
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
        overlayPreview.visibility = View.GONE
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

    private fun showOverlayPreview(uri: Uri) {
        val image = overlayClips.firstOrNull { it.first == uri } ?: return
        overlayLoadGeneration++
        val request = overlayLoadGeneration
        overlayPreview.setImageDrawable(null)
        overlayPreview.visibility = View.VISIBLE
        val transformKey = overlayTransformKey(uri)
        val transformPrefs = getSharedPreferences("mobile_project", MODE_PRIVATE)
        overlayPreview.translationX = transformPrefs.getFloat("$transformKey.x", 0f)
        overlayPreview.translationY = transformPrefs.getFloat("$transformKey.y", 0f)
        overlayPreview.rotation = transformPrefs.getFloat("$transformKey.rotation", 0f)
        val savedScale = transformPrefs.getFloat("$transformKey.scale", 1f).coerceIn(0.2f, 4f)
        overlayPreview.scaleX = savedScale
        overlayPreview.scaleY = savedScale
        imagePreviewExecutor.execute {
            val bitmap = runCatching { decodePreviewBitmap(uri) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || request != overlayLoadGeneration) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    selectionLabel.text = "Overlay image could not be opened · ${image.second}"
                    return@runOnUiThread
                }
                overlayPreview.setImageBitmap(bitmap)
                val canvasWidth = previewFrame.width.coerceAtLeast(dp(150)).toFloat()
                val canvasHeight = previewFrame.height.coerceAtLeast(dp(110)).toFloat()
                val fit = minOf(canvasWidth * 0.72f / bitmap.width, canvasHeight * 0.72f / bitmap.height)
                overlayPreview.layoutParams = FrameLayout.LayoutParams(
                    (bitmap.width * fit).toInt().coerceAtLeast(dp(48)),
                    (bitmap.height * fit).toInt().coerceAtLeast(dp(48)), Gravity.CENTER
                )
            }
        }
    }

    private fun overlayTransformKey(uri: Uri): String = "overlay_transform_${uri.toString().hashCode()}"

    private fun handleOverlayTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    gestureStartDistance = pointerDistance(event).coerceAtLeast(1f)
                    gestureStartAngle = pointerAngle(event)
                    gestureStartRotation = view.rotation
                    gestureStartScale = view.scaleX
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastTouchX = event.getX(remaining)
                    lastTouchY = event.getY(remaining)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = pointerDistance(event)
                    val angle = pointerAngle(event)
                    val delta = ((angle - gestureStartAngle + 540f) % 360f) - 180f
                    val scale = (gestureStartScale * distance / gestureStartDistance).coerceIn(0.2f, 4f)
                    view.scaleX = scale
                    view.scaleY = scale
                    view.rotation = gestureStartRotation + delta
                } else {
                    view.translationX += event.x - lastTouchX
                    view.translationY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                commitOverlayTransform(view)
                return true
            }
        }
        return true
    }

    private fun pointerDistance(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun pointerAngle(event: MotionEvent): Float = Math.toDegrees(
        kotlin.math.atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())
    ).toFloat()

    private fun commitOverlayTransform(view: View) {
        val clip = activeTimelineClipId ?: return
        if (!nativeSessionReady) return
        val width = previewFrame.width.toFloat().coerceAtLeast(1f)
        val height = previewFrame.height.toFloat().coerceAtLeast(1f)
        val canvasWidth = 1920.0
        val canvasHeight = canvasWidth * height / width
        val position = JSONArray().put(canvasWidth / 2.0 + view.translationX * canvasWidth / width)
            .put(canvasHeight / 2.0 + view.translationY * canvasHeight / height)
        val time = playheadTicks()
        val scale = view.scaleX * 100.0
        val rotation = view.rotation.toDouble()
        val transformKey = overlayTransformKey(selectedClipUri ?: return)
        getSharedPreferences("mobile_project", MODE_PRIVATE).edit()
            .putFloat("$transformKey.x", view.translationX).putFloat("$transformKey.y", view.translationY)
            .putFloat("$transformKey.scale", view.scaleX).putFloat("$transformKey.rotation", view.rotation).apply()
        selectionLabel.text = "Saving overlay transform…"
        importExecutor.execute {
            val result = runCatching {
                val params = listOf(
                    "position" to position,
                    "scale" to scale,
                    "rotation" to rotation,
                )
                params.forEach { (name, value) ->
                    val args = JSONObject().put("clip", clip).put("effect", "motion").put("param", name)
                        .put("value", value).put("time", time)
                    val response = JSONObject(nativeExecute("effects.setParam", args.toString()))
                    if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Could not update $name"))
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selectionLabel.text = result.fold({ "Overlay transform saved · keyframe it to animate" },
                    { error -> "Transform could not be saved · ${error.localizedMessage ?: "FilmCraft error"}" })
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
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlaybackPosition()
                }
            })
            videoPreview.player = player
        }
    }

    private fun showVideo(uri: Uri, autoPlay: Boolean = true, startPositionMs: Long = 0L) {
        imageLoadGeneration++
        selectedClipUri = uri
        activeTimelineClipId = timelineClipIds[uri]
        selectedClipHasVideo = true
        overlayPreview.visibility = View.GONE
        if (activeVideo != uri) applyPreviewEffectForClip()
        activeVideo = uri
        imagePreview.visibility = View.GONE
        emptyPreview.visibility = View.GONE
        videoPreview.visibility = View.VISIBLE
        previewPlayer?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            if (startPositionMs > 0L) seekTo(startPositionMs)
            if (autoPlay) play() else pause()
        }
    }

    private fun persistClips() {
        val prefs = getSharedPreferences("mobile_project", MODE_PRIVATE)
        fun encode(items: List<Pair<Uri, String>>) = JSONArray().apply {
            items.forEach { (uri, name) ->
                put(JSONArray().put(uri.toString()).put(name).put(timelineClipIds[uri] ?: JSONObject.NULL))
            }
        }.toString()
        prefs.edit().putString("video_clips", encode(videoClips)).putString("overlay_clips", encode(overlayClips))
            .putString("audio_clips", encode(audioClips)).apply()
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
        decode("overlay_clips", overlayClips)
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

    private external fun nativeEffectCraftInitialize(dataDir: String): String
    private external fun nativeEffectCraftListCommands(): String
    private external fun nativeEffectCraftListEffects(): String
    private external fun nativeEffectCraftRender(): ByteArray
    private external fun nativeEffectCraftExecute(commandId: String, parametersJson: String): String
    private external fun nativeInitialize(dataDir: String): String
    private external fun nativeImportMedia(path: String, overlay: Boolean): String
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
