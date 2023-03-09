package com.tyler.bitburner.extensions

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor

class BBProjectOpenedProcessor : ProjectOpenProcessor() {
    override fun getName(): String {
        return "Bitburner"
    }

    override fun canOpenProject(file: VirtualFile): Boolean {
        return file.extension == ".js" || file.extension == ".kt"
    }

    override fun doOpenProject(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean
    ): Project? {
        val basedir = if (virtualFile.isDirectory) virtualFile else virtualFile.parent

        return PlatformProjectOpenProcessor.getInstance().doOpenProject(basedir, projectToClose, forceOpenInNewFrame)?.also {
                StartupManager.getInstance(it).runWhenProjectIsInitialized {
                    projectInitialized()
                }
        }
    }

    private fun projectInitialized() {
    }
}