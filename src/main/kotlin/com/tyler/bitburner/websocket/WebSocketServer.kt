@file:Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")

package com.tyler.bitburner.websocket

import com.intellij.openapi.Disposable
import com.intellij.util.io.toByteArray
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.*
import kotlin.concurrent.timer
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.experimental.xor

/**
 * Status codes for use in closing (opcode 8) frames.
 *
 * See [RFC 6455 § 7.4.1](https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1) for more details.
 */
enum class CloseStatusCode(private val ord: Int) {
    Normal(1000),
    GoingDown(1001),
    ProtocolError(1002),
    CantInterpret(1003),
    // For internal use only; don't send over the wire
    None(1005),
    // For internal use only; don't send over the wire
    AbnormalClosure(1006),
    BadData(1007),
    PolicyViolation(1008),
    FrameTooBig(1009),
    BadExtensionNegotiation(1010),
    InternalError(1011),
    // For internal use only; don't send over the wire
    TlsError(1015);

    /** The numerical value of the status code */
    val value: Int
        get() = ord
}

/**
 * Opcodes used in the WebSocket protocol. Internally, these are converted a 4-bit value and are second half of the
 * first byte of the header.
 */
enum class OpCode(private val op: Int) {
    /** Continuation frame. For messages split into parts.
     *
     * This generally does not need to be dealt with, the server will
     */
    Continuation(0x0),
    /** A text frame. This contains utf-8 data to be interpreted as a string. */
    Text(0x1),
    /** A binary frame. This contains binary data that is not guaranteed to be of any specific format */
    Binary(0x2),
    /** Custom content opcode for application use. These are only used if the client/server knows what they mean. */
    Custom1(0x3),
    /** Custom content opcode for application use. These are only used if the client/server knows what they mean. */
    Custom2(0x4),
    /** Custom content opcode for application use. These are only used if the client/server knows what they mean. */
    Custom3(0x5),
    /** Custom content opcode for application use. These are only used if the client/server knows what they mean. */
    Custom4(0x6),
    /** Custom content opcode for application use. These are only used if the client/server knows what they mean. */
    Custom5(0x7),
    /** Close frame. This is used by either end to close a connection cleanly. The server mostly manages these. */
    Close(0x8),
    /** Ping frame. The server wants to make sure the connection is still active.
     *
     * Protocol requires a [OpCode.Pong] response promptly.
     * The server usually deals with these unless explicitly disable.
     */
    Ping(0x9),
    /** Pong frame. A response to a [OpCode.Ping]. */
    Pong(0xA),
    /** Reserved control opcode for extensions. As of writing this, they are invalid. */
    Reserved1(0xB),
    /** Reserved control opcode for extensions. As of writing this, they are invalid. */
    Reserved2(0xC),
    /** Reserved control opcode for extensions. As of writing this, they are invalid. */
    Reserved3(0xD),
    /** Reserved control opcode for extensions. As of writing this, they are invalid. */
    Reserved4(0xE),
    /** Reserved control opcode for extensions. As of writing this, they are invalid. */
    Reserved5(0xF);

    companion object {
        /**
         * Create an [OpCode] from a value. Only the values in 0..15 are valid.
         *
         * @param op the numeric value of the opcode
         * @return the [OpCode] object representing the value passed
         * @throws IllegalArgumentException the [op] passed is not in 0..15
         */
        fun fromValue(op: Int): OpCode {
            return when (op) {
                0x0 -> Continuation
                0x1 -> Text
                0x2 -> Binary
                0x3 -> Custom1
                0x4 -> Custom2
                0x5 -> Custom3
                0x6 -> Custom4
                0x7 -> Custom5
                0x8 -> Close
                0x9 -> Ping
                0xA -> Pong
                0xB -> Reserved1
                0xC -> Reserved2
                0xD -> Reserved3
                0xE -> Reserved4
                0xF -> Reserved5
                else -> throw IllegalArgumentException("opcode must be between 0 and 15")
            }
        }
    }

    /**
     * Returns true if the frame is a control frame. That is, a frame used to control aspects of the protocol rather
     * than exclusively to send user data.
     */
    fun isControlFrame() = when(this) {
        Continuation, Close, Ping, Pong, Custom1, Custom2, Custom3, Custom4, Custom5 -> true
        else -> false
    }

    /**
     * Returns whether this opcode is a custom content opcode, corresponding to [OpCode.Custom1]..[OpCode.Custom5].
     */
    fun isCustomContentOp() = when (this) {
        Custom1, Custom2, Custom3, Custom4, Custom5 -> true
        else -> false
    }

    /**
     * Returns whether this opcode is a reserved control opcode, corresponding to [OpCode.Reserved1]..[OpCode.Reserved5].
     */
    fun isReservedOp() = when (this) {
        Reserved1, Reserved2, Reserved3, Reserved4, Reserved5 -> true
        else -> false
    }

    /**
     * Get the numerical value of this opcode.
     *
     * @return the returned value is in 0..15. The WebSocket protocol internally cannot represent further values.
     */
    val value: Int
        get() = op
}

/**
 * A client connected to the [WebSocketServer].
 *
 * Provides the ability to write data and correctly deals with all the WebSocket intricacies without exposing
 * many of those details.
 *
 * Should be constructed by the [WebSocketServer], and not manually.
 *
 * @param socket the [java.net.Socket] object representing the connection
 * @param inputStream the stream to use for input. Does not have to match the value from socket.getInputStream,
 * which should not be used directly.
 * @param outputStream the output stream. Does not have to match the value from socket.getOutputStream,
 * which should not be used directly.
 * @param server the [WebSocketServer] that is managing this [Client]
 * @param disableAutoPing whether pings are automatically sent on an interval
 */
