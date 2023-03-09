package com.tyler.bitburner.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(
    name = "BitburnerStateComponent",
    storages = [Storage("bitburner-plugin.xml")]
)
@Service(Service.Level.PROJECT)
class BitburnerStateComponent: PersistentStateComponent<BitburnerStateComponent.BitburnerState> {
    companion object {
        fun getInstance(project: Project): BitburnerStateComponent {
            return project.getService(BitburnerStateComponent::class.java)
        }
    }

    private var _state = BitburnerState()

   override fun getState(): BitburnerState {
        return _state
    }

    override fun loadState(state: BitburnerState) {
        _state = state
    }

    class BitburnerState {
        var isEnabled = false
    }
}