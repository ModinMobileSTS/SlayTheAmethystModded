package io.stamethyst.ui.settings

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleMode
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import kotlin.math.roundToInt

internal object AtlasDownscaleImportDialog {
    fun show(
        host: Activity,
        previews: List<ModImportAtlasDownscalePreview>,
        onApply: (AtlasOfflineDownscaleStrategy) -> Unit,
        onSkip: () -> Unit,
        onCancel: () -> Unit
    ) {
        val density = host.resources.displayMetrics.density
        val outerPadding = (20f * density).roundToInt()
        val sectionSpacing = (16f * density).roundToInt()
        val itemSpacing = (8f * density).roundToInt()
        val selectedStrategy = arrayOf(
            AtlasOfflineDownscaleStrategy.maxEdge(AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX)
        )

        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
        }
        val scrollView = ScrollView(host).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        content.addView(
            TextView(host).apply {
                text = SettingsFileService.buildAtlasDownscaleImportConfirmationMessage(host, previews)
            }
        )

        content.addView(
            sectionTitleView(host, R.string.mod_import_atlas_downscale_confirm_strategy_title).apply {
                setPadding(0, sectionSpacing, 0, itemSpacing)
            }
        )

        val strategyGroup = RadioGroup(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        val percentageButton = RadioButton(host).apply {
            id = View.generateViewId()
            text = host.getString(R.string.mod_import_atlas_downscale_strategy_percentage)
        }
        val maxEdgeButton = RadioButton(host).apply {
            id = View.generateViewId()
            text = host.getString(R.string.mod_import_atlas_downscale_strategy_max_edge)
            isChecked = true
        }
        strategyGroup.addView(percentageButton)
        strategyGroup.addView(maxEdgeButton)
        content.addView(strategyGroup)

        content.addView(
            sectionTitleView(host, R.string.mod_import_atlas_downscale_confirm_level_title).apply {
                setPadding(0, sectionSpacing, 0, itemSpacing)
            }
        )

        val levelGroup = RadioGroup(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(levelGroup)

        val descriptionView = TextView(host).apply {
            setPadding(0, itemSpacing, 0, 0)
        }
        content.addView(descriptionView)

        fun updateDescription() {
            descriptionView.text = when (selectedStrategy[0].mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE -> host.getString(
                    R.string.mod_import_atlas_downscale_level_percent_desc,
                    selectedStrategy[0].value,
                    AtlasOfflineDownscaleStrategy.CANDIDATE_PREVIEW_MAX_EDGE_PX
                )

                AtlasOfflineDownscaleMode.MAX_EDGE -> host.getString(
                    R.string.mod_import_atlas_downscale_level_max_edge_desc,
                    selectedStrategy[0].value
                )
            }
        }

        fun rebuildLevelOptions(mode: AtlasOfflineDownscaleMode) {
            levelGroup.setOnCheckedChangeListener(null)
            levelGroup.removeAllViews()
            val options = when (mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE ->
                    AtlasOfflineDownscaleStrategy.percentageOptions().toList()

                AtlasOfflineDownscaleMode.MAX_EDGE ->
                    AtlasOfflineDownscaleStrategy.maxEdgeOptions().toList()
            }
            val defaultStrategy = when (mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE ->
                    AtlasOfflineDownscaleStrategy.percentage(AtlasOfflineDownscaleStrategy.DEFAULT_PERCENTAGE)

                AtlasOfflineDownscaleMode.MAX_EDGE ->
                    AtlasOfflineDownscaleStrategy.maxEdge(AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX)
            }
            val currentValue = selectedStrategy[0]
                .takeIf { it.mode == mode && options.contains(it.value) }
                ?.value
                ?: defaultStrategy.value
            options.forEach { option ->
                levelGroup.addView(
                    RadioButton(host).apply {
                        id = View.generateViewId()
                        tag = option
                        text = when (mode) {
                            AtlasOfflineDownscaleMode.PERCENTAGE ->
                                host.getString(
                                    R.string.mod_import_atlas_downscale_level_percent_label,
                                    option
                                )

                            AtlasOfflineDownscaleMode.MAX_EDGE ->
                                host.getString(
                                    R.string.mod_import_atlas_downscale_level_max_edge_label,
                                    option
                                )
                        }
                        isChecked = option == currentValue
                    }
                )
            }
            selectedStrategy[0] = when (mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE ->
                    AtlasOfflineDownscaleStrategy.percentage(currentValue)

                AtlasOfflineDownscaleMode.MAX_EDGE ->
                    AtlasOfflineDownscaleStrategy.maxEdge(currentValue)
            }
            levelGroup.setOnCheckedChangeListener { group, checkedId ->
                val selectedValue = group.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                selectedStrategy[0] = when (mode) {
                    AtlasOfflineDownscaleMode.PERCENTAGE ->
                        AtlasOfflineDownscaleStrategy.percentage(selectedValue)

                    AtlasOfflineDownscaleMode.MAX_EDGE ->
                        AtlasOfflineDownscaleStrategy.maxEdge(selectedValue)
                }
                updateDescription()
            }
            updateDescription()
        }

        strategyGroup.setOnCheckedChangeListener { _, checkedId ->
            rebuildLevelOptions(
                if (checkedId == percentageButton.id) {
                    AtlasOfflineDownscaleMode.PERCENTAGE
                } else {
                    AtlasOfflineDownscaleMode.MAX_EDGE
                }
            )
        }
        rebuildLevelOptions(AtlasOfflineDownscaleMode.MAX_EDGE)

        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_atlas_downscale_confirm_title)
            .setView(scrollView)
            .setNegativeButton(R.string.mod_import_dialog_duplicate_cancel) { _, _ ->
                onCancel()
            }
            .setNeutralButton(R.string.mod_import_dialog_atlas_downscale_confirm_skip) { _, _ ->
                onSkip()
            }
            .setPositiveButton(R.string.mod_import_dialog_atlas_downscale_confirm_apply) { _, _ ->
                onApply(selectedStrategy[0])
            }
            .setOnCancelListener {
                onCancel()
            }
            .show()
    }

    private fun sectionTitleView(host: Activity, titleResId: Int): TextView {
        return TextView(host).apply {
            text = host.getString(titleResId)
            setTypeface(typeface, Typeface.BOLD)
        }
    }
}