class Client(val socket: Socket,
             internal val inputStream: BufferedInputStream,
             internal val outputStream: OutputStream,
             internal val server: WebSocketServer,
             val disableAutoPing: Boolean = false,
            ) : Disposable
{
    companion object {
        /**
         * How often to send a ping, in ms.
         *
         * If the client doesn't respond with a pong within the time between timer activations, it will be considered
         * in violation of the protocol, closed and culled. We allow a grace period of two activations, as especially
         * in a loopback, the ping and pong could happen within the resolution of the clock.
         *
         * 10,000 ms should be the absolute minimum used. The higher you make it, the longer the time it will take to
         * find an inactive connection and disconnect from it. It's good to be in an intermediate timescale, well above
         * what any reasonable network and processing latency might cause, but below what a stalled connection for
         * any reason can be assumed to wait. This value is pretty active, but in cases of needing to release a stalled
         * connection before we can accept another, should be reasonable. The amount of wire data should be
         * 4 bytes divided by this per millisecond on average.
         *
         * Consider 2 * [PERIOD_MS] the length of time you are willing to tolerate between the last
         * receipt, and when the connection should be terminated. This value is mutable in the companion object,
         * meaning you can, in the module used, set it to something else which will be used for all clients.
         */
        var PERIOD_MS: Long = 60_000
    }

    internal var handshakeComplete = false
    internal var runnerJob: Job? = null
    internal var lastPing: Long = System.nanoTime()
    internal var lastPong: Long = System.nanoTime()
    internal var frameInProgress: WebSocketFrame? = null
    internal val activeProtocolExtensions: MutableList<ProtocolExtension> = mutableListOf()

    private var heartbeatTimer: Timer? = null
    init {
        if (!disableAutoPing) {
            timer(period=PERIOD_MS, initialDelay=5000) {
                if (socket.isClosed || !socket.isConnected || socket.isOutputShutdown) {
                    server.logDispatcher?.info(
                        "Heartbeat Timer: Underlying WebSocket socket closed from under us." +
                        " Stopping")
                    server.clientDisconnected(this@Client, CloseStatusCode.AbnormalClosure)
                    socket.close()
                    heartbeatTimer = null
                    this.cancel()
                } else if(handshakeComplete) {
                    lastPing = System.nanoTime()
                    ping()
                    val elapsedMillis = (System.nanoTime() - lastPong) / 1e6
                    // We don't know that the nanoTime is precise enough to not have received a pong within the granularity
                    // time. We'll allow two ping-pong cycles to go by with some latency grace period to go by before
                    // deciding it to be in timeout.
                    if (elapsedMillis > PERIOD_MS*1.5) {
                        server.logDispatcher?.warn("Pong timeout - the client is not responding.")
                        server.clientDisconnected(this@Client)
                        this@Client.close(CloseStatusCode.ProtocolError, message="Connection Timeout")
                        heartbeatTimer = null
                        this.cancel()
                    }
                }
            }
        }
    }

    /**
     * Return true if the WebSocket upgrade has completed successfully. Before this, the [Client] should be considered
     * to be in HTTP mode, and writing to it will be blocked.
     */
    val upgradeCompleted: Boolean
        get() = handshakeComplete

    /**
     * Return a string giving the address and port of this client.
     */
    val addressStr =
        "${socket.inetAddress}:${socket.localPort}"

    /**
     * A list of active protocol extension object registered to this client.
     *
     * These may themselves offer a public API. The order is processing order in the case of multiple.
     */
    val protocolExtensions: List<ProtocolExtension>
        get() = activeProtocolExtensions

    /**
     * This will hard close the client socket, not doing any closing negotiation. This means it is near instant.
     *
     * Use [Client.close] instead if you can wait for the closing negotiation to complete the close "nicely". The WebSocket
     * spec permits this, but favors a clean close.
     */
    override fun dispose() {
        forceClose()
    }

    /**
     * Returns a [ProtocolExtension] object given the name string as registered with IANA, or null if this client
     * is not using that extension.
     */
    fun protocolExtension(name: String): ProtocolExtension? =
        activeProtocolExtensions.find { it.name == name }

    /**
     * Write a [OpCode.Text] message to the client
     */
    fun write(str: String) {
        val bytes = str.encodeToByteArray()
        return write(bytes, WebSocketHeader(bytes.size.toLong(), opcode=OpCode.Text))
    }

    /**
     * Write a message to the client. This is lower level and allows the header to be specified. It will be sent
     * verbatim except the length field will be overwritten. Only use this with frames that don't require specific
     * additional data, which, for example, [OpCode.Close] does. The remainder are safe in terms of being properly
     * formatted, but setting headers such as reserve flags is not recommended unless you really know what you are doing.
     *
     * When in doubt, create a header with only the fields you need and use the default values for the rest.
     *
     * @throws WebSocketException write was called on a client that has not completed upgrade handshake. Check
     * [Client.upgradeCompleted] before writing if you are unsure.
     */
    fun write(data: ByteArray, header: WebSocketHeader) {
        if (!upgradeCompleted) {
            server.logDispatcher?.warn(
            "Tried to send WebSocket data over a connection that has yet to successfully " +
                    "complete the upgrade handshake. The write will be ignored.")
            return
        }
        val headerData = header.encode()
        val frameData = headerData + data
        header.length = data.size.toLong()
        outputStream.write(frameData)

        val frame = WebSocketFrame(header, data)
        server.frameSent(FrameEvent(StandardEventCategory.FrameSent, server, this, frame))
    }

    /**
     * Send a ping
     */
    fun ping(msg: ByteArray? = null) {
        server.logDispatcher?.debug("Ping sent")
        val data = msg ?: ByteArray(0)
        write(data, WebSocketHeader(data.size.toLong(), opcode=OpCode.Ping))
    }

    /**
     * Send a pong.
     *
     * This does not necessarily have to be in response to a ping. It can be a unidirectional heartbeat. The client
     * is not expected to respond, however.
     */
    fun pong(msg: ByteArray? = null) {
        server.logDispatcher?.debug("Pong sent")
        val data = msg ?: ByteArray(0)
        write(data, WebSocketHeader(data.size.toLong(), opcode=OpCode.Pong))
    }

    /**
     * Notify a client that a pong is received.
     */
    fun pongReceived(frame: WebSocketFrame) {
        if (frame.header.opCode == OpCode.Pong) {
            lastPong = System.nanoTime()
        }
    }

    /**
     * Close this client cleanly
     *
     * This involves removing the client from the server, sending a close message, and waiting for a close message
     * in response. This is done on a thread other than the calling thread, and when this function returns a job
     * processing the closing has been initiated, the outputStream has been closed but the socket will remain open
     * until the closing process completes.
     *
     * @return a job that completes when the closing process is completed
     */
    fun close(statusCode: CloseStatusCode, message: String? = null): Job? {
        val clientObj = this
        server.clientDisconnected(this, statusCode)
        heartbeatTimer?.cancel()
        // Kill the original worker job, we will create a special closing worker to handle closing protocol.
        runnerJob?.cancel()


        return server.coroutineScope?.launch {
            try {
                val len = (2 + (message?.length ?: 0)).toLong()
                val outData = ByteArray(len.toInt())
                ByteBuffer.wrap(outData)
                    .putShort(statusCode.value.toShort())
                    .put(message?.encodeToByteArray() ?: byteArrayOf())

                write(outData, WebSocketHeader(outData.size.toLong(), OpCode.Ping))
                server.logDispatcher?.info("Close frame sent to \"$addressStr\"")
                withContext(Dispatchers.IO) {
                    clientObj.outputStream.close()
                }

                val header: WebSocketHeader?
                var status: Int? = null
                var exitMsg: String? = null
                try {
                    header = withContext(Dispatchers.IO) {
                        WebSocketHeader.decode(inputStream)
                    }
                    status =
                        if (header.length >= 2) {
                            ByteBuffer.wrap(receiveBytes(inputStream, header.length.toInt()))
                                .asShortBuffer()[0].toUShort().toInt()
                        } else {
                            null
                        }

                    exitMsg =
                        if (header.length > 2) {
                            receiveBytes(inputStream, (header.length - 2).toInt()).decodeToString()
                        } else {
                            null
                        }

                } catch (t: Throwable) {
                    server.logDispatcher?.info("Final close read failed. The client probably closed before " +
                            "sending a response")
                } finally {
                    clientObj.inputStream.close()
                    socket.close()

                    server.logDispatcher?.info("Clean connection negotiation completed for \"$addressStr\". " +
                            "Final status: $status. " + (exitMsg ?: "")
                    )
                    server.connectionClosedGracefully(
                        ConnectionCloseEvent(StandardEventCategory.ConnectionClosedGracefully,
                            server, this@Client, status, exitMsg))

                }
            } catch (t: Throwable) {
                server.logDispatcher?.error(t)
            }
        }
    }

    /**
     * Close the socket ungracefully, just closing the socket object itself and doing no further steps.
     *
     * This is allowed, but not recommended if you can allow for a clean close.
     * The client will be closed when this returns, unlike [Client.close].
     */
    fun forceClose() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        runnerJob?.cancel()
        runnerJob = null
        server.clientDisconnected(this, CloseStatusCode.AbnormalClosure, "Force closed")
        socket.close()
    }
}

