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
    private val styles = listOf("Glass Blur", "OLED Dark", "Cosmic Violet", "3D Neon Horizon", "Minimalist Editorial")
    private var currentTypefaceIndex = 0
    private val typefaceStyles = listOf("Sans Bold", "Elegant Serif", "Cyber Mono", "Minimal Sans")
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
        binding.styleToggle.text = "Backdrop: ${styles[currentStyleIndex]}"
        binding.styleToggle.setOnClickListener {
            currentStyleIndex = (currentStyleIndex + 1) % styles.size
            binding.styleToggle.text = "Backdrop: ${styles[currentStyleIndex]}"
            renderCard()
        }

        binding.typographyToggle.text = "Font: ${typefaceStyles[currentTypefaceIndex]}"
        binding.typographyToggle.setOnClickListener {
            currentTypefaceIndex = (currentTypefaceIndex + 1) % typefaceStyles.size
            binding.typographyToggle.text = "Font: ${typefaceStyles[currentTypefaceIndex]}"
            renderCard()
        }

        binding.selectStanzaButton.setOnClickListener {
            val currentPos = MediaPlaybackManager.songSeekPositionFlow.value
            val lrc = LyricsManager.activeLrcDataFlow.value
            val entries = lrc?.entries ?: emptyList()
            if (entries.isNotEmpty()) {
                val activeIdx = entries.indexOfLast { it.timeInMillis <= currentPos }.coerceAtLeast(0)
                val start = activeIdx.coerceIn(0, (allLines.size - 3).coerceAtLeast(0))
                selectedLines.clear()
                selectedLines.addAll(allLines.drop(start).take(3))
                binding.linesRecycler.adapter?.notifyDataSetChanged()
                renderCard()
                Toast.makeText(requireContext(), "Selected current stanza", Toast.LENGTH_SHORT).show()
            }
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

            val isLightEditorial = currentStyleIndex == 4
            val primaryTextColor = if (isLightEditorial) 0xFF18181B.toInt() else Color.WHITE
            val secondaryTextColor = if (isLightEditorial) 0xFF52525B.toInt() else 0xAAFFFFFF.toInt()
            val quoteColor = if (isLightEditorial) 0x3318181B.toInt() else 0x44FFFFFF.toInt()
            val borderColor = if (isLightEditorial) 0x3318181B.toInt() else 0x26FFFFFF.toInt()

            val selectedTypeface = when (currentTypefaceIndex) {
                0 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                1 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                2 -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                3 -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                else -> Typeface.DEFAULT_BOLD
            }

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
                    val overlay = Paint().apply {
                        shader = LinearGradient(
                                0f, 0f, 0f, height.toFloat(),
                                intArrayOf(0xCC0D0D11.toInt(), 0xF00D0D11.toInt()),
                                null, Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
                }
                1 -> { // OLED Pure Dark
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
                3 -> { // 3D Neon Horizon (Perspective Grid)
                    val skyGradient = Paint().apply {
                        shader = LinearGradient(
                                0f, 0f, 0f, height.toFloat(),
                                intArrayOf(0xFF0F0426.toInt(), 0xFF35063D.toInt(), 0xFF120321.toInt()),
                                floatArrayOf(0f, 0.65f, 1f),
                                Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyGradient)

                    // 3D Perspective Grid
                    val horizonY = height * 0.65f
                    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x33FF007F.toInt()
                        strokeWidth = 2f
                    }
                    // Converging perspective lines
                    val vanishingX = width / 2f
                    for (gx in -200..width.toInt() + 200 step 80) {
                        canvas.drawLine(vanishingX, horizonY, gx.toFloat(), height.toFloat(), gridPaint)
                    }
                    // Horizontal depth slices
                    for (step in 1..8) {
                        val fraction = (step.toFloat() / 8f) * (step.toFloat() / 8f)
                        val gy = horizonY + (height - horizonY) * fraction
                        canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
                    }
                }
                4 -> { // Minimalist Editorial Ivory
                    canvas.drawColor(0xFFF5F2EB.toInt())
                    val paperBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x11000000.toInt()
                        strokeWidth = 2f
                        style = Paint.Style.STROKE
                    }
                    canvas.drawRect(40f, 40f, (width - 40).toFloat(), (height - 40).toFloat(), paperBorder)
                }
            }

            // Draw Card Container
            val cardRect = RectF(80f, 100f, (width - 80).toFloat(), (height - 120).toFloat())
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(cardRect, 48f, 48f, cardPaint)

            // Draw Quote Marks
            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = quoteColor
                textSize = 140f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("“", 140f, 260f, quotePaint)

            // Draw Lyrics with Selected Typography
            val lyricsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryTextColor
                textSize = if (lines.size <= 2) 58f else 46f
                typeface = selectedTypeface
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
                color = primaryTextColor
                textSize = 38f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(song.getProperTitle(), 140f, (height - 240).toFloat(), titlePaint)

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryTextColor
                textSize = 30f
                typeface = Typeface.DEFAULT
            }
            canvas.drawText(song.getProperArtists(), 140f, (height - 185).toFloat(), artistPaint)

            // Soul Watermark / Branding
            val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryTextColor
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
