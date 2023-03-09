package com.tyler.bitburner.websocket

enum class PropagationType {
    Continue,
    Stop,
    Error;
}

data class EventReturn<R>(val result: R, val propagation: PropagationType = PropagationType.Continue)

class EventReturnBundle<R>(bundle: MutableList<EventReturn<R>>) {
}

fun interface EventListener<C: EventCategory, E: Event<C>> : (E) -> Unit {
    override operator fun invoke(event: E)
}

interface EventCategory {
    val name: String
    val ordinal: Int
}

interface Event<C: EventCategory>{
    val category: C
}

class EventDispatcher<C: EventCategory, E: Event<C>, R, F: EventListener<C, E>> {
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

enum class LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error;
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
    fun error(msg: String, throwable: Throwable?)
}