/**
 * An exception in WebSocket related processing
 */
class WebSocketException : Exception {
    constructor() : super()
    constructor(msg: String) : super(msg)
}

/**
 * A header of a websocket frame.
 *
 * This can store all the header information, print itself in a human-readable format, and encode itself to a binary
 * structure ready to be sent over the wire.
 *
 * It also has some convenience getters for classifying the header type.
 */
data class WebSocketHeader(var length: Long, private val opcode: Int, var final: Boolean = true,
                           var masked: Boolean = false, val mask: ByteArray = ByteArray(4),
                           var resv1: Boolean = false, var resv2: Boolean = false, var resv3: Boolean = false) {
    constructor(length: Long, opcode: OpCode, final: Boolean = true,
                masked: Boolean = false, mask: ByteArray = ByteArray(4),
                resv1: Boolean = false, resv2: Boolean = false, resv3: Boolean = false) :
            this(length, opcode.value, final, masked, mask, resv1, resv2, resv3)
    constructor(opcode: OpCode) : this(0, opcode.value)


    init {
        if (opcode !in 0..15) {
            throw IllegalArgumentException("Opcode must be in 0..15")
        }
    }

    companion object {
        /**
         * Decode a header from a stream
         */
        suspend fun decode(stream: InputStream): WebSocketHeader {
            val headerStart: ByteArray = receiveBytes(stream, 2)

            val len: Long =
                when (val shortLen = headerStart[1].and(0x7F.toByte()).toInt()) {
                    0x7E -> ByteBuffer.wrap(receiveBytes(stream, 2))
                        .order(ByteOrder.BIG_ENDIAN).asShortBuffer()[0].toUShort().toLong()
                    0x7F -> ByteBuffer.wrap(receiveBytes(stream, 8))
                        .order(ByteOrder.BIG_ENDIAN).asShortBuffer()[0].toULong().toLong()
                    else -> shortLen.toLong()
                }

            val final: Boolean = headerStart[0].and(0x80.toUByte().toByte()) != 0.toByte()
            val rsv1: Boolean = headerStart[0].and(0x40.toByte()) > 0
            val rsv2: Boolean = headerStart[0].and(0x20.toByte()) > 0
            val rsv3: Boolean = headerStart[0].and(0x10.toByte()) > 0
            val opcode: Int = headerStart[0].and(0xF.toByte()).toInt()

            val masked: Boolean = headerStart[1].and(0x80.toUByte().toByte()) != 0.toByte()
            val mask: ByteArray =
                if (masked) {
                    receiveBytes(stream, 4)
                } else {
                    byteArrayOf(0, 0, 0, 0)
                }

            return WebSocketHeader(len, opcode, final, masked, mask, rsv1, rsv2, rsv3)
        }
    }

    /**
     * Get the [OpCode] of the header.
     */
    val opCode: OpCode
        get() = OpCode.fromValue(opcode)

    /**
     * Return whether this is a control frame.
     *
     * @see [OpCode.isControlFrame]
     */
    fun isControlFrame(): Boolean = opCode.isControlFrame()

    /**
     * Return whether this is a custom content opcode frame.
     *
     * @see [OpCode.isCustomContentOp]
     */
    fun isCustomOpFrame(): Boolean = opCode.isCustomContentOp()

    /**
     * Return whether this is a reserved control frame.
     *
     * @see [OpCode.isReservedOp]
     */
    fun isReservedOpFrame(): Boolean = opCode.isReservedOp()

    /**
     * The frame header uses an extended length. This means the length is > 125, and it increases the length of the
     * header data by either 2 or 8 bytes.
     */
    fun hasExtendedLen() = length > 125

    /**
     * The frame header is extended and uses a [Long] extended length. in this case the header contains 8 bytes holding
     * the length. This is the case if the length field is longer than what fits in a 16-bit unsigned integer (65,535).
     *
     * Due to the experimental nature of [ULong], this library opts to use [Long] to store the value and thus
     * technically does not support valid values where the 64-th bit is significant, however if such a frame is
     * received you have bigger problems with the fact you just got over 9 exabytes over the wire.
     *
     * This library artificially limits frames to 2 GiB in length. This is because of the problematic nature of the JVM
     * using [Int] as the index type for most collections, and thus makes storing such a frame nicely difficult for
     * the purpose of supporting a rare event.
     *
     * If you are receiving legitimate data over 2 GiB, you will need to handle [OpCode.Continuation] frames yourself.
     * This isn't currently implemented, but is planned
     */
    fun hasLongExtendedLen() = length > 65535

    /**
     * Size of the encoded header in bytes. This can range from 2 to 14
     */
    fun sizeInBytes(): Int {
        val baseSize: Int =
            if (hasExtendedLen()) {
                4
            } else if (hasLongExtendedLen()) {
                10
            } else {
                2
            }

        return if (masked) {
            baseSize + 4
        } else {
            baseSize
        }
    }

    /**
     * Get a ByteArray of the header that is in the format needed to go over the wire
     */
    fun encode(): ByteArray {
       val headerData = ByteArray(sizeInBytes())
        // The suffering that is dealing with binary data in kotlin/java
        headerData[0] =
                (if (final) 0x80.toUByte().toByte() else 0x0.toByte())
                .or(if (resv1) 0x40.toByte() else 0x0.toByte())
                .or(if (resv2) 0x20.toByte() else 0x0.toByte())
                .or(if (resv3) 0x10.toByte() else 0x0.toByte())
                .or(opcode.toByte())

        val shortLenVal: Byte =
            when (length) {
                in 0x00..0x7D -> length
                in 0x7E..0xFFFF -> 126
                else -> 127
            }.toByte()

        headerData[1] = shortLenVal.toUByte().or(if (masked) 0x80.toUByte() else 0.toUByte()).toByte()
        val lenBuffer: ByteBuffer =
            if (hasLongExtendedLen()) {
                ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(length.toULong().toLong())
            } else if (hasExtendedLen()) {
                ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(length.toUShort().toShort())
            } else {
                ByteBuffer.allocate(0)
            }

        val unmaskedHeader = headerData + lenBuffer.toByteArray()
        return if (masked) unmaskedHeader + mask else unmaskedHeader
    }

    /**
     * Returns a human-readable String appropriate for display.
     */
    override fun toString(): String =
        arrayOf(
            if (resv1) "resv1=true" else "",
            if (resv2) "resv2=true" else "",
            if (resv3) "resv3=true" else ""
        ).joinToString(",").let {
            if (it.trimEnd(',').isNotEmpty()) {
                ", $it"
            } else {
                it.trimEnd(',')
            }
        }.let {
            return "WebSocketHeader(${opCode}):$length {fin=$final, masked=$masked$it}"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WebSocketHeader

        if (length != other.length) return false
        if (opcode != other.opcode) return false
        if (final != other.final) return false
        if (masked != other.masked) return false
        if (!mask.contentEquals(other.mask)) return false
        if (resv1 != other.resv1) return false
        if (resv2 != other.resv2) return false
        if (resv3 != other.resv3) return false

        return true
    }

    override fun hashCode(): Int {
        return encode().hashCode()
    }
}

