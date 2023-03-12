package com.tyler.bitburner.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class BitburnerConfigurable(project: Project): Configurable, Disposable {
    private var _ui: BitburnerSettingsUi? = null
    private var _proj = project

    val ui get() = _ui!!

    override fun createComponent(): JComponent {
        if (_ui == null) {
            _ui = BitburnerSettingsUi()
        }
        return _ui!!.getPanel()
    }

    override fun isModified(): Boolean {
        return ui.isModifiedFrom(BitburnerStateComponent.getInstance(_proj).state)
    }

    override fun apply() {
        ui.applySettings(BitburnerStateComponent.getInstance(_proj).state)
    }

    override fun reset() {
        ui.resetFromSettings(BitburnerStateComponent.getInstance(_proj).state)
        super.reset()
    }

    override fun getDisplayName(): String {
        return "Bitburner Integration Configuration"
    }

    override fun dispose() {
        _ui = null
    }
}