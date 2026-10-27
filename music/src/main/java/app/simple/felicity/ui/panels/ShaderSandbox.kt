package app.simple.felicity.ui.panels

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.simple.felicity.R
import app.simple.felicity.databinding.FragmentShaderSandboxBinding
import app.simple.felicity.decorations.views.PopupMenuItem
import app.simple.felicity.decorations.views.SharedScrollViewPopup
import app.simple.felicity.decorations.visualizer.ShaderSandboxView
import app.simple.felicity.engine.managers.VisualizerManager
import app.simple.felicity.extensions.fragments.MediaFragment
import kotlinx.coroutines.launch

/**
 * GLSL Shader Sandbox Panel.
 *
 * Hosts [ShaderSandboxView] with dynamic camera modes (Orbit mode turntable,
 * Beat-Cam transient zoom/shake, and Gyroscope sensor control) and allows users
 * to write, paste, and load custom audio-reactive GLSL fragment shaders in real-time.
 *
 * @author Hamza417
 */
class ShaderSandbox : MediaFragment() {

    private lateinit var binding: FragmentShaderSandboxBinding
    private var currentPreset = 0

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentShaderSandboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireLightBarIcons()
        requireHiddenMiniPlayer()

        setupButtons()
        observeAudioSpectrum()
    }

    private fun observeAudioSpectrum() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VisualizerManager.spectrumFlow.collect { bands ->
                    if (bands.isNotEmpty()) {
                        var bass = 0f
                        var mid = 0f
                        var treble = 0f
                        for (i in 0 until bands.size.coerceAtMost(8)) bass += bands[i]
                        for (i in 8 until bands.size.coerceAtMost(24)) mid += bands[i]
                        for (i in 24 until bands.size) treble += bands[i]
                        binding.shaderSurface.setAudioLevels(
                                (bass / 8f).coerceIn(0f, 1f),
                                (mid / 16f).coerceIn(0f, 1f),
                                (treble / 16f).coerceIn(0f, 1f)
                        )
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnPreset.setOnClickListener { anchor ->
            SharedScrollViewPopup(
                    container = requireContainerView(),
                    anchorView = anchor,
                    menuItems = listOf(
                            PopupMenuItem(title = "Cosmic Pulse"),
                            PopupMenuItem(title = "Cyber Tunnel"),
                            PopupMenuItem(title = "Quantum Lattice")
                    ),
                    onMenuItemClick = { index ->
                        when (index) {
                            0 -> {
                                binding.shaderSurface.setShaderCode(ShaderSandboxView.DEFAULT_COSMIC_SHADER)
                            }
                            1 -> {
                                binding.shaderSurface.setShaderCode(ShaderSandboxView.NEON_TUNNEL_SHADER)
                            }
                            2 -> {
                                binding.shaderSurface.setShaderCode(ShaderSandboxView.QUANTUM_LATTICE_SHADER)
                            }
                        }
                    },
                    onDismiss = {}
            ).show()
        }

        binding.toggleOrbit.setOnClickListener {
            binding.shaderSurface.orbitModeEnabled = !binding.shaderSurface.orbitModeEnabled
            binding.toggleOrbit.text = if (binding.shaderSurface.orbitModeEnabled) "Orbit: ON" else "Orbit: OFF"
        }

        binding.toggleBeatcam.setOnClickListener {
            binding.shaderSurface.beatCamEnabled = !binding.shaderSurface.beatCamEnabled
            binding.toggleBeatcam.text = if (binding.shaderSurface.beatCamEnabled) "Beat-Cam: ON" else "Beat-Cam: OFF"
        }

        binding.toggleGyro.setOnClickListener {
            binding.shaderSurface.gyroscopeEnabled = !binding.shaderSurface.gyroscopeEnabled
            binding.toggleGyro.text = if (binding.shaderSurface.gyroscopeEnabled) "Gyro: ON" else "Gyro: OFF"
        }

        binding.btnEditCode.setOnClickListener {
            showShaderEditorDialog()
        }
    }

    private fun showShaderEditorDialog() {
        val input = EditText(requireContext()).apply {
            setPadding(40, 30, 40, 30)
            hint = "Paste custom GLSL Fragment Shader code..."
            setText(ShaderSandboxView.DEFAULT_COSMIC_SHADER.trimIndent())
            textSize = 12f
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shader_sandbox)
            .setMessage("Reacts to: u_time, u_bass, u_mid, u_treble, u_resolution, u_touch, u_gyro, u_cam_pos")
            .setView(input)
            .setPositiveButton("Compile") { _, _ ->
                val code = input.text.toString()
                if (code.isNotBlank()) {
                    val error = binding.shaderSurface.setShaderCode(code)
                    if (error != null) {
                        Toast.makeText(requireContext(), "Compile error: $error", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "Shader loaded successfully!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Paste Clipboard") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                if (!clip.isNullOrBlank()) {
                    binding.shaderSurface.setShaderCode(clip)
                    Toast.makeText(requireContext(), "Pasted & Compiled shader", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.shaderSurface.onResume()
    }

    override fun onPause() {
        binding.shaderSurface.onPause()
        super.onPause()
    }

    companion object {
        const val TAG = "ShaderSandbox"

        fun newInstance(): ShaderSandbox {
            return ShaderSandbox().apply {
                arguments = Bundle()
            }
        }
    }
}