/**
 * Represents a full WebSocket frame. This consists of a header and an optional payload.
 *
 * Because of how the [WebSocketServer] works, continuation frames are automatically appended and thus aren't exposed
 * to the user in normal usage.
 */
data class WebSocketFrame(val header: WebSocketHeader, internal val data: ByteArray) {
    /**
     * Get the content as utf-8 encoded text, as long as parsing it as such succeeds and the frame is valid to contain
     * utf-8 data.
     *
     * @return the utf-8 [String] if by spec the body must be utf-8, or null if decoding as utf-8 fails for frames
     * with optional bodies. It will also return null automatically
     * for opcodes that should not be interpreted as utf-8: [OpCode.Binary].
     * If it is an [OpCode.Close], messages are interpreted slightly differently but this is handled transparently.
     * [OpCode.Text] will always return a valid string, with replacement characters for invalid characters.
     * There shouldn't be invalid characters for a properly behaving client.
     */
    val textContent
        get(): String? =
            when(header.opCode) {
                // Close can have a message after the status code, and it is required to be utf-8, so we won't validate.
                OpCode.Close -> if (data.size >= 2) data.copyOfRange(2, data.size).decodeToString() else null
                // Text is required to have utf-8 data, so we won't validate
                OpCode.Text -> data.decodeToString()
                // Binary frames are specifically not utf-8 data. No reason to waste cycles checking
                OpCode.Binary -> null
                // The remaining might have a valid utf-8 body, but it's not guaranteed
                else -> try {
                        data.decodeToString(throwOnInvalidSequence=true)
                    } catch (t: CharacterCodingException) {
                        null
                    }
            }

