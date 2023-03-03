package com.tyler.bitburner.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

class BitburnerSettingsUi {
    // {{ UI Components
    private var isEnabledBox = JBCheckBox("Enable for project?")
    // UI Components }}
    private var panelUi = init()

    private fun init(): JPanel {
        val form = FormBuilder.createFormBuilder()
            .addComponent(this.isEnabledBox)
            .panel
        return form
    }

    fun getPanel(): JPanel {
        return panelUi
    }

    fun isModifiedFrom(settings: BitburnerStateComponent.BitburnerState): Boolean {
        return settings.isEnabled != isEnabledBox.isSelected
    }

    fun applySettings(settings: BitburnerStateComponent.BitburnerState) {
        settings.isEnabled = isEnabledBox.isSelected
    }

    fun resetFromSettings(settings: BitburnerStateComponent.BitburnerState) {
        isEnabledBox.isSelected = settings.isEnabled
    }
}