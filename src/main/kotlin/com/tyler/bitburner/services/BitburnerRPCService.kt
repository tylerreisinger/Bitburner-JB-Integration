package com.tyler.bitburner.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.tyler.bitburner.websocket.WebSocketServer

@Service(Service.Level.PROJECT)
class BitburnerRPCService(proj: Project) : Disposable {
    private var _server: WebSocketServer? = null
    private var _proj = proj

    val server: WebSocketServer?
        get() = _server

    companion object {
        fun getInstance(project: Project): BitburnerRPCService {
            return project.getService(BitburnerRPCService::class.java)
        }
    }

    fun start(port: Int) {
        _server = WebSocketServer()
        _server?.start(port)
    }

    fun stop() = _server?.stop()

    fun write(str: String) {
        _server?.client?.write(str)
    }

    override fun dispose() {
        val job = _server?.stop()

    }
}