    /**
     * Get the raw data in this frame. There is no guarantee what format this data is in, other than that it is
     * guaranteed to be utf-8 encoded text if the opcode is [OpCode.Text].
     */
    val bytes: ByteArray
        get() = data

    /**
     * Return the content length of the frame. This is equivalent to [WebSocketHeader.length].
     */
    val length: Long =
        header.length

    /**
     * Return the [OpCode] for the frame. This is equivalent to [WebSocketHeader.opCode]
     */
    val opCode: OpCode =
        header.opCode

    /**
     * Get the optional status code of an [OpCode.Close] frame.
     *
     * For all other frames, this will return null.
     */
    val closeStatusCode: Int? =
        if (header.opCode == OpCode.Close) {
            if (header.length >= 2) {
                ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    .asShortBuffer()[0].toInt()
            } else {
                null
            }
        } else {
            null
        }

    /**
     * Get a human-readable string containing the header and up to 120 chars of the body.
     */
    override fun toString(): String {
        var headerStr = header.toString()

        if (opCode == OpCode.Close && closeStatusCode != null) {
            headerStr += ". Close Status: $closeStatusCode"
        }
        val text = textContent

        val bodyStr =
            if (text != null && text.length <= 120) {
                if (text.isEmpty()) {
                    ""
                } else {
                    "\n$text"
                }
            } else if (text != null) {
                "\n${text.substring(0..120)}..."
            } else {
                ""
            }

        return headerStr + bodyStr
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WebSocketFrame

        if (header != other.header) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }
}

/**
 * An input stream reader that doesn't close the underlying stream when it closes. These are effectively views
 * (and what java should have done for readers from the start)
 *
 * This is used to read the HTTP request
 *
 * @see java.io.InputStreamReader
 */
internal class NonOwningInputStreamReader(stream: InputStream, charset: Charset = Charset.defaultCharset()) :
    InputStreamReader(stream, charset) {

    override fun close() {}
}

/**
 * A websocket server implementation.
 *
 * In the current implementation, it only accepts a single connected client at once. It is written to allow this to be
 * extended in the future to multiple simultaneous clients if that becomes useful.
 *
 * @param logDispatcher an optional instance of a LogDispatcher that sends internal log messages to the application
 * logger. WebSocketServer tries to keep dependencies lean and non-opinionated.
 */
class WebSocketServer(var logDispatcher: LogDispatcher? = null) {
    companion object {
        /**
         * Apply WebSocket masking to the content of a message,
         */
        fun applyMasking(data: ByteArray, mask: ByteArray) {
            for (i in data.indices) {
                data[i] = data[i].xor(mask[(i % 4)])
            }
        }

        private val HTTP_REQUEST_REGEX = Regex("GET (/\\w*)\\s+HTTP/[.\\d]+")
        private val HTTP_HEADER_REGEX = Regex("([-\\w]+):\\s+(.*)\\s*")
    }

    internal var listenSocket: ServerSocket? = null
    internal var _client: Client? = null
    internal var coroutineScope: CoroutineScope? = null
    internal var listenJob: Job? = null

    /**
     * Collection of known extensions. These are available for use, but are not necessarily used.
     */
    internal var extensionDefs: MutableMap<String, ProtocolExtensionDefinition> = mutableMapOf()

    val client
        get() = _client

    /**
     * Return a [Map] mapping extension name to the definition.
     */
    val registeredProtocolExtensions: Map<String, ProtocolExtensionDefinition>
        get() = extensionDefs

    // {{{ Public general api

    /**
     * Start listening on the given port. The servers are scoped to a project, so multiple can run at once in multiple
     * windows if needed.
     *
     * @param port the TCP port to listen for connections on
     */
    fun start(port: Int) {
        listenSocket = ServerSocket(port)
        coroutineScope = CoroutineScope(Dispatchers.Default)

        listenJob = coroutineScope!!.launch(Dispatchers.Default) {
            acceptConnection(listenSocket!!)
        }

        var errorHandlerBody: ((Throwable?) -> Unit)? = null
        errorHandlerBody =
            { it: Throwable? ->
                if (it != null) {
                    logDispatcher?.error("Exception in listener job. It will be restarted", it)
                    listenJob = coroutineScope!!.launch(Dispatchers.Default) {
                        acceptConnection(listenSocket!!)
                    }
                    listenJob?.invokeOnCompletion(errorHandlerBody!!)
                }

            }

        listenJob?.invokeOnCompletion(errorHandlerBody)
    }

    /**
     * Close the server.
     *
     * This will shut down listening, terminate many of the long-running jobs, and start a graceful shutdown process.
     * This will leave a job running, which is returned, doing the final shutdown steps. If you must take it all down
     * fast, you may call cancel on the returned job, but only do this if you need to guarantee the jobs are all
     * terminated.
     *
     * @param status: the status code to pass to the client
     * @param msg: an optional message to send to the client
     * @return the job doing the final clean-up steps
     */
    fun stop(status: CloseStatusCode = CloseStatusCode.Normal, msg: String? = null): Job? {
        listenJob?.cancel()
        listenJob = null
        listenSocket?.close()
        listenSocket = null
        val job = _client?.close(status, msg)
        job?.invokeOnCompletion {
            val scope = coroutineScope
            try {
                // Just in case any jobs are stragglers, we get rid of them now.
                scope?.cancel("Server shutdown")
            } catch(t: Throwable) {
                logDispatcher?.error("Exception encountered when stopping server", t)
            }
            coroutineScope = null
        }
        return job
    }

