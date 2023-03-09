package com.tyler.bitburner.ui

import com.intellij.openapi.util.IconLoader

object BBIcons {
    val BITBURNER = loadIcon("/icons/bitburner.svg")

    private fun loadIcon(path: String) = IconLoader.getIcon(path, BBIcons::class.java)
}