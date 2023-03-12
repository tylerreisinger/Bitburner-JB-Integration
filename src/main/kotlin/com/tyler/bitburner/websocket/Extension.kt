package com.tyler.bitburner.websocket

import java.util.*
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.experimental.and

data class ExtensionDeclaration(val name: String, val args: Map<String, String?> = mapOf()) {
    override fun toString(): String {
        return name + args.map { it.key + if (it.value != null) "=${it.value}" else "" }.joinToString("; ")
    }
}

interface ProtocolExtension {
    val name: String

    fun beforeFrameSend(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame
    fun afterFrameReceived(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame
}

interface ProtocolExtensionDefinition {
    fun newInstance(client: Client): ProtocolExtension
}

class PerMessageDeflate(val compressCustomOps: Boolean = false): ProtocolExtension {
    override val name: String
        get() = "permessage-deflate"

    private val deflater = Deflater()
    private val inflater = Inflater()

    companion object {
        const val DEFLATE_MIN_SIZE = 80
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

class PerMessageDeflateDefinition(var compressCustomOps: Boolean = false):
    ProtocolExtensionDefinition {

    override fun newInstance(client: Client): ProtocolExtension =
        PerMessageDeflate(compressCustomOps)
}
