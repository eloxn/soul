package app.simple.felicity.ui.subpanels

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.simple.felicity.R
import app.simple.felicity.adapters.ui.lists.AdapterEqualizerPresets
import app.simple.felicity.databinding.FragmentEqualizerPresetsBinding
import app.simple.felicity.databinding.HeaderEqualizerPresetsBinding
import app.simple.felicity.decorations.views.AppHeader
import app.simple.felicity.decorations.views.PopupMenuItem
import app.simple.felicity.decorations.views.SharedScrollViewPopup
import app.simple.felicity.engine.managers.AutoEqParser
import app.simple.felicity.engine.managers.EqualizerManager
import app.simple.felicity.extensions.fragments.MediaFragment
import app.simple.felicity.preferences.EqualizerPreferences
import app.simple.felicity.repository.models.EqualizerPreset
import app.simple.felicity.viewmodels.dialogs.EqualizerPresetsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A full-screen panel that lists all saved equalizer presets — both the ones bundled
 * with the app and any the user has created themselves.
 *
 * This is modeled after [LyricsSearch] so it fits naturally into the existing panel
 * navigation stack: tapping the Presets button in the EQ screen pushes this fragment
 * onto the back stack, and pressing back pops it off. No bottom-sheets, no dialogs
 * floating over dialogs — just clean, predictable navigation.
 *
 * Tapping a preset applies it immediately and returns to the EQ screen so the user
 * can see the sliders snap to the preset curve in real time.
 *
 * @author Hamza417
 */
class EqualizerPresets : MediaFragment() {

    private lateinit var binding: FragmentEqualizerPresetsBinding
    private lateinit var headerBinding: HeaderEqualizerPresetsBinding

    private val viewModel: EqualizerPresetsViewModel by viewModels()
    private var adapter: AdapterEqualizerPresets? = null

    private val importAutoEqLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importAutoEqFromUri(uri)
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentEqualizerPresetsBinding.inflate(inflater, container, false)
        headerBinding = HeaderEqualizerPresetsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireHiddenMiniPlayer()

        // Plug the header into the AppHeader so it behaves like every other panel header —
        // it scrolls away as the user scrolls down the preset list.
        binding.appHeader.setContentView(headerBinding.root)
        binding.appHeader.attachTo(binding.recyclerView, AppHeader.ScrollMode.HIDE_ON_SCROLL)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        headerBinding.importAutoeq.setOnClickListener {
            importAutoEqLauncher.launch(arrayOf("text/*", "*/*"))
        }

        setupAdapter()
        observePresets()
    }

    private fun importAutoEqFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                var fileName = "AutoEQ Preset"
                requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex).substringBeforeLast(".")
                    }
                }

                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val profile = inputStream?.use { AutoEqParser.parse(it, fileName) }

                withContext(Dispatchers.Main) {
                    if (profile != null && isAdded) {
                        viewModel.savePeqPreset(profile.name, profile.bands, profile.preampDb)
                        Toast.makeText(
                                requireContext(),
                                getString(R.string.autoeq_import_success, profile.name, profile.bands.size),
                                Toast.LENGTH_SHORT
                        ).show()
                    } else if (isAdded) {
                        Toast.makeText(requireContext(), R.string.autoeq_import_error, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), R.string.autoeq_import_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        adapter = null
        super.onDestroyView()
    }

    /**
     * Wires up the adapter and hands it the two interaction callbacks.
     * The adapter is created once and recycled across list updates.
     */
    private fun setupAdapter() {
        adapter = AdapterEqualizerPresets(
                onPresetClicked = ::applyPreset,
                onOptionClicked = ::showPresetOptions
        )
        binding.recyclerView.adapter = adapter
    }

    /**
     * Starts collecting the live preset list from the database and feeds each update
     * straight into the adapter. Room handles the heavy lifting — we just watch.
     */
    private fun observePresets() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.presets.collect { presets ->
                    adapter?.submitList(presets)
                }
            }
        }
    }

    /**
     * Applies [preset] to the live audio engine and switches the EQ mode to match the
     * preset type, then navigates back so the user can immediately see the changes.
     *
     * For graphic presets: all 10 band gains and the preamp are written to preferences
     * and pushed to the audio engine. For parametric presets: the PEQ bands are stored
     * in preferences and the EQ mode is flipped to parametric. Either way, the mode
     * switch is persisted so the equalizer screen opens in the right mode on the way back.
     *
     * @param preset The preset to apply.
     */
    private fun applyPreset(preset: EqualizerPreset) {
        if (preset.isPeq()) {
            // Switch to parametric mode and store the bands in preferences so the EQ
            // screen can load them the moment it becomes visible again.
            EqualizerManager.setEqMode(EqualizerPreferences.EQ_MODE_PARAMETRIC)
            EqualizerPreferences.setPeqBandsRaw(preset.peqBandsRaw)
            // Push the PEQ bands into the audio engine and update the flow so the
            // EQ screen's knobs snap to the preset values when it comes back into view.
            EqualizerManager.setPeqBands(preset.getPeqBands())
            EqualizerManager.setPreamp(preset.preampDb)
        } else {
            val gains = preset.getBandGains()

            // Write all gains to SharedPreferences first, then push them to the audio engine.
            // Doing it in this order means the values are safe even if the app is killed mid-update.
            EqualizerManager.setEqMode(EqualizerPreferences.EQ_MODE_GRAPHIC)
            EqualizerPreferences.setAllBandGains(gains)
            for (i in gains.indices) {
                EqualizerManager.setBandGain(i, gains[i], persist = false)
            }
            EqualizerManager.setPreamp(preset.preampDb)
        }

        goBack()
    }

    /**
     * Shows a [SharedScrollViewPopup] anchored to [anchorView] with context actions for
     * the given [preset]. "Apply" is always present; "Delete" is only offered for
     * presets that the user created (not the built-in factory ones).
     *
     * @param preset     The preset the user long-pressed.
     * @param anchorView The view that was long-pressed — used as the popup anchor.
     */
    private fun showPresetOptions(preset: EqualizerPreset, anchorView: View) {
        SharedScrollViewPopup(
                container = requireContainerView(),
                anchorView = anchorView,
                menuItems = listOf(
                        PopupMenuItem(title = R.string.apply_preset),
                        PopupMenuItem(title = R.string.delete),
                ),
                onMenuItemClick = {
                    val effect = when (it) {
                        R.string.apply_preset -> applyPreset(preset)
                        R.string.delete -> confirmAndDelete(preset)
                        else -> return@SharedScrollViewPopup
                    }
                },
                onDismiss = {}
        ).show()
    }

    /**
     * Shows a [withSureDialog] confirmation before permanently deleting [preset].
     * The dialog lives in the parent panel rather than a nested bottom-sheet, which
     * keeps the interaction hierarchy clean and simple.
     *
     * @param preset The user-created preset to delete.
     */
    private fun confirmAndDelete(preset: EqualizerPreset) {
        withSureDialog { confirmed ->
            if (confirmed) {
                viewModel.deletePreset(preset)
            }
        }
    }

    companion object {

        const val TAG = "EqualizerPresets"

        /**
         * Creates a fresh instance of [EqualizerPresets] ready to be pushed onto the
         * fragment back stack via [openFragment].
         */
        fun newInstance(): EqualizerPresets {
            return EqualizerPresets().apply {
                arguments = Bundle()
            }
        }
    }
}