    /**
     * Register a protocol definition with the server. The extension definitions contain a unique name and handle
     * the creation of the actual client ProtocolExtension object if the client gives a request including it.
     *
     * This is only for protocol extensions. If it is not registered with IANA, it is not valid to use.
     */
    fun addProtocolExtensionDefinition(extension: ProtocolExtensionDefinition) {
        extensionDefs[extension.name] = extension
    }

    // Public general api }}}

    // {{{ Public events api

    /**
     * Invoked when a client has made a connection but before registering them with the server.
     *
     * A client can be rejected by setting [ConnectionAttemptEvent.doAccept] to false in a handler.
     */
    val connectionAttempted = EventDispatcher<StandardEventCategory, ConnectionAttemptEvent,
            EventListener<StandardEventCategory, ConnectionAttemptEvent>>(StandardEventCategory.ConnectionAttempt)

    /**
     * Invoked when a connection is made, a [Client] is created, and it is registered with the server, but before
     * the WebSocket handshake is complete. This is still operating as an HTTP stream until the handshake completes.
     */
    val connectionAccepted = EventDispatcher<StandardEventCategory, ConnectionEvent,
            EventListener<StandardEventCategory, ConnectionEvent>>(StandardEventCategory.ConnectionAccepted)

    /**
     * Invoked when a connection successfully completes the WebSocket handshake protocol and the [Client] is ready
     * to send and receive WebSocket frames.
     */
    val webSocketNegotiated = EventDispatcher<StandardEventCategory, ConnectionEvent,
            EventListener<StandardEventCategory, ConnectionEvent>>(StandardEventCategory.WebSocketNegotiated)

    /**
     * Invoked when a connection starts the closing process, when [Client.forceClose] is used or when
     * the client either sends a [OpCode.Close] frame or closes the socket.
     *
     * The [ConnectionCloseEvent.statusCode] and [ConnectionCloseEvent.msg] fields of the event are the status
     * and optional messages provided by the [WebSocketServer].
     */
    val connectionClosing = EventDispatcher<StandardEventCategory, ConnectionCloseEvent,
            EventListener<StandardEventCategory, ConnectionCloseEvent>>(StandardEventCategory.ConnectionClosing)

    /**
     * Invoked when the client close process is completed gracefully. It is not forbidden for a connection to be
     * closed ungracefully, so this event will not be generated for all cases where a connection is closed.
     * Use [connectionClosing] in that case.
     *
     * The [ConnectionCloseEvent.statusCode] and [ConnectionCloseEvent.msg] fields of the event are the status
     * and optional messages provided by the _client_ after the closing handshake completes.
     */
    val connectionClosedGracefully = EventDispatcher<StandardEventCategory, ConnectionCloseEvent,
            EventListener<StandardEventCategory, ConnectionCloseEvent>>(StandardEventCategory.ConnectionClosing)

    /**
     * Invoked when a [OpCode.Text] or [OpCode.Binary] frame is received, or a custom opcode frame.
     *
     * These are frames to transmit user defined data only, in contrast to control frames which are
     * used for controlling the WebSocket protocol.
     * A [OpCode.Continuation] frame is also a content frame, but it is not exposed by [WebSocketServer] and is
     * folded internally into a single, whole frame.
     */
    val contentFrameReceived = EventDispatcher<StandardEventCategory, FrameEvent,
            EventListener<StandardEventCategory, FrameEvent>>(StandardEventCategory.ContentFrameReceived)

    /**
     * Invoked when a control frame is received.
     *
     * Control frames are frames for controlling the WebSocketServer.
     * They include [OpCode.Close], [OpCode.Ping], [OpCode.Pong] and the reserved opcodes.
     * A user of WebSocketServer does not need to explicitly interact with these frames normally,
     * but they may if they desire.
     */
    val controlFrameReceived = EventDispatcher<StandardEventCategory, FrameEvent,
            EventListener<StandardEventCategory, FrameEvent>>(StandardEventCategory.ControlFrameReceived)

    /**
     * Invoked when a [OpCode.Continuation] frame is received.
     *
     * These are technically content frames, but are handled internally for the most part. If you want to do your
     * own fragment processing, or are expecting very large messages for e.g. downloading a file that do not need
     * to be entirely in RAM at once, you can override the default processing and get access to those frames with
     * this dispatcher.
     */
    val continuationFrameReceived = EventDispatcher<StandardEventCategory, FrameEvent,
            EventListener<StandardEventCategory, FrameEvent>>(StandardEventCategory.ContinuationFrameReceived)

    /**
     * Invoked when any frame is sent by the server.
     *
     * This is mostly useful for introspection or debugging purposes. When the event fires, the frame is already sent
     * and the process or frame cannot be altered.
     */
    val frameSent = EventDispatcher<StandardEventCategory, FrameEvent,
            EventListener<StandardEventCategory, FrameEvent>>(StandardEventCategory.FrameSent)

    // Public events api }}}

    // {{{ Internal events api

    internal fun clientConnected(client: Client) {
        this._client = client
    }

    internal fun clientHandshakeSuccess(client: Client) {
        webSocketNegotiated(ConnectionEvent(StandardEventCategory.WebSocketNegotiated, this, client))
    }

    internal fun clientDisconnected(client: Client,
                                    statusCode: CloseStatusCode = CloseStatusCode.Normal,
                                    msg: String? = null) {
        if (client === this._client) {
            this._client = null
            connectionClosing(ConnectionCloseEvent(StandardEventCategory.ConnectionClosing, this, client,
                statusCode.value, msg))
        }
    }

    // Internal events api }}}

