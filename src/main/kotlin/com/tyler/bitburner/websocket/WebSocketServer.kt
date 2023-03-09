@file:Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")

package com.tyler.bitburner.websocket

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.toByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    /** Custom opcodes for application use. These are only used if the client/server knows what they mean. */
    Custom1(0x3),
    /** Custom opcodes for application use. These are only used if the client/server knows what they mean. */
    Custom2(0x4),
    /** Custom opcodes for application use. These are only used if the client/server knows what they mean. */
    Custom3(0x5),
    /** Custom opcodes for application use. These are only used if the client/server knows what they mean. */
    Custom4(0x6),
    /** Custom opcodes for application use. These are only used if the client/server knows what they mean. */
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
    /** Reserved for extensions. As of writing this, they are invalid. */
    Reserved1(0xB),
    /** Reserved for extensions. As of writing this, they are invalid. */
    Reserved2(0xC),
    /** Reserved for extensions. As of writing this, they are invalid. */
    Reserved3(0xD),
    /** Reserved for extensions. As of writing this, they are invalid. */
    Reserved4(0xE),
    /** Reserved for extensions. As of writing this, they are invalid. */
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
     * than send user data.
     */
    fun isControlFrame() = when(this) {
        Continuation, Close, Ping, Pong -> true
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
             internal var inputStream: BufferedInputStream,
             internal var outputStream: OutputStream,
             internal val server: WebSocketServer,
             val disableAutoPing: Boolean = false,
            ) : Disposable
{
    companion object {
        private val logger = Logger.getInstance(WebSocketServer::class.java)
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
         * 2 bytes divided by this per millisecond on average.
         *
         * Consider 2 * [PERIOD_MS] the length of time you are willing to tolerate between the last
         * receipt, and when the connection should be terminated. This value is mutable in the companion object,
         * meaning you can, in the module used, set it to something else which will be used for all clients.
         */
        var PERIOD_MS: Long = 30000
    }

    internal var handshakeComplete = false
    internal var runnerJob: Job? = null
    internal var lastPing: Long = System.nanoTime()
    internal var lastPong: Long = System.nanoTime()

    private var heartbeatTimer: Timer? = null
    init {
        if (!disableAutoPing) {
            timer(period=PERIOD_MS, initialDelay=2000) {
                if (socket.isClosed || !socket.isConnected) {
                    logger.warn("Underlying WebSocket socket closed from under us")
                    server.clientDisconnected(this@Client, CloseStatusCode.AbnormalClosure)
                    socket.close()
                } else if(handshakeComplete) {
                    lastPing = System.nanoTime()
                    ping()
                    val elapsedMillis = (System.nanoTime() - lastPong) / 1e6
                    // We don't know that the nanoTime is precise enough to have received a pong within the update
                    // time. We'll allow two ping-pong cycles to go by with some latency grace period to go by before
                    // deciding it to be in timeout.
                    if (elapsedMillis > PERIOD_MS*1.5) {
                        logger.warn("Pong timeout - the client is not responding.")
                        server.clientDisconnected(this@Client)
                        this@Client.close(CloseStatusCode.ProtocolError, message="Connection Timeout")
                    }
                }
            }
        }
    }

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
     */
    fun write(data: ByteArray, header: WebSocketHeader) {
        logger.debug("Sending frame ${header}.")
        val headerData = header.encode()
        val frameData = headerData + data
        header.length = data.size.toLong()
        outputStream.write(frameData)
    }

    /**
     * Send a ping
     */
    fun ping() {
        logger.info("Ping sent")
        write(ByteArray(0), WebSocketHeader(0, opcode=OpCode.Ping))
    }

    /**
     * Notify a client that a pong is received.
     *
     * This ex
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
        server.clientDisconnected(this, statusCode)
        heartbeatTimer?.cancel()
        // Kill the original worker job, we will create a special closing worker to handle closing protocol.
        runnerJob?.cancel()

        // After this, the client is completely de-associated with the WebSocketServer instance
        return server.coroutineScope?.launch {
            val client = this@Client
            val len = (2 + (message?.length ?: 0)).toLong()
            val outData = ByteArray(len.toInt())
            val buf = ByteBuffer.wrap(outData)
                .putShort(statusCode.value.toShort())
                .put(message?.encodeToByteArray() ?: byteArrayOf())


            write(buf.toByteArray(), WebSocketHeader(OpCode.Ping))
            withContext(Dispatchers.IO) {
                client.outputStream.close()
            }
            val header = withContext(Dispatchers.IO) {
                WebSocketHeader.decode(inputStream)
            }
            val status: Int? =
                if (header.length >= 2) {
                    ByteBuffer.wrap(receiveBytes(inputStream, header.length.toInt()))
                        .asShortBuffer()[0].toUShort().toInt()
                } else {
                    null
                }

            val exitMsg: String? =
                if (header.length > 2) {
                    receiveBytes(inputStream, (header.length - 2).toInt()).decodeToString()
                } else {
                    null
                }

            withContext(Dispatchers.IO) {
                inputStream.close()
            }

            logger.info("Clean connection negotiation completed for ${socket.inetAddress}:${socket.port}. " +
                    "Final status: $status." + (exitMsg ?: "")
            )

            withContext(Dispatchers.IO) {
                socket.close()
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
        socket.close()
        server.clientDisconnected(this, CloseStatusCode.AbnormalClosure)
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

    fun hasExtendedLen() = length > 125
    fun hasLongExtendedLen() = length > 65535

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
        ).joinToString(", ").let {
            if (it.isNotEmpty()) {
                ", $it"
            } else {
                it
            }
        }.let {
            return "WebSocketHeader:$length [op=${opCode}] (fin=$final, masked=$masked$it)"
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
 * to the user.
 */
class WebSocketFrame(val header: WebSocketHeader, internal val data: ByteArray) {
    /**
     * Get the content as utf-8 encoded text, as long as parsing it as such succeeds and the frame is valid to contain
     * utf-8 data.
     *
     * @return the utf-8 [String] if by spec the body must be utf-8, or null if decoding as utf-8 fails for frames
     * with optional bodies. It will also return null automatically
     * for opcodes that cannot correctly be interpreted as utf-8: [OpCode.Binary].
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
 */
// @Suppress("PrivatePropertyName", "PropertyName")
class WebSocketServer {
    companion object {
        internal val logger = Logger.getInstance(WebSocketServer::class.java)

        /**
         *
         */
        fun applyMasking(data: ByteArray, mask: ByteArray) {
            for (i in data.indices) {
                data[i] = data[i].xor(mask[(i % 4)])
            }
        }

        /**
         * How many messages can be queued in the Channel at once.
         */
        const val CHANNEL_BACKLOG: Int = 100
        private val HTTP_REQUEST_REGEX = Regex("GET (/\\w*)\\s+HTTP/[.\\d]+")
        private val HTTP_HEADER_REGEX = Regex("([-\\w]+):\\s+(.*)\\s*")
        private val HTTP_WEBSOCKET_EXT_REGEX = Regex("Sec-Web:\\s+(.*)\\s*")
    }

    internal var listenSocket: ServerSocket? = null
    private var _client: Client? = null
    internal var coroutineScope: CoroutineScope? = null
    internal var listenJob: Job? = null

    private var _eventChannel = Channel<String>(CHANNEL_BACKLOG)

    internal val client
        get() = _client

    // {{{ Public general api

    /**
     * Start listening on the given port. The servers are scoped to a project, so multiple can run at once in multiple
     * windows if needed.
     */
    fun start(port: Int) {
        listenSocket = ServerSocket(port)
        coroutineScope = CoroutineScope(Dispatchers.Default)

        listenJob = coroutineScope!!.launch(Dispatchers.Default) {
            acceptConnection(listenSocket!!)
        }
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
        _client = null
        _eventChannel.close()
        _eventChannel = Channel(CHANNEL_BACKLOG)
        listenSocket?.close()
        listenSocket = null
        val job = _client?.close(status, msg)
        job?.invokeOnCompletion {
            val scope = coroutineScope
            try {
                // Just in case any jobs are stragglers, we get rid of them now.
                scope?.cancel("Server shutdown")
            } catch(_: Throwable) {
            }
        }

        coroutineScope = null
        return job
    }

    // Public general api }}}

    // {{{ Public events api
    // Public events api }}}

    // {{{ Internal events api

    internal fun closeClientConnection(client: Client,
                              statusCode: CloseStatusCode = CloseStatusCode.Normal,
                              msg: String? = null): Job? {
        val job: Job? = client.close(statusCode, msg)
        if (client == this._client) {
            this._client = null
        }
        return job
    }

    internal fun clientConnected(client: Client) {
        this._client = client
    }

    internal fun clientHandshakeSuccess(client: Client) {

    }

    internal fun clientDisconnected(client: Client,
                                    statusCode: CloseStatusCode = CloseStatusCode.Normal) {
        if (client == this._client) {
            this._client = null
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
                if (clientSocket != null) {
                    val inputStream = withContext(Dispatchers.IO) {
                        BufferedInputStream(clientSocket.getInputStream())
                    }
                    val outputStream = withContext(Dispatchers.IO) {
                        clientSocket.getOutputStream()
                    }
                    logger.info("Incoming client connection")


                    val client = Client(clientSocket, inputStream, outputStream, this)
                    clientConnected(client)

                    withContext(Dispatchers.IO) {
                        doHandshake(client)
                    }.let {
                        if (this._client != null) {
                            this._client!!.runnerJob =
                                coroutineScope?.launch {
                                    clientWorker(client)
                                }
                        }
                    }
                }
                yield()
            }
        }
    }

    private suspend fun clientWorker(client: Client) {
        var frameId = 0

        // We use this for slowing checking down when we aren't currently getting many frames
        var lastContentFrameId = 0
        while (!client.socket.isClosed && this._client != null) {
            val frame = withContext(Dispatchers.IO) {
                parseFrame(client)
            }
            logger.debug(frame.header.toString())
            logger.debug("\tData: ${String(frame.data, Charset.forName("utf-8"))}")

            when (frame.header.opCode) {
                OpCode.Continuation -> {
                    val ex = WebSocketException("Got an unexpected OpCode.Continuation frame. " +
                            "This is a client or server bug!")
                    logger.error(ex)
                }
                OpCode.Text -> {
                    logger.info("Text Frame")
                }
                OpCode.Binary -> {
                    logger.info("Data Frame")
                }
                OpCode.Custom1, OpCode.Custom2, OpCode.Custom3, OpCode.Custom4, OpCode.Custom5 -> {
                    logger.info("Custom frame")
                }
                OpCode.Close -> {
                    logger.warn("Client requested WebSocket closure")
                    if (frame.data.size >= 2) {
                        val statusCode: Short = ByteBuffer.wrap(frame.data).order(ByteOrder.BIG_ENDIAN)
                            .asShortBuffer()[0]
                    }
                }
                OpCode.Ping -> {
                    logger.info("Ping received")
                    client.ping()
                }
                OpCode.Pong -> {
                    logger.info("Pong received")
                    client.pongReceived(frame)
                }
                else -> {
                    val error =  WebSocketException("Found a reserved opcode outside the valid range.")
                    logger.error(error)
                    throw error
                }
            }
            yield()
        }
    }

    private suspend fun parseFrameHeader(client: Client): WebSocketHeader = WebSocketHeader.decode(client.inputStream)

    private suspend fun parseFrame(client: Client): WebSocketFrame {
        var data = ByteArray(0)
        var header: WebSocketHeader

        do {
            header = withContext(Dispatchers.IO) {
                parseFrameHeader(client)
            }
            if (header.length != 0L) {
                val dataPart = receiveBytes(client, header.length.toInt())
                if (header.masked) {
                    applyMasking(dataPart, header.mask)
                }
                data = dataPart
            }

            if (header.length > Int.MAX_VALUE) {
                withContext(Dispatchers.Default) {
                    closeClientConnection(client, CloseStatusCode.FrameTooBig,
                        "Total frame size above INT_MAX is unsupported")
                }
            }
        } while (!header.final)

        return WebSocketFrame(header, data)
    }

    /**
     * Handle the HTTP protocol upgrade, before we can drop into a websocket connection.
     */
    private suspend fun doHandshake(client: Client) {
        val logger = Logger.getInstance(WebSocketServer::class.java)
        val reader = BufferedReader(NonOwningInputStreamReader(client.inputStream))

        val requestStr = withContext(Dispatchers.IO) {
            reader.readLine()
        }
        val getPath = HTTP_REQUEST_REGEX.matchEntire(requestStr)?.groups?.get(1)?.value

        if (getPath == null) {
            stop()
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
        val headersStr = headers.map { "${it.key}: ${it.value}" }.joinToString("\r\n", postfix="\r\n\r\n")
        logger.info("Got HTTP request: $requestStr\n$headersStr")

        if (headers["Upgrade"]?.get(0) == "websocket" && headers.containsKey("Sec-WebSocket-Key")) {
            sendHandshakeResponse(client, headers)
        } else {
            client.forceClose()
            if (client == this._client) {
                this._client = null
            }
        }
        headers.clear()
    }

    private suspend fun sendHandshakeResponse(client: Client, headers: Map<String, MutableList<String>>) {
        // The WebSocket standard itself defines this string
        val magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val responseLines = mutableListOf<String>()

        val secKey = headers["Sec-WebSocket-Key"]?.get(0)
        val extensions = headers["Sec-WebSocket-Extensions"]

        // The response key is required by spec to be a sha1 digest of what the client sends plus the magic string,
        // encoded in base-64.
        val responseKey = Base64.getEncoder().encode(
            MessageDigest.getInstance("SHA1").digest((secKey + magicString).toByteArray())
        )
        val responseKeyStr = String(responseKey)
        val lang = headers["Content-Language"]?.get(0)

        responseLines.addAll(listOf(
            "HTTP/1.1 101 Switching Protocols",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Content-Language: ${lang ?: "en-US"}",
            "Sec-WebSocket-Version: 13",
            "Sec-WebSocket-Accept: $responseKeyStr",
        ))

        val responseStr = responseLines.joinToString("\r\n", postfix="\r\n\r\n")
        logger.info("Protocol upgrade response:\n$responseStr")
        withContext(Dispatchers.IO) {
            client.outputStream.write(responseStr.encodeToByteArray())
            client.outputStream.flush()
        }

        val clientAddr = client.socket.inetAddress.toString()
        val port = client.socket.localPort

        client.handshakeComplete = true
    }

    /** Like receiveBytes at the package level, but uses the client. */
    private suspend fun receiveBytes(client: Client, len: Int): ByteArray {
        return receiveBytes(client.inputStream, len)
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

