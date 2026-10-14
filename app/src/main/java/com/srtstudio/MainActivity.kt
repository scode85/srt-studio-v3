package com.srtstudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private lateinit var etKey: EditText
    private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnPick: Button
    private lateinit var btnGo: Button
    private lateinit var spinnerLang: Spinner
    private lateinit var progress: ProgressBar

    private val pool = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val activity get() = this

    private val langs = listOf(
        "auto" to "Tự động",
        "vi" to "Tiếng Việt",
        "en" to "English",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "th" to "ไทย",
        "id" to "Indonesia"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etKey       = findViewById(R.id.etKey)
        tvInfo      = findViewById(R.id.tvInfo)
        tvLog       = findViewById(R.id.tvLog)
        btnPick     = findViewById(R.id.btnPick)
        btnGo       = findViewById(R.id.btnGo)
        spinnerLang = findViewById(R.id.spinnerLang)
        progress    = findViewById(R.id.progress)

        val prefs = getSharedPreferences("srt", Context.MODE_PRIVATE)
        etKey.setText(prefs.getString("groq_key", ""))

        spinnerLang.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            langs.map { it.second })

        btnPick.setOnClickListener { pickVideo() }
        btnGo.setOnClickListener { startTranscribe() }
    }

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "video/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(i, REQ)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ && res == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            videoUri = uri
            tvInfo.text = "Video: " + uri.lastPathSegment
            btnGo.isEnabled = true
            progress.visibility = View.GONE
            tvLog.text = "Sẵn sàng. Nhập API key rồi bấm Tạo phụ đề."
        }
    }

    private fun startTranscribe() {
        val uri = videoUri ?: return
        val key = etKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Nhập API key Groq", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("srt", Context.MODE_PRIVATE)
            .edit().putString("groq_key", key).apply()

        val pos = spinnerLang.selectedItemPosition
        val lang = if (pos in langs.indices) langs[pos].first else "auto"

        btnGo.isEnabled = false
        btnPick.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        tvLog.text = "Bắt đầu..."

        pool.execute {
            try { runTranscribe(uri, key, lang) }
            catch (e: Exception) {
                log("❌ Lỗi: ${e.message}")
                finishRun()
            }
        }
    }

    private fun runTranscribe(uri: Uri, key: String, lang: String) {
        val cache = File(cacheDir, "srt_audio").apply { mkdirs() }
        val wav = File(cache, "audio.wav")

        log("1/4. Tách audio từ video...")
        setProgress(5)
        val info = AudioExtractor.extractWav(activity, uri, wav)
        if (info == null) { log("❌ Video không có track âm thanh."); finishRun(); return }
        log("   ✓ WAV ${wav.length()/1024/1024} MB, ${info.durationMs/1000}s, ${info.sampleRate} Hz")
        setProgress(15)

        log("2/4. Chia nhỏ audio...")
        val chunks = AudioExtractor.splitWav(wav, cache, info.sampleRate, 240)
        log("   ✓ ${chunks.size} chunk (240s/chunk)")
        setProgress(20)

        log("3/4. Gửi lên Groq Whisper...")
        SrtWriter.reset()
        var i = 0
        for ((cf, offsetMs) in chunks) {
            i++
            log("   [${i}/${chunks.size}] ${cf.name} (${cf.length()/1024/1024} MB)")
            var retry = 0
            while (true) {
                try {
                    val segs = GroqClient.transcribe(key, cf, lang)
                    for (s in segs) SrtWriter.add(
                        s.startMs + offsetMs, s.endMs + offsetMs, s.text)
                    log("   ✓ ${segs.size} segments")
                    break
                } catch (e: Exception) {
                    retry++
                    if (retry >= 3) throw e
                    log("   ⚠ ${e.message} — retry $retry/3")
                    Thread.sleep(3000L * retry)
                }
            }
            setProgress(20 + i * 70 / chunks.size)
        }

        log("4/4. Lưu SRT...")
        val name = baseName(uri.lastPathSegment ?: "output")
        val outDir = File(activity.getExternalFilesDir(null), "subtitles").apply { mkdirs() }
        val outFile = File(outDir, "$name.srt")
        val count = SrtWriter.save(outFile)
        setProgress(100)
        log("\n✅ Xong: $count dòng")
        log("📄 ${outFile.absolutePath}")
        ui.post {
            Toast.makeText(activity,
                "Đã lưu: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
        finishRun()
    }

    private fun baseName(s: String): String {
        var n = s
        val slash = n.lastIndexOf('/'); if (slash >= 0) n = n.substring(slash + 1)
        val dot = n.lastIndexOf('.');   if (dot > 0) n = n.substring(0, dot)
        return if (n.isBlank()) "output" else n
    }

    private fun log(msg: String) { ui.post { tvLog.append("\n$msg") } }
    private fun setProgress(p: Int) { ui.post { progress.progress = p } }
    private fun finishRun() { ui.post { btnGo.isEnabled = true; btnPick.isEnabled = true } }

    companion object { const val REQ = 101 }
}
