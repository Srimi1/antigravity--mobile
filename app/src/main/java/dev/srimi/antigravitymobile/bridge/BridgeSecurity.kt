package dev.srimi.antigravitymobile.bridge

import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** No raw frame, CLI output or pairing material is included in errors. */
class BridgeProtocolException : IOException("Local CLI bridge protocol or authentication failed")

object BridgeSecurity {
    const val MAX_BODY_BYTES = 128 * 1024
    const val MAX_FRAME_BYTES = 192 * 1024
    private val pairPattern = Regex("[A-Za-z0-9_-]{1,64}")
    internal fun checkPair(pair: String) { if (!pairPattern.matches(pair)) throw BridgeProtocolException() }
    internal fun nonce(): String = base64(ByteArray(32).also(SecureRandom()::nextBytes))
    fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    internal fun unbase64(value: String): ByteArray = guard {
        val result = Base64.getUrlDecoder().decode(value)
        if (base64(result) != value) throw BridgeProtocolException()
        result
    }
    internal fun mac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    internal fun proof(key: ByteArray, role: String, pair: String, server: String, client: String): ByteArray =
        mac(key, "agm-bridge-v1|$role|$pair|$server|$client")
    internal fun verify(expected: ByteArray, actual: String) {
        val decoded = unbase64(actual)
        if (decoded.size != 32 || !MessageDigest.isEqual(expected, decoded)) throw BridgeProtocolException()
    }
    internal fun json(value: String, limit: Int = MAX_FRAME_BYTES): JSONObject = guard {
        if (value.toByteArray(Charsets.UTF_8).size > limit) throw BridgeProtocolException()
        val tokener = JSONTokener(value)
        val result = tokener.nextValue() as? JSONObject ?: throw BridgeProtocolException()
        if (tokener.nextClean() != '\u0000') throw BridgeProtocolException()
        result
    }
    internal fun fields(value: JSONObject, expected: Set<String>) {
        if (value.keys().asSequence().toSet() != expected) throw BridgeProtocolException()
    }
    internal fun string(value: JSONObject, key: String): String = guard { value.get(key) as? String ?: throw BridgeProtocolException() }
    internal fun nonce(value: JSONObject, key: String): String = string(value, key).also {
        if (unbase64(it).size != 32) throw BridgeProtocolException()
    }
    internal fun header(value: JSONObject, type: String) {
        if (string(value, "type") != type || value.opt("v") != 1) throw BridgeProtocolException()
    }
    internal fun text(bytes: ByteArray): String = guard {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    internal inline fun <T> guard(block: () -> T): T = try { block() } catch (_: Exception) { throw BridgeProtocolException() }
}

/** Mutual nonce challenge proves the paired helper before the app sends any project or task. */
object BridgeHandshake {
    fun server(pairId: String, secret: ByteArray): Server = Server(pairId, secret)
    fun client(pairId: String, secret: ByteArray, challenge: String): Client = Client(pairId, secret, challenge)
    data class Accepted(val reply: String, val session: BridgeSession)

