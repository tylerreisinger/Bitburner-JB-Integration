package com.tyler.bitburner.websocket

import java.net.Socket

enum class StandardEventCategory : EventCategory {
    ConnectionAttempt,
    ConnectionAccepted,
    WebSocketNegotiated,
    ConnectionClosing,
    ConnectionClosedGracefully,
    ContentFrameReceived,
    ControlFrameReceived,
    ContinuationFrameReceived,
    FrameSent,
}

fun interface EventListener<C: EventCategory, E: Event<C>> : (E) -> Unit {
    override operator fun invoke(event: E)
}

interface EventCategory {
    val name: String
    val ordinal: Int
}

interface Event<C: EventCategory> {
    val category: C
    val server: WebSocketServer
}

interface LogDispatcher {
    fun trace(msg: String)
    fun trace(t: Throwable)
    fun debug(msg: String)
    fun debug(t: Throwable)
    fun info(msg: String)
    fun info(t: Throwable)
    fun warn(msg: String)
    fun warn(t: Throwable)
    fun error(throwable: Throwable?)
    fun error(msg: String, throwable: Throwable? = null)
}

class EventDispatcher<C: EventCategory, E: Event<C>, F: EventListener<C, E>>(val category: EventCategory) {
    private val _listeners: MutableList<F> = mutableListOf()

    @Suppress("MemberVisibilityCanBePrivate")
    val listeners: List<F>
        get() = _listeners

    /**
     * Invoke all registered [EventListener]s in invocation order.
     */
    operator fun invoke(event: E) =
        listeners.forEach { it.invoke(event) }

    fun clear() {
        _listeners.clear()
    }

    fun register(listener: F) {
        _listeners.add(listener)
    }
}

data class ConnectionAttemptEvent(override val category: StandardEventCategory,
    override val server: WebSocketServer, val socket: Socket) : Event<StandardEventCategory> {

    var doAccept: Boolean = true
}

data class ConnectionEvent(override val category: StandardEventCategory,
                           override val server: WebSocketServer, val client: Client) : Event<StandardEventCategory>
data class FrameEvent(override val category: StandardEventCategory, override val server: WebSocketServer,
                      val client: Client, val frame: WebSocketFrame) : Event<StandardEventCategory>
data class ConnectionCloseEvent(override val category: StandardEventCategory, override val server: WebSocketServer,
                                val client: Client, val statusCode: Int?,
                                val msg: String?) : Event<StandardEventCategory>
