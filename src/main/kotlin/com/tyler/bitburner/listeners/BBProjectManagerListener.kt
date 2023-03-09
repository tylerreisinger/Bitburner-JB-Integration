package com.tyler.bitburner.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.tyler.bitburner.services.BitburnerRPCService
import com.tyler.bitburner.settings.BitburnerStateComponent

internal class BBProjectManagerListener : ProjectManagerListener {
    override fun projectOpened(project: Project) {
        val config = BitburnerStateComponent.getInstance(project)

        if (config.state.isEnabled) {
            BitburnerRPCService.getInstance(project).start(8080)
        }

        super.projectOpened(project)
    }
}