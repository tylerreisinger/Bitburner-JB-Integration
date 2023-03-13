package com.tyler.bitburner.websocket

import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.experimental.and


data class ExtensionArg(val name: String, val value: String? = null) {
    override fun toString(): String =
        name + (if (value != null) "=$value" else "")
}

data class ExtensionProposal(val name: String, val arguments: List<ExtensionArg>) {
    override fun toString(): String =
        "$name; " + arguments.joinToString("; ")
}

data class ExtensionDeclaration(val name: String, val args: List<ExtensionProposal>) {
    override fun toString(): String =
        args.joinToString(",\n")
}

interface ProtocolExtension {
    val name: String

    fun beforeFrameSend(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame
    fun afterFrameReceived(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame
}

interface ProtocolExtensionDefinition {
    val name: String
    fun newInstance(client: Client): ProtocolExtension

    fun extensionNegotiation(extension: ExtensionDeclaration): ExtensionProposal?
}

open class PerMessageDeflate(val compressCustomOps: Boolean = false): ProtocolExtension {
    override val name: String
        get() = "permessage-deflate"

    val definition
        get() = PerMessageDeflateDefinition::class

    private val deflater = Deflater()
    private val inflater = Inflater()

    companion object {
        /**
         * We won't compress on our end ever if the payload len is below this. Configurable,
         * but compression of small text blocks is adding extra latency for little gain.
         */
        const val DEFLATE_MIN_SIZE = 50
    }

    override fun beforeFrameSend(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame {
        // If the payload is small enough, there's no point in compression
        if (frame.data.size < DEFLATE_MIN_SIZE) {
            return frame
        }
        // Control frames are forbidden from being compressed
        if (frame.header.isControlFrame() || (frame.header.isCustomOpFrame() && !compressCustomOps)) {
            return frame
        }
        frame.header.resv1 = true
        deflater.setInput(frame.data)
        var output = ByteArray(frame.data.size)
        deflater.deflate(output)
        while (!deflater.needsInput()) {
            val rest = ByteArray(frame.data.size)
            output += rest
        }

        if (output[output.size-4] == 0.toByte() && output[output.size-3] == 0.toByte()
            && output[output.size-2] == 0xFF.toByte() && output[output.size-1] == 0xFF.toByte()
            && frame.header.final) {
            val checkIdxStart = output.size-5

            if (output[checkIdxStart].and(0xF8.toByte()) == 0.toByte()) {
                var trailingZeroes = 0
                for (shift in 1..5) {
                    if (output[checkIdxStart].toInt().shr(shift).toByte().and(0xF8.toByte()) == 0.toByte()) {
                        trailingZeroes = 3 + shift
                    }
                    output[checkIdxStart] = output[checkIdxStart].and(4.shl(trailingZeroes - 3).toByte())
                    output = output.copyOf(output.size - 4)
                }
            } else {
                output = output.copyOf(output.size - 3)
                output[output.size-1] = 0x80.toByte()
            }
        }

        return WebSocketFrame(frame.header, output)
    }

    override fun afterFrameReceived(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame {
        if (frame.header.resv1) {
            if (frame.header.isControlFrame()) {
                throw WebSocketException("Received a control frame with resv1 set. This is not allowed by the spec")
            }
            inflater.setInput(frame.bytes)
            var output = ByteArray(frame.data.size)
            inflater.inflate(output)
            while (!inflater.needsInput()) {
                val rest = ByteArray(frame.data.size)
                inflater.inflate(rest)
                output += rest
            }
        }
        return frame
    }
}

@Suppress("MemberVisibilityCanBePrivate")
open class PerMessageDeflateDefinition(val compressCustomOps: Boolean = false,
                                       val serverNoCtxTakeover: Boolean = false): ProtocolExtensionDefinition {
    override val name: String
        get() = "permessage-deflate"

    open val maxWindowBitsDefault: Int?
        get() = 15

    override fun newInstance(client: Client): ProtocolExtension =
        PerMessageDeflate(compressCustomOps)

    override fun extensionNegotiation(extension: ExtensionDeclaration): ExtensionProposal? {
        return if (extension.args.isNotEmpty()) {
            val proposal = extension.args[0]

            proposal
        } else {
            // No parameters, we use our default that are allowed without client prompt
            if (serverNoCtxTakeover) {
                ExtensionProposal(extension.name, listOf(ExtensionArg("server_no_context_takeover")))
            } else {
               ExtensionProposal(extension.name,
                    listOf(ExtensionArg("server_max_window_bits", maxWindowBitsDefault.toString())))
            }
        }
    }
}
