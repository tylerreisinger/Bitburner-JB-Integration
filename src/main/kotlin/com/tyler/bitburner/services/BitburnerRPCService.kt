package com.tyler.bitburner.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.tyler.bitburner.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

@Service(Service.Level.PROJECT)
class BitburnerRPCService(proj: Project) : Disposable {
    private var logger = logger<BitburnerRPCService>()
    private var _server: WebSocketServer? = null
    private var _proj = proj

    companion object {
        const val DISPOSE_WS_CLOSE_MS: Int = 500

        fun getInstance(project: Project): BitburnerRPCService {
            return project.getService(BitburnerRPCService::class.java)
        }
    }

    /**
     * Get the underlying WebSocketServer object.
     *
     * This is where event handlers can be defined and can provide introspection into connected clients.
     * If the server is not started, this will return null.
     */
    val server: WebSocketServer?
        get() = _server

    /**
     * Start the server on the given [port].
     *
     * This will start listening on the port, and start all internal coroutines needed to maintain a WebSocket
     * connection and handshake.
     */
    fun start(port: Int) {
        _server = WebSocketServer(IntelliJLogDispatcher())
        _server?.start(port)
        registerEventListeners()
    }

    /**
     * Stop the server, returning a [kotlinx.coroutines.Job]? representing the job handling graceful shutdown.
     */
    fun stop(): Job? = _server?.stop()

    /**
     * Write a text frame to the client.
     */
    fun write(str: String) {
        _server?.client?.write(str)
    }

    /**
     * Dispose the service and underlying server if it is running. This will delay up to [DISPOSE_WS_CLOSE_MS] ms to
     * wait for a graceful close, after which it will cancel all remaining jobs.
     */
    override fun dispose() {
        logger.info("Disposing BitburnerRPCService")
        val startTime = System.currentTimeMillis()
        val job = _server?.stop()
        // We will wait for a very short period to send a close signal and potentially get a reply.
        while (System.currentTimeMillis() - startTime < DISPOSE_WS_CLOSE_MS) {
            if (job == null || !job.isActive) {
                break
            }
            Thread.sleep(5)
        }
        server?.coroutineScope?.cancel("Server shutdown")
        logger.info(
            "${WebSocketServer::class.simpleName}.dispose took ${System.currentTimeMillis() - startTime} ms")
    }

    private fun contentFrameReceived(event: FrameEvent) {
        var logStr = "Received content frame: ${event.frame.header}"
        if (event.frame.length > 0) {
            val body = event.frame.textContent
            if (body != null) {
                logStr += "\n\t$body"
            }
        }
        logger.info(logStr)
    }

    private fun controlFrameReceived(event: FrameEvent) {
        var logStr = "Received control frame: ${event.frame.header}"
        if (event.frame.length > 0) {
            val body = event.frame.textContent
            if (body != null) {
                if (event.frame.header.opCode == OpCode.Close) {
                    logStr += "\n\tExit code: ${event.frame.closeStatusCode}. '$body'"
                }
            }
        }
        logger.info(logStr)
    }

    private fun webSocketNegotiated(event: ConnectionEvent) {
        val name = "${event.client.socket.inetAddress}:${event.client.socket.port}"
        logger.info("Client '$name' successfully upgraded to a WebSocket connection")
    }

    private fun connectionClosing(event: ConnectionCloseEvent) {
        var logStr = "'${event.client.addressStr}' is closed or closing."
        if (event.statusCode != null) {
            logStr += " Code ${event.statusCode}"
        }
        if (event.msg != null) {
            logStr += "\n\"${event.msg}\""
        }
        logger.info(logStr)
    }

    private fun connectionClosedGracefully(event: ConnectionCloseEvent) {
        var logStr = "'${event.client.addressStr}' closed gracefully"
        if (event.statusCode != null) {
            logStr += " Code ${event.statusCode}"
        }
        if (event.msg != null) {
            logStr += "\n\"${event.msg}\""
        }
        logger.info(logStr)
    }

    private fun frameSent(event: FrameEvent) {
        logger.info("Frame sent: ${event.frame.header}")
    }

    private fun registerEventListeners() {
        server?.contentFrameReceived?.register(this::contentFrameReceived)
        server?.controlFrameReceived?.register(this::controlFrameReceived)
        server?.webSocketNegotiated?.register(this::webSocketNegotiated)
        server?.connectionClosing?.register(this::connectionClosing)
        server?.connectionClosedGracefully?.register(this::connectionClosedGracefully)
        server?.frameSent?.register(this::frameSent)
    }
}

class IntelliJLogDispatcher : LogDispatcher {
    @PublishedApi
    internal val logger = Logger.getInstance(WebSocketServer::class.java)

    override fun trace(msg: String) {
        logger.trace(msg)
    }
    override fun trace(t: Throwable) {
        logger.trace(t)
    }
    override fun debug(msg: String) {
        logger.debug(msg)
    }
    override fun debug(t: Throwable) {
        logger.debug(t)
    }
    override fun info(msg: String) {
        logger.info(msg)
    }
    override fun info(t: Throwable) {
        logger.info(t)
    }
    override fun warn(msg: String) {
        logger.warn(msg)
    }
    override fun warn(t: Throwable) {
        logger.warn(t)
    }

    override fun error(throwable: Throwable?) {
        logger.error(throwable)
    }
    override fun error(msg: String, throwable: Throwable?) {
        if (throwable != null) {
            logger.error(msg, throwable)
        } else {
            logger.error(msg)
        }
    }

}