    // {{{ Private api
    private suspend fun acceptConnection(listenSocket: ServerSocket) {
        while (!listenSocket.isClosed) {
            if (_client != null) {
                yield()
                delay(100)
            } else {
                val clientSocket = withContext(Dispatchers.IO) {
                    listenSocket.accept()
                }
                val event = ConnectionAttemptEvent(StandardEventCategory.ConnectionAttempt, this, clientSocket)
                logDispatcher
                    ?.info("Incoming client connection from " +
                            "\"${clientSocket.inetAddress}:${clientSocket.localPort}\"")
                if (!event.doAccept) {
                    withContext(Dispatchers.IO) {
                        clientSocket.close()
                        logDispatcher?.info("Incoming connection rejected")
                    }
                    break
                }
                if (clientSocket != null) {
                    val inputStream = withContext(Dispatchers.IO) {
                        BufferedInputStream(clientSocket.getInputStream())
                    }
                    val outputStream = withContext(Dispatchers.IO) {
                        clientSocket.getOutputStream()
                    }

                    val client = Client(clientSocket, inputStream, outputStream, this)
                    clientConnected(client)

                    withContext(Dispatchers.IO) {
                        try {
                            doHandshake(client)
                        } catch (t: Throwable) {
                            logDispatcher?.error("Error when performing handshake", t)
                            client.close(CloseStatusCode.PolicyViolation, "Error negotiating handshake: " +
                                    "${t.message}")
                            if (_client === client) {
                               _client = null
                            }
                        }
                    }
                    if (this._client != null) {
                        this._client!!.runnerJob =
                            coroutineScope?.launch {
                                clientWorker(client)
                            }
                    }
                }
                yield()
            }
        }
    }

    private suspend fun clientWorker(client: Client) {
        while (!client.socket.isClosed && this._client != null) {
            var doContinue = false
            val frame = withContext(Dispatchers.IO) {
                try {
                    parseFrame(client)
                } catch (t: WebSocketException) {
                    logDispatcher?.error(t)
                    client.close(CloseStatusCode.ProtocolError, t.message)
                    throw t
                } catch (t: Throwable) {
                    logDispatcher?.error(t)
                    doContinue = true
                    WebSocketFrame(WebSocketHeader(0, OpCode.Text), ByteArray(0))
                }
            }

            if (doContinue) continue

            when (frame.header.opCode) {
                OpCode.Continuation -> {
                    logDispatcher?.info("Processing continuation frame")
                    continuationFrameReceived(
                        FrameEvent(StandardEventCategory.ContinuationFrameReceived, this,
                        client, frame)
                    )
                }
                OpCode.Binary, OpCode.Text, OpCode.Custom1, OpCode.Custom2, OpCode.Custom3,
                    OpCode.Custom4, OpCode.Custom5 -> {
                        contentFrameReceived(FrameEvent(StandardEventCategory.ContentFrameReceived,
                            this, client, frame))
                }
                OpCode.Close -> {
                    logDispatcher?.info("Client \"${client.addressStr}\" requested WebSocket closure")
                    val data = ByteArray(2)
                    ByteBuffer.wrap(data).putShort(CloseStatusCode.Normal.value.toShort())
                    client.write(data, WebSocketHeader(2, OpCode.Close))
                    connectionClosedGracefully(ConnectionCloseEvent(StandardEventCategory.ConnectionClosing, this, client,
                        frame.closeStatusCode, frame.textContent))
                    client.forceClose()
                }
                OpCode.Ping -> {
                    logDispatcher?.debug("Ping received")
                    controlFrameReceived(FrameEvent(StandardEventCategory.ControlFrameReceived, this,
                        client, frame))
                    client.pong(frame.bytes)
                }
                OpCode.Pong -> {
                    logDispatcher?.debug("Pong received")
                    controlFrameReceived(FrameEvent(StandardEventCategory.ControlFrameReceived, this,
                            client, frame))
                    client.pongReceived(frame)
                }
                else -> {
                    val error =  WebSocketException("Found a reserved control opcode outside the valid range.")
                    logDispatcher?.warn(error)
                    throw error
                }
            }
            yield()
        }
    }

    private suspend fun parseFrameHeader(client: Client): WebSocketHeader = WebSocketHeader.decode(client.inputStream)

    private suspend fun parseFrame(client: Client): WebSocketFrame {
        var data = ByteArray(0)

        val header: WebSocketHeader = withContext(Dispatchers.IO) {
            parseFrameHeader(client)
        }
        if (header.length != 0L) {
            val dataPart = receiveBytes(client, header.length.toInt())
            if (header.masked) {
                applyMasking(dataPart, header.mask)
            }
            data = dataPart
        }
        if (header.isControlFrame() && !header.final) {
            throw WebSocketException("Control frames are not permitted to be fragmented")
        }

        if (header.length > Int.MAX_VALUE) {
            withContext(Dispatchers.Default) {
                throw WebSocketException("Total message size above INT_MAX is unsupported")
            }
        }

        if (header.opCode == OpCode.Continuation) {
            if (header.isControlFrame()) {
                throw WebSocketException("Control frames are not permitted to be fragmented")
            }
            if (client.frameInProgress != null) {
                client.frameInProgress = WebSocketFrame(header, client.frameInProgress!!.data + data)
            } else {
                throw WebSocketException("Got a continuation frame with no current frame to be continued. This is " +
                        "a serious protocol error as it means a full message must be discarded, or a serious server" +
                        "bug. Since we cannot guarantee that the communication is not context-sensitive from previous" +
                        "messages, we will close the connection.")
            }
        } else if (!header.final) {
            if (client.frameInProgress != null) {
                logDispatcher?.info("Starting a new fragmented frame: $header")
                client.frameInProgress = WebSocketFrame(header, data)
            } else {
                throw WebSocketException("A non-final frame with an opcode other than Continuation (0x0) is " +
                        "invalid")
            }
        }

        return if (header.final && client.frameInProgress != null) {
            val frame = WebSocketFrame(client.frameInProgress!!.header,
                client.frameInProgress!!.bytes + data)
            frame.header.final = true
            client.frameInProgress = null
            frame
        } else {
            WebSocketFrame(header, data)
        }
    }

