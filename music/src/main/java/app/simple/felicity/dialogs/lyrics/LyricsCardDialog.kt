package app.simple.felicity.dialogs.lyrics

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.simple.felicity.databinding.DialogLyricsCardBinding
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.dialogs.MediaBottomDialogFragment
import app.simple.felicity.glide.util.AudioCoverUtils.loadPlainArtCover
import app.simple.felicity.managers.LyricsManager
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Dialog allowing users to select 2 to 4 lines of lyrics, pick card typography/style,
 * generate a clean high-resolution social sharing image with album art backdrop,
 * and share directly to social media / messenger apps.
 *
 * @author Hamza417
 */
class LyricsCardDialog : MediaBottomDialogFragment() {

    private lateinit var binding: DialogLyricsCardBinding
    private var audio: Audio? = null
    private val selectedLines = mutableListOf<String>()
    private var allLines = listOf<String>()
    private var currentStyleIndex = 0
    private val styles = listOf("Glass Blur", "OLED Dark", "Cosmic Violet")
    private var coverBitmap: Bitmap? = null
    private var generatedCardBitmap: Bitmap? = null

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = DialogLyricsCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audio = MediaPlaybackManager.getCurrentSong()
        loadCoverArt()
        setupLines()
        setupButtons()
    }

    private fun loadCoverArt() {
        val song = audio ?: return
        requireContext().loadPlainArtCover(song.uri) { bmp ->
            coverBitmap = bmp
            renderCard()
        }
    }

    private fun setupLines() {
        val lrc = LyricsManager.activeLrcDataFlow.value
        allLines = lrc?.entries?.map { it.text }?.filter { it.isNotBlank() } ?: emptyList()

        if (allLines.isNotEmpty()) {
            // Pre-select first 2-3 lines by default
            selectedLines.clear()
            selectedLines.addAll(allLines.take(3))
        }

        binding.linesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.linesRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    setPadding(32, 20, 32, 20)
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val line = allLines[position]
                val tv = holder.itemView as TextView
                val isSelected = selectedLines.contains(line)
                tv.text = if (isSelected) "✓  $line" else "   $line"
                tv.setBackgroundColor(if (isSelected) 0x33FFFFFF else Color.TRANSPARENT)
                tv.setOnClickListener {
                    if (selectedLines.contains(line)) {
                        if (selectedLines.size > 1) selectedLines.remove(line)
                    } else {
                        if (selectedLines.size < 4) {
                            selectedLines.add(line)
                        } else {
                            Toast.makeText(requireContext(), "Maximum 4 lines", Toast.LENGTH_SHORT).show()
                        }
                    }
                    notifyDataSetChanged()
                    renderCard()
                }
            }

            override fun getItemCount(): Int = allLines.size
        }

        renderCard()
    }

    private fun setupButtons() {
        binding.styleToggle.text = "Style: ${styles[currentStyleIndex]}"
        binding.styleToggle.setOnClickListener {
            currentStyleIndex = (currentStyleIndex + 1) % styles.size
            binding.styleToggle.text = "Style: ${styles[currentStyleIndex]}"
            renderCard()
        }

        binding.shareButton.setOnClickListener {
            val bmp = generatedCardBitmap
            if (bmp != null) {
                shareCardBitmap(bmp)
            } else {
                Toast.makeText(requireContext(), "Rendering card...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderCard() {
        val song = audio ?: return
        val lines = if (selectedLines.isNotEmpty()) selectedLines else listOf("♪ Soul Music Player")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val width = 1080
            val height = 1350
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw Background according to current style
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (currentStyleIndex) {
                0 -> { // Glass Blur with artwork
                    if (coverBitmap != null) {
                        val scaledCover = Bitmap.createScaledBitmap(coverBitmap!!, width, height, true)
                        canvas.drawBitmap(scaledCover, 0f, 0f, bgPaint)
                    } else {
                        canvas.drawColor(0xFF1E1E24.toInt())
                    }
                    // Dark Frosted Overlay
                    val overlay = Paint().apply {
                        shader = LinearGradient(
                                0f, 0f, 0f, height.toFloat(),
                                intArrayOf(0xCC0D0D11.toInt(), 0xF00D0D11.toInt()),
                                null, Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
                }
                1 -> { // OLED Dark
                    canvas.drawColor(0xFF000000.toInt())
                    val accentGradient = Paint().apply {
                        shader = LinearGradient(
                                0f, 0f, width.toFloat(), height.toFloat(),
                                intArrayOf(0x22FFFFFF.toInt(), 0x05FFFFFF.toInt()),
                                null, Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), accentGradient)
                }
                2 -> { // Cosmic Violet
                    val gradient = Paint().apply {
                        shader = LinearGradient(
                                0f, 0f, width.toFloat(), height.toFloat(),
                                intArrayOf(0xFF1E0826.toInt(), 0xFF0B061A.toInt()),
                                null, Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradient)
                }
            }

            // Draw Card Container
            val cardRect = RectF(80f, 100f, (width - 80).toFloat(), (height - 120).toFloat())
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x26FFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(cardRect, 48f, 48f, cardPaint)

            // Draw Quote Marks
            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x44FFFFFF.toInt()
                textSize = 140f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("“", 140f, 260f, quotePaint)

            // Draw Lyrics
            val lyricsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = if (lines.size <= 2) 58f else 46f
                typeface = Typeface.DEFAULT_BOLD
            }

            val lyricsBlock = lines.joinToString("\n\n")
            val staticLayout = StaticLayout.Builder.obtain(
                    lyricsBlock, 0, lyricsBlock.length, lyricsPaint, width - 280
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

            canvas.save()
            canvas.translate(140f, 320f)
            staticLayout.draw(canvas)
            canvas.restore()

            // Draw Track & Artist Information
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 38f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(song.getProperTitle(), 140f, (height - 240).toFloat(), titlePaint)

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAAFFFFFF.toInt()
                textSize = 30f
                typeface = Typeface.DEFAULT
            }
            canvas.drawText(song.getProperArtists(), 140f, (height - 185).toFloat(), artistPaint)

            // Soul Watermark / Branding
            val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x88FFFFFF.toInt()
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("SOUL MUSIC", (width - 140).toFloat(), (height - 185).toFloat(), badgePaint)

            generatedCardBitmap = bitmap
            withContext(Dispatchers.Main) {
                binding.cardPreview.setImageBitmap(bitmap)
            }
        }
    }

    private fun shareCardBitmap(bitmap: Bitmap) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(requireContext().cacheDir, "shared_cards").apply { mkdirs() }
                val file = File(cacheDir, "soul_lyrics_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val authority = "${requireContext().packageName}.provider"
                val uri = FileProvider.getUriForFile(requireContext(), authority, file)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "“${selectedLines.firstOrNull() ?: ""}” — ${audio?.getProperTitle()}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(intent, "Share Lyrics Card"))
                    dismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to export card: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val TAG = "LyricsCardDialog"

        fun FragmentManager.showLyricsCard(): LyricsCardDialog {
            val dialog = LyricsCardDialog()
            dialog.show(this, TAG)
            return dialog
        }
    }
}
