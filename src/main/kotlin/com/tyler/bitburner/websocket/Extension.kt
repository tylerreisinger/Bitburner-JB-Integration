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

    operator fun contains(argName: String): Boolean =
        arguments.find { it.name == argName } != null

    operator fun get(name: String): ExtensionArg? = arguments.find { it.name == name }

    companion object {
        private val MATCHER_REGEX = Regex("([\\w-]+)(=[*?])?(.+)?")
    }

    /**
     * Find an argument in the proposal that satisfies the pattern.
     *
     * The pattern is a string starting with the literal name of the argument,
     * and followed by an optional value specifier.
     *
     * For pattern values, the following are valid formats:
     *
     * * name={literal text} - match both the name and literal value for an argument
     * * name=* - match the name, having any not-null value
     * * name=? - match the name, having any value or no value
     * * name - match the name, having no value
     *
     * If a match fails, the return is null. Otherwise, a [ExtensionArg] instance is returned with the value set to
     * the stored value, if appropriate based on the selectors.
     *
     * [predicate] can be used to do more advanced value selection, such as making sure it is an integer or falls
     * within a specific range.
     *
     * @param pattern the string pattern to match against.
     * @param predicate given the argument value, return whether to accept it as a match.
     * If it returns false, any further matching is stopped and null is returned.
     */
    fun matchArg(
        pattern: String,
        predicate: ((valStr: String) -> Boolean)? = null
    ): ExtensionArg? {
        val match = MATCHER_REGEX.find(pattern)

        val name = match?.groups?.get(1)?.value ?: return null
        val opStr = match.groups[2]?.value
        val valStr = match.groups[3]?.value.let { if (it.isNullOrEmpty()) null else it }

        val protoArg = get(name) ?: return null

        if (!valStr.isNullOrEmpty() && predicate != null && protoArg.value != null) {
            if (!predicate(protoArg.value)) {
                return null
            }
        }

        return when (opStr) {
            "=" ->
                if (valStr.isNullOrEmpty() || valStr != protoArg.value) {
                    null
                } else {
                    ExtensionArg(name, valStr)
                }
            "=*" ->
                if (valStr != null) {
                    ExtensionArg(name, valStr)
                } else {
                    null
                }
            "=?" ->
                ExtensionArg(name, valStr)
            null, "" -> if (valStr.isNullOrEmpty()) ExtensionArg(name, null) else null
            else -> throw IllegalArgumentException("Invalid")
        }
    }
}

data class ExtensionDeclaration(val name: String, val args: List<ExtensionProposal>) {
    override fun toString(): String =
        args.joinToString(",\n")
}

/**
 * A defined extension to the protocol, operating at the WebSocket frame level. These are part of the WebSocket spec.
 * This is the per-client implementation to actually do the work.
 *
 * The WebSocket version 13 protocol technically forbids custom extensions not registered by IANA,
 * however we allow them as long as both the client and server reference them.
 */
interface ProtocolExtension {
    /**
     * The string name of the extension as is passed in the websocket negotiation headers
     *
     * This should _not_ return anything other than a constant value. Assume it should act as if it were in a
     * companion object.
     */
    val name: String

    /**
     * Get the frame as-is based on what was received, server processing rules and any processing from extensions
     * before this one in order. This is before any notifications or processing based on this frame.
     *
     * You are able to return a completely different [WebSocketFrame] or the same but which has been modified.
     * References to the original frame sent here is considered no longer valid and replaced
     * by the returned frame. Thus writing to [frame] it is not a concern.
     *
     * @param frame the frame to sent, to be referenced and modified if needed, or passed along. The variable holding it
     * is never referenced again, so it is free to either modify and return the same object or return a different one.
     * @param server the server that is trying to send and processed the frame
     *
     * @return the frame to use going forward. You can simply pass through the original, or modify or replace it
     * as necessary
     */
    fun beforeFrameSend(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame

    /**
     * Get a frame that has been received, processed by the server and any extensions before this one.
     *
     * The [frame] can be considered unused in content at this point. No notifications or interpretation of content
     * has been done or sent to the application layer. You are able to return a completely different [WebSocketFrame]
     * or the same but which has been modified. References to the original frame sent here are considered no longer
     * valid, and replaced by the returned frame. Thus writing to [frame] is not a concern
     *
     * @param frame the received frame after processing by the server and previous extensions but before use.
     * @param server the server that received and processed this frame
     *
     * @return the frame to use going forward. You can simply pass through the original, or modify or replace it
     * as necessary
     */
    fun afterFrameReceived(frame: WebSocketFrame, server: WebSocketServer): WebSocketFrame
}

/**
 * A definition of a WebSocket protocol definition.
 *
 * This is a factory that can take default params
 * to pass to the [ProtocolExtension] instances it creates per [Client]. It has additional functionality of providing
 * a constant name and ability to read the client request extension proposal, and return an agreed proposal.
 */
interface ProtocolExtensionDefinition {
    /**
     * The name of the extension, as registered by IANA or a name the client and server both recognize.
     *
     * This must return the same string on every call, with no exceptions
     */
    val name: String


    fun newInstance(client: Client): ProtocolExtension

    fun extensionNegotiation(extension: ExtensionDeclaration): ExtensionProposal?
}

/**
 * The default permessage-deflate extension handler.
 *
 * @param compressCustomOps set to true if we want the custom opcodes [OpCode.Custom1]..[OpCode.Custom5] to be
 * considered in having their payload compressed when sent by the server.
 * We don't know anything about custom opcodes in the server
 * itself, so these will be treated as black boxes to be interpreted by the application level.
 */
open class PerMessageDeflate(val compressCustomOps: Boolean = false): ProtocolExtension {
    override val name: String
        get() = PerMessageDeflate.name

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

        val name: String
            get() = "permessage-deflate"
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
            val outArgs: MutableList<ExtensionArg> = mutableListOf()

            if (proposal.matchArg(Arg.ServNoCtxTakeover.text) != null) {
                outArgs.add(ExtensionArg(Arg.ServNoCtxTakeover.text))
            }
            if (proposal.matchArg(Arg.ClientNoCtxTakeover.text) != null) {
                outArgs.add(ExtensionArg(Arg.ClientNoCtxTakeover.text))
            }

            val servMaxWindowArg = proposal.matchArg("${Arg.ServMaxWindowBits}=?")
            val clientMaxWindowArg = proposal.matchArg("${Arg.ClientMaxWindowBits}=?")

            if (servMaxWindowArg?.value != null && outArgs.find { it.name == Arg.ServNoCtxTakeover.text } == null) {
                // Currently we only support 15 here. Thanks, Java.
                // This actually in practice likely doesn't matter outside of very resource limited servers/clients,
                // as 15 corresponds to only retaining 32 KiB per client.
                if (servMaxWindowArg.value.toInt() == 15) {
                    outArgs.add(ExtensionArg(Arg.ServMaxWindowBits.text, "15"))
                }
            }

            if (clientMaxWindowArg != null && outArgs.find { it.name == Arg.ClientNoCtxTakeover.text } == null) {
                val value = clientMaxWindowArg.value

                if (value == null || value.toInt() in 8..15) {
                    outArgs.add(ExtensionArg(Arg.ClientMaxWindowBits.text, "15"))
                }
            }

            proposal
        } else {
            // No parameters, we use our default that are allowed without client prompt
            if (serverNoCtxTakeover) {
                ExtensionProposal(extension.name, listOf(ExtensionArg(Arg.ServNoCtxTakeover.text)))
            } else {
               ExtensionProposal(extension.name,
                    listOf(ExtensionArg(Arg.ServMaxWindowBits.text, maxWindowBitsDefault.toString())))
            }
        }
    }

    enum class Arg(val text: String) {
        ServNoCtxTakeover("server_no_context_takeover"),
        ClientNoCtxTakeover("client_no_context_takeover"),
        ServMaxWindowBits("server_max_window_bits"),
        ClientMaxWindowBits("client_max_window_bits");

        override fun toString(): String = text
    }
}