    class Server internal constructor(private val pair: String, secret: ByteArray) {
        private val key = secret.copyOf()
        private val nonce = BridgeSecurity.nonce()
        private var consumed = false
        init { BridgeSecurity.checkPair(pair); if (key.size != 32) throw BridgeProtocolException() }
        val challenge: String = JSONObject().put("type", "challenge").put("v", 1).put("pairId", pair).put("nonce", nonce).toString()
        @Synchronized fun accept(hello: String): Accepted = BridgeSecurity.guard {
            if (consumed) throw BridgeProtocolException()
            val value = BridgeSecurity.json(hello, 1024)
            BridgeSecurity.fields(value, setOf("type", "v", "pairId", "nonce", "proof"))
            BridgeSecurity.header(value, "hello")
            if (BridgeSecurity.string(value, "pairId") != pair) throw BridgeProtocolException()
            val client = BridgeSecurity.nonce(value, "nonce")
            BridgeSecurity.verify(BridgeSecurity.proof(key, "client", pair, nonce, client), BridgeSecurity.string(value, "proof"))
            consumed = true
            val reply = JSONObject().put("type", "paired").put("v", 1)
                .put("proof", BridgeSecurity.base64(BridgeSecurity.proof(key, "server", pair, nonce, client))).toString()
            Accepted(reply, BridgeSession(BridgeSecurity.proof(key, "session", pair, nonce, client), "server", "client"))
        }
    }
    class Client internal constructor(private val pair: String, secret: ByteArray, challenge: String) {
        private val key = secret.copyOf()
        private val server: String
        private val nonce = BridgeSecurity.nonce()
        private var consumed = false
        init {
            BridgeSecurity.checkPair(pair)
            if (key.size != 32) throw BridgeProtocolException()
            val value = BridgeSecurity.json(challenge, 1024)
            BridgeSecurity.fields(value, setOf("type", "v", "pairId", "nonce"))
            BridgeSecurity.header(value, "challenge")
            if (BridgeSecurity.string(value, "pairId") != pair) throw BridgeProtocolException()
            server = BridgeSecurity.nonce(value, "nonce")
        }
        val hello: String = JSONObject().put("type", "hello").put("v", 1).put("pairId", pair).put("nonce", nonce)
            .put("proof", BridgeSecurity.base64(BridgeSecurity.proof(key, "client", pair, server, nonce))).toString()
        @Synchronized fun finish(reply: String): BridgeSession = BridgeSecurity.guard {
            if (consumed) throw BridgeProtocolException()
            val value = BridgeSecurity.json(reply, 1024)
            BridgeSecurity.fields(value, setOf("type", "v", "proof")); BridgeSecurity.header(value, "paired")
            BridgeSecurity.verify(BridgeSecurity.proof(key, "server", pair, server, nonce), BridgeSecurity.string(value, "proof"))
            consumed = true
            BridgeSession(BridgeSecurity.proof(key, "session", pair, server, nonce), "client", "server")
        }
    }
}

/** Direction-bound authenticated frames. Each connection has fresh nonces and monotonic sequence numbers. */
class BridgeSession internal constructor(private val key: ByteArray, private val sendRole: String, private val receiveRole: String) {
    private var sent = 0L
    private var received = 0L
    @Synchronized fun encode(value: JSONObject): String {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > BridgeSecurity.MAX_BODY_BYTES || sent == Long.MAX_VALUE) throw BridgeProtocolException()
        val sequence = sent + 1
        val body = BridgeSecurity.base64(bytes)
        val tag = BridgeSecurity.base64(BridgeSecurity.mac(key, "agm-frame-v1|$sendRole|$sequence|$body"))
        val frame = JSONObject().put("seq", sequence).put("body", body).put("tag", tag).toString()
        if (frame.length > BridgeSecurity.MAX_FRAME_BYTES) throw BridgeProtocolException()
        sent = sequence
        return frame
    }
    @Synchronized fun decode(frame: String): JSONObject = BridgeSecurity.guard {
        val value = BridgeSecurity.json(frame)
        BridgeSecurity.fields(value, setOf("seq", "body", "tag"))
        val sequence = when (val number = value.get("seq")) {
            is Int -> number.toLong(); is Long -> number; else -> throw BridgeProtocolException()
        }
        if (received == Long.MAX_VALUE || sequence != received + 1) throw BridgeProtocolException()
        val body = BridgeSecurity.string(value, "body")
        BridgeSecurity.verify(BridgeSecurity.mac(key, "agm-frame-v1|$receiveRole|$sequence|$body"), BridgeSecurity.string(value, "tag"))
        val decoded = BridgeSecurity.unbase64(body)
        if (decoded.size > BridgeSecurity.MAX_BODY_BYTES) throw BridgeProtocolException()
        val result = BridgeSecurity.json(BridgeSecurity.text(decoded), BridgeSecurity.MAX_BODY_BYTES)
        received = sequence
        result
    }
}
