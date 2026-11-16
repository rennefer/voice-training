package app.mp3splitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mp3splitter.core.ExportedPart
import app.mp3splitter.core.Exporter
import app.mp3splitter.core.IN_RATE
import app.mp3splitter.core.PartEncoder
import app.mp3splitter.core.Quality
import app.mp3splitter.core.SplitPlan
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    // top row
    private lateinit var openButton: ImageButton
    private lateinit var newRecButton: View
    private lateinit var deleteButton: ImageButton
    private lateinit var status: TextView
    // recorder
    private lateinit var recPanel: View
    private lateinit var dial: LevelDial
    private lateinit var recLabel: View
    private lateinit var timer: TextView
    private lateinit var recInfo: TextView
    private lateinit var recordButton: View
    private lateinit var recordGlyph: View
    private lateinit var recordHint: View
    // editor
    private lateinit var editor: View
    private lateinit var selLength: TextView
    private lateinit var ofTotal: TextView
    private lateinit var waveform: WaveformView
    private lateinit var startTime: TextView
    private lateinit var endTime: TextView
    private lateinit var playButton: ImageButton
    private lateinit var qualityBar: TabBar
    private lateinit var splitBar: TabBar
    private lateinit var customLayout: View
    private lateinit var customMb: EditText
    private lateinit var evenOut: MaterialSwitch
    private lateinit var estimate: TextView
    private lateinit var exportButton: Button
    private lateinit var progress: LinearProgressIndicator
    private lateinit var results: LinearLayout
    private lateinit var shareAll: TextView

    private var player: Player? = null
    private var exportJob: Job? = null
    private var importJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)
    private var savedUris: List<Uri> = emptyList()
    private var exportedThisRecording = false
    private var wasRecording = false
    private var hasRecording = false

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }

    private val qualities = Quality.values().toList()              // 32 · 64 · 96 · 128 kbps
    private val splitMb = listOf<Double?>(null, 5.0, 10.0, 25.0, -1.0) // Whole · 5 · 10 · 25 · Custom
    private val customIndex = 4

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmThenImport(uri)
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) startRecording()
        else status("Microphone permission is needed to record.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(Brass.Background())
        setContentView(R.layout.activity_main)
        bindViews()
        style()
        applyInsets()
        addTabs()

        recordButton.setOnClickListener { onRecordButton() }
        newRecButton.setOnClickListener { onNewRecording() }
        deleteButton.setOnClickListener { onDeleteRecording() }
        openButton.setOnClickListener {
            if (busy()) return@setOnClickListener
            try { openFile.launch(arrayOf("audio/*")) } catch (_: Exception) { status("No file picker found") }
        }
        playButton.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.startMinus).setOnClickListener { nudge(start = true, seconds = -1) }
        findViewById<View>(R.id.startPlus).setOnClickListener { nudge(start = true, seconds = 1) }
        findViewById<View>(R.id.endMinus).setOnClickListener { nudge(start = false, seconds = -1) }
        findViewById<View>(R.id.endPlus).setOnClickListener { nudge(start = false, seconds = 1) }
        waveform.onSelectionChanged = { _, _ -> updateSelectionText(); updateEstimate() }

        // choices, restored from last time
        qualityBar.setOptions(qualities.map { it.kbps.toString() })
        qualityBar.select(prefs.getInt("qualityIdx", Quality.VOICE.ordinal).coerceIn(0, qualities.size - 1))
        splitBar.setOptions(listOf("Whole", "5", "10", "25", "Custom"), listOf(1.2f, 1f, 1f, 1f, 1.4f))
        splitBar.select(prefs.getInt("splitIdx", 1).coerceIn(0, splitMb.size - 1))
        customMb.setText(prefs.getString("customMb", "8"))
        evenOut.isChecked = prefs.getBoolean("evenOut", false)
        customLayout.visibility = if (splitBar.selected == customIndex) View.VISIBLE else View.GONE

        qualityBar.onSelect = { saveSettings(); updateEstimate() }
        splitBar.onSelect = { i ->
            customLayout.visibility = if (i == customIndex) View.VISIBLE else View.GONE
            saveSettings(); updateEstimate()
        }
        customMb.doAfterTextChanged { saveSettings(); updateEstimate() }
        evenOut.setOnCheckedChangeListener { _, _ -> saveSettings() }
        exportButton.setOnClickListener { if (exportJob?.isActive == true) cancelFlag.set(true) else startExport() }
        shareAll.setOnClickListener { Saver.share(this, savedUris) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecordService.recording.collect { onRecordingState(it) } }
                launch {
                    RecordService.samples.collect {
                        if (RecordService.recording.value) {
                            timer.text = clock(it)
                            recInfo.text = "≈ " + mb(SplitPlan.estimateBytes(it, quality().kbps))
                        }
                    }
                }
                launch { RecordService.level.collect { if (RecordService.recording.value) dial.setLevel(it) } }
                launch { RecordService.error.collect { if (it != null) { status(it); RecordService.clearError() } } }
            }
        }

        showScreen()
        if (!RecordService.recording.value && RecordService.pcmFile(this).length() > 0) loadRecording()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun bindViews() {
        openButton = findViewById(R.id.openButton)
        newRecButton = findViewById(R.id.newRecButton)
        deleteButton = findViewById(R.id.deleteButton)
        status = findViewById(R.id.status)
        recPanel = findViewById(R.id.recPanel)
        dial = findViewById(R.id.dial)
        recLabel = findViewById(R.id.recLabel)
        timer = findViewById(R.id.timer)
        recInfo = findViewById(R.id.recInfo)
        recordButton = findViewById(R.id.recordButton)
        recordGlyph = findViewById(R.id.recordGlyph)
        recordHint = findViewById(R.id.recordHint)
        editor = findViewById(R.id.editor)
        selLength = findViewById(R.id.selLength)
        ofTotal = findViewById(R.id.ofTotal)
        waveform = findViewById(R.id.waveform)
        startTime = findViewById(R.id.startTime)
        endTime = findViewById(R.id.endTime)
        playButton = findViewById(R.id.playButton)
        qualityBar = findViewById(R.id.qualityBar)
        splitBar = findViewById(R.id.splitBar)
        customLayout = findViewById(R.id.customLayout)
        customMb = findViewById(R.id.customMb)
        evenOut = findViewById(R.id.evenOut)
        estimate = findViewById(R.id.estimate)
        exportButton = findViewById(R.id.exportButton)
        progress = findViewById(R.id.exportProgress)
        results = findViewById(R.id.results)
        shareAll = findViewById(R.id.shareAll)
    }

    /** Record | Voice switch under the title row; Voice opens the voice analyser. */
    private fun addTabs() {
        val content = findViewById<LinearLayout>(R.id.content)
        val tabs = TabBar(this).apply { setOptions(listOf("Record", "Voice")); select(0) }
        tabs.onSelect = { i ->
            if (i == 1) {
                if (RecordService.recording.value || exportJob?.isActive == true) { tabs.select(0); status("Finish recording first") }
                else {
                    startActivity(Intent(this, app.mp3splitter.voice.VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    overridePendingTransition(0, 0)
                }
            }
        }
        this.tabs = tabs
        content.addView(tabs, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() })
    }

    private var tabs: TabBar? = null

    override fun onResume() {
        super.onResume()
        tabs?.select(0)
    }

    /** Brass and frosted backgrounds (gradients with several stops are easier in code). */
    private fun style() {
        openButton.background = Brass.panel(0f, oval = true)
        deleteButton.background = Brass.panel(0f, oval = true)
        newRecButton.background = Brass.metal(0f, oval = true)
        findViewById<View>(R.id.newRecDot).background = circle(Brass.REC_RED)
        findViewById<View>(R.id.recLabelDot).background = circle(0xFFD9463E.toInt())
        recordButton.background = Brass.metal(0f, oval = true)
        playButton.background = Brass.panel(0f, oval = true)
        findViewById<View>(R.id.wavePanel).background = Brass.panel(16 * dp)
        findViewById<View>(R.id.evenRow).background = Brass.panel(16 * dp)
        exportButton.background = Brass.metal(16 * dp)
        setRecordGlyph(recording = false)
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Red dot = record, dark square = stop. */
    private fun setRecordGlyph(recording: Boolean) {
        recordGlyph.background = GradientDrawable().apply {
            if (recording) { cornerRadius = 5 * dp; setColor(Brass.ON_BRASS) }
            else { shape = GradientDrawable.OVAL; setColor(Brass.REC_RED) }
        }
        recordButton.contentDescription = if (recording) "Stop recording" else "Record"
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.content)
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
    }

    /** Recorder screen while recording or when there is nothing to edit; editor otherwise. */
    private fun showScreen() {
        val rec = RecordService.recording.value
        val edit = hasRecording && !rec
        recPanel.visibility = if (edit) View.GONE else View.VISIBLE
        editor.visibility = if (edit) View.VISIBLE else View.GONE
        newRecButton.visibility = if (edit) View.VISIBLE else View.GONE
        deleteButton.visibility = if (edit) View.VISIBLE else View.GONE
        openButton.visibility = if (rec) View.GONE else View.VISIBLE
        recLabel.visibility = if (rec) View.VISIBLE else View.INVISIBLE
        recordHint.visibility = if (rec) View.VISIBLE else View.INVISIBLE
        setRecordGlyph(rec)
        if (!rec) {
            dial.reset()
            if (!edit) { timer.text = clock(0); recInfo.text = "" }
        }
    }

    // ---------- recording ----------

    private fun onRecordButton() {
        if (RecordService.recording.value) { RecordService.stop(this); return }
        if (busy()) return
        requestAndRecord()
    }

    private fun onNewRecording() {
        if (busy()) return
        if (hasRecording && !exportedThisRecording) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Replace the current recording? It hasn't been saved as MP3 yet.")
                .setPositiveButton("Replace") { _, _ -> requestAndRecord() }
                .setNegativeButton("Keep", null)
                .show()
        } else requestAndRecord()
    }

    private fun onDeleteRecording() {
        if (busy() || !hasRecording) return
        val msg = if (savedUris.isNotEmpty())
            "Delete this recording? The MP3s already saved in Music/${Saver.FOLDER} are kept."
        else "Delete this recording? It hasn't been saved as MP3."
        MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> deleteRecording() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRecording() {
        player?.stop()
        player = null
        RecordService.pcmFile(this).delete()
        prefs.edit().remove("sourceName").apply()
        hasRecording = false
        exportedThisRecording = false
        waveform.peaks = null
        waveform.totalSamples = 0
        clearResults()
        showScreen()
        status("Recording deleted")
    }

    private fun requestAndRecord() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRecording() else askPermissions.launch(needed.toTypedArray())
    }

    private fun startRecording() {
        player?.stop()
        player = null
        status(null)
        clearResults()
        prefs.edit().remove("sourceName").apply()
        RecordService.start(this)
    }

    private fun onRecordingState(rec: Boolean) {
        if (rec) {
            hasRecording = false
            timer.text = clock(0)
            recInfo.text = "≈ " + mb(0)
        }
        showScreen()
        if (wasRecording && !rec) loadRecording()
        wasRecording = rec
    }

    private fun loadRecording() {
        val file = RecordService.pcmFile(this)
        lifecycleScope.launch {
            val enc = PartEncoder(file)
            val (peaks, total) = withContext(Dispatchers.IO) { enc.peaks(1500) to enc.totalSamples }
            if (total < IN_RATE / 10) {
                hasRecording = false
                showScreen()
                status("Recording was too short.")
                return@launch
            }
            player = Player(file)
            waveform.totalSamples = total
            waveform.peaks = peaks
            waveform.setSelection(0, total)
            ofTotal.text = "of " + clock(total)
            exportedThisRecording = false
            hasRecording = true
            clearResults()
            showScreen()
            updateEstimate()
        }
    }

    // ---------- import (call recordings, any audio file) ----------

    /** "Share → Recorder" from My Files / Voice Recorder, or "Open with". */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> streamExtra(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            intent?.action = null // don't import again on rotation
            confirmThenImport(uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun busy() = RecordService.recording.value || exportJob?.isActive == true || importJob?.isActive == true

    private fun confirmThenImport(uri: Uri) {
        if (busy()) { status("Finish the current recording or export first"); return }
        if (hasRecording && !exportedThisRecording) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Replace the current recording? It hasn't been saved as MP3 yet.")
                .setPositiveButton("Replace") { _, _ -> importAudio(uri) }
                .setNegativeButton("Keep", null)
                .show()
        } else importAudio(uri)
    }

    private fun importAudio(uri: Uri) {
        player?.stop()
        player = null
        val name = AudioImporter.displayName(this, uri)
        cancelFlag.set(false)
        setControlsEnabled(false)
        status("Opening ${name ?: "file"}…")
        importJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AudioImporter.import(this@MainActivity, uri, RecordService.pcmFile(this@MainActivity),
                        onProgress = { f -> status.post { status.text = "Opening ${name ?: "file"}… ${(f * 100).toInt()} %" } },
                        isCancelled = { cancelFlag.get() })
                }
                prefs.edit().putString("sourceName", name?.let { cleanName(it) }).apply()
                status(null)
                loadRecording()
            } catch (e: CancellationException) {
                status("Cancelled")
            } catch (e: Exception) {
                status("Could not open the file: ${e.message}")
            } finally {
                setControlsEnabled(true)
            }
        }
    }

    /** "Call recording Mamma_241108_231005.m4a" -> "Call_recording_Mamma_241108_231005" */
    private fun cleanName(file: String): String? {
        val base = file.substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(60)
        return base.ifEmpty { null }
    }

    // ---------- trim + playback ----------

    private fun nudge(start: Boolean, seconds: Int) {
        if (!waveform.isEnabled) return
        val d = seconds.toLong() * IN_RATE
        if (start) waveform.setSelection(waveform.selStart + d, waveform.selEnd)
        else waveform.setSelection(waveform.selStart, waveform.selEnd + d)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) { p.stop(); return }
        playButton.setImageResource(R.drawable.ic_pause)
        playButton.contentDescription = "Pause"
        p.play(waveform.selStart, waveform.selEnd,
            onPosition = { pos -> runOnUiThread { waveform.playhead = pos } },
            onDone = {
                runOnUiThread {
                    waveform.playhead = -1
                    playButton.setImageResource(R.drawable.ic_play)
                    playButton.contentDescription = "Play selection"
                }
            })
    }

    private fun updateSelectionText() {
        startTime.text = clock(waveform.selStart, tenths = true)
        endTime.text = clock(waveform.selEnd, tenths = true)
        selLength.text = clock(waveform.selEnd - waveform.selStart)
    }

    // ---------- settings + estimate ----------

    private fun quality(): Quality = qualities[qualityBar.selected]

    /** null = one file; throws if the custom size is not usable. */
    private fun limitBytes(): Long? {
        val mb = splitMb[splitBar.selected] ?: return null
        val value = if (mb < 0) customMb.text.toString().replace(',', '.').toDoubleOrNull()
            ?: throw IllegalArgumentException("Type a size in MB") else mb
        require(value >= 0.2) { "Use at least 0.2 MB" }
        return (value * 1_000_000).toLong()
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("qualityIdx", qualityBar.selected)
            .putInt("splitIdx", splitBar.selected)
            .putString("customMb", customMb.text.toString())
            .putBoolean("evenOut", evenOut.isChecked)
            .apply()
    }

    private fun updateEstimate() {
        if (!hasRecording || exportJob?.isActive == true) return
        val q = quality()
        try {
            val parts = SplitPlan.plan(waveform.selStart, waveform.selEnd, q.kbps, limitBytes())
            val sizes = parts.map { SplitPlan.estimateBytes(it.samples, q.kbps) }
            estimate.text = when {
                parts.size == 1 -> "≈ " + mb(sizes[0])
                parts.size <= 4 -> sizes.joinToString("  ·  ") { mb(it) }
                else -> "${parts.size} parts  ·  ${mb(sizes[0])} each"
            }
            exportButton.text = if (parts.size == 1) "Make MP3" else "Make ${parts.size} MP3s"
            exportButton.isEnabled = true
            exportButton.alpha = 1f
        } catch (e: IllegalArgumentException) {
            estimate.text = e.message ?: "Part size too small"
            exportButton.isEnabled = false
            exportButton.alpha = 0.5f
        }
    }

    // ---------- export ----------

    private fun startExport() {
        val limit = try { limitBytes() } catch (e: IllegalArgumentException) { status(e.message); return }
        player?.stop()
        val q = quality()
        val even = evenOut.isChecked
        val start = waveform.selStart
        val end = waveform.selEnd
        val pcm = RecordService.pcmFile(this)
        val recStart = pcm.lastModified() - waveform.totalSamples * 1000 / IN_RATE
        val base = prefs.getString("sourceName", null)
            ?: ("rec_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(recStart)))
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)

        clearResults()
        cancelFlag.set(false)
        setExporting(true)
        status(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        exportJob = lifecycleScope.launch {
            try {
                val parts = withContext(Dispatchers.Default) {
                    Exporter(pcm, File(cacheDir, "export")).run(start, end, q, even, limit, base, threads,
                        onProgress = { f -> progress.post { progress.setProgressCompat((f * 100).toInt(), true) } },
                        isCancelled = { cancelFlag.get() })
                }
                val uris = withContext(Dispatchers.IO) { parts.map { Saver.saveToMusic(this@MainActivity, it.file) } }
                savedUris = uris
                exportedThisRecording = true
                showResults(parts, uris)
                status("Saved in Music/${Saver.FOLDER}")
            } catch (e: CancellationException) {
                status("Cancelled")
            } catch (e: Exception) {
                status("Export failed: ${e.message}")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setExporting(false)
            }
        }
    }

    private fun setExporting(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) progress.setProgressCompat(0, false)
        setControlsEnabled(!on)
        exportButton.isEnabled = true
        if (on) { exportButton.text = "Cancel"; exportButton.alpha = 1f } else updateEstimate()
    }

    private fun setControlsEnabled(on: Boolean) {
        recordButton.isEnabled = on
        newRecButton.isEnabled = on
        deleteButton.isEnabled = on
        openButton.isEnabled = on
        waveform.isEnabled = on
        qualityBar.isEnabled = on
        splitBar.isEnabled = on
        evenOut.isEnabled = on
        customMb.isEnabled = on
        exportButton.isEnabled = on
    }

    private fun showResults(parts: List<ExportedPart>, uris: List<Uri>) {
        results.removeAllViews()
        parts.forEachIndexed { i, p ->
            val row = layoutInflater.inflate(R.layout.item_part, results, false)
            row.background = Brass.panel(14 * dp)
            row.findViewById<TextView>(R.id.partName).text = p.file.name
            row.findViewById<TextView>(R.id.partInfo).text = "${mb(p.bytes)} · ${clock(p.samples)}"
            row.findViewById<View>(R.id.partShare).setOnClickListener { Saver.share(this, listOf(uris[i])) }
            row.findViewById<View>(R.id.partDelete).setOnClickListener { confirmDeletePart(row, p.file.name, uris[i]) }
            results.addView(row)
        }
        shareAll.visibility = if (parts.size > 1) View.VISIBLE else View.GONE
    }

    private fun confirmDeletePart(row: View, name: String, uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setMessage("Delete $name from Music/${Saver.FOLDER}?")
            .setPositiveButton("Delete") { _, _ ->
                if (Saver.delete(this, uri)) {
                    results.removeView(row)
                    savedUris = savedUris - uri
                    if (savedUris.size < 2) shareAll.visibility = View.GONE
                    status("Deleted $name")
                } else status("Could not delete $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearResults() {
        results.removeAllViews()
        shareAll.visibility = View.GONE
        savedUris = emptyList()
    }

    private fun status(msg: String?) {
        status.text = msg ?: ""
        status.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        player?.stop()
        cancelFlag.set(true)
        super.onDestroy()
    }

    // ---------- formatting ----------

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

    private fun clock(samples: Long, tenths: Boolean = false): String {
        val totalTenths = samples * 10 / IN_RATE
        val t = totalTenths % 10
        val s = (totalTenths / 10) % 60
        val m = (totalTenths / 600) % 60
        val h = totalTenths / 36000
        val main = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
        return if (tenths) "$main.$t" else main
    }
}