    /**
     * Handle the HTTP protocol upgrade, before we can drop into a websocket connection.
     */
    private suspend fun doHandshake(client: Client) {
        val reader = BufferedReader(NonOwningInputStreamReader(client.inputStream))

        val requestStr = withContext(Dispatchers.IO) {
            reader.readLine()
        }
        val getPath = HTTP_REQUEST_REGEX.matchEntire(requestStr)?.groups?.get(1)?.value

        if (getPath == null) {
            logDispatcher?.warn("Unsupported GET request received: $requestStr")
            clientDisconnected(client, CloseStatusCode.CantInterpret)
            withContext(Dispatchers.IO) {
                client.outputStream.write("HTTP/1.1 403 Forbidden".encodeToByteArray())
            }
            client.forceClose()
            return
        }

        val headers = mutableMapOf<String, MutableList<String>>()

        var line = withContext(Dispatchers.IO) {
            reader.readLine()
        }
        while (line != "") {
            val regexResult = HTTP_HEADER_REGEX.matchEntire(line)
            val name = regexResult?.groups?.get(1)?.value
            val value = regexResult?.groups?.get(2)?.value
            if (name != null && value != null) {
                val list = headers[name] ?: mutableListOf()
                list.add(value)
                headers[name] = list
            }
            line = withContext(Dispatchers.IO) {
                reader.readLine()
            }
        }
        val headersStr = headers.map { "${it.key}: ${it.value.joinToString(", ")}" }
            .joinToString("\r\n", postfix="\r\n\r\n")
        logDispatcher?.info("Got HTTP request: $requestStr\n$headersStr")

        if (headers["Upgrade"]?.get(0) == "websocket" && headers.containsKey("Sec-WebSocket-Key")) {
            sendHandshakeResponse(client, headers)
        } else {
            client.forceClose()
            if (client === this._client) {
                this._client = null
            }
        }
    }

    private suspend fun sendHandshakeResponse(client: Client, headers: Map<String, List<String>>) {
        // The WebSocket standard itself defines this string
        val magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val responseLines = mutableListOf<String>()

        val secKey = headers["Sec-WebSocket-Key"]?.get(0)

        val extensionProposals = extensionNegotiation(client, headers)

        // The response key is required by spec to be a sha1 digest of what the client sends plus the magic string,
        // encoded in base-64.
        val responseKey = Base64.getEncoder().encode(
            MessageDigest.getInstance("SHA1").digest((secKey + magicString).toByteArray())
        )
        val responseKeyStr = String(responseKey)

        responseLines.addAll(listOf(
            "HTTP/1.1 101 Switching Protocols",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Content-Language: \"en-US\"",
            "Sec-WebSocket-Version: 13",
            "Sec-WebSocket-Accept: $responseKeyStr",
        ))

        val responseStr = responseLines.joinToString("\r\n", postfix="\r\n\r\n")
        logDispatcher?.info("Protocol upgrade response:\n$responseStr")
        withContext(Dispatchers.IO) {
            client.outputStream.write(responseStr.encodeToByteArray())
        }

        client.handshakeComplete = true
        webSocketNegotiated(ConnectionEvent(StandardEventCategory.WebSocketNegotiated, this, client))
    }

    /** Like receiveBytes at the package level, but uses the client. */
    private suspend fun receiveBytes(client: Client, len: Int): ByteArray {
        return receiveBytes(client.inputStream, len)
    }

    private fun extensionNegotiation(client: Client, headers: Map<String, List<String>>){
        val extensions = headers["Sec-WebSocket-Extensions"] ?: mutableListOf()

        //Group
        val extensionsFlat = extensions.flatMap { it.split(",") }

        // Tokenize and build the structure
        val extensionMap: Map<String, ExtensionDeclaration> = extensionsFlat.map { ex ->
            val frags = ex.split(';')
            val exName = frags[0]
            val args = frags.subList(1, frags.size).map {frag ->
                val parts = frag.trim().split('=')
                val name = parts[0]
                val value: String? =
                    if (parts.size > 1) {
                        parts.subList(1, parts.size).joinToString("=")
                    } else {
                        null
                    }
                ExtensionArg(name, value)
            }

            ExtensionProposal(exName, args)
        }.let {
            // Construct a map of extension to proposal(s).
            val proposalMapping: MutableMap<String, MutableList<ExtensionProposal>> = mutableMapOf()
            for (proposal in it) {
                if (proposal.name in proposalMapping) {
                    proposalMapping[proposal.name]?.add(proposal)
                } else {
                    proposalMapping[proposal.name] = mutableListOf(proposal)
                }
            }
            proposalMapping
        }.map {
            // Build array of declarations
            it.key to ExtensionDeclaration(it.key, it.value)
        }.toMap()

        for (ex in registeredProtocolExtensions) {
            if (ex.key in extensionMap) {
                val params = ex.value.extensionNegotiation(extensionMap[ex.key]!!)
                if (!params.isNullOrEmpty()) {
                    logDispatcher?.info("Extension ${ex.key} is being activated for ${client.addressStr}")
                }
            }
        }
    }

    // Private api }}}
}

/**
 * Receive exactly numBytes from the stream.
 *
 * This should only be used if you know that at least [numBytes] bytes are expected to be in the stream or added to it
 * soon. Using an incorrect value may never return. As a suspend function, this spawns a IO job to get the data,
 * and blocks until it returns all the required data.
 *
 * @param stream the stream to read data from
 * @param numBytes how many bytes to receive
 * @return ByteArray a ByteArray of size [numBytes] containing the data
 */
internal suspend fun receiveBytes(stream: InputStream, numBytes: Int): ByteArray {
    var data = ByteArray(0)
    do {
        data += withContext(Dispatchers.IO) {
            stream.readNBytes(numBytes - data.size)
        }
    } while (data.size != numBytes)

    return data
}