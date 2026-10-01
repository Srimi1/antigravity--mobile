package dev.srimi.antigravitymobile.bridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class BridgeSecurityTest {
    private fun secret() = ByteArray(32).also(SecureRandom()::nextBytes)

    @Test fun pairingProvesBothSidesAndFramesCannotBeReplayed() {
        val key = secret()
        val server = BridgeHandshake.server("pair-1", key)
        val client = BridgeHandshake.client("pair-1", key, server.challenge)
        val accepted = server.accept(client.hello)
        val outgoing = client.finish(accepted.reply)
        val incoming = accepted.session
        val frame = outgoing.encode(JSONObject().put("op", "observe").put("taskId", "task-1"))
        assertEquals("task-1", incoming.decode(frame).getString("taskId"))
        assertThrows(BridgeProtocolException::class.java) { incoming.decode(frame) }
        assertEquals("ready", outgoing.decode(incoming.encode(JSONObject().put("state", "ready"))).getString("state"))
    }

    @Test fun wrongPairAndForgedServerFailBeforeCommands() {
        val server = BridgeHandshake.server("pair-1", secret())
        val stranger = BridgeHandshake.client("pair-1", secret(), server.challenge)
        assertThrows(BridgeProtocolException::class.java) { server.accept(stranger.hello) }
        val key = secret()
        val legitimate = BridgeHandshake.server("pair-1", key)
        assertThrows(BridgeProtocolException::class.java) { BridgeHandshake.client("pair-2", key, legitimate.challenge) }
        val client = BridgeHandshake.client("pair-1", key, legitimate.challenge)
        assertThrows(BridgeProtocolException::class.java) {
            client.finish(JSONObject().put("type", "paired").put("proof", BridgeSecurity.base64(secret())).toString())
        }
    }

    @Test fun alteredBodySkippedSequenceAndMalformedJsonAreRejected() {
        val key = secret()
        val server = BridgeHandshake.server("pair-1", key)
        val client = BridgeHandshake.client("pair-1", key, server.challenge)
        val accepted = server.accept(client.hello)
        val session = client.finish(accepted.reply)
        val first = session.encode(JSONObject().put("op", "cancel"))
        val changed = JSONObject(first).put("body", BridgeSecurity.base64("{\"op\":\"start\"}".toByteArray())).toString()
        assertThrows(BridgeProtocolException::class.java) { accepted.session.decode(changed) }
        // Invalid frames do not consume the expected sequence.
        assertEquals("cancel", accepted.session.decode(first).getString("op"))
        val skipped = JSONObject(session.encode(JSONObject().put("op", "observe"))).put("seq", 3).toString()
        assertThrows(BridgeProtocolException::class.java) { accepted.session.decode(skipped) }
        assertThrows(BridgeProtocolException::class.java) { accepted.session.decode("{} trailing") }
    }

    @Test fun oversizedEventsAndUnknownEnvelopeFieldsFailClosed() {
        val key = secret()
        val server = BridgeHandshake.server("pair-1", key)
        val client = BridgeHandshake.client("pair-1", key, server.challenge)
        val accepted = server.accept(client.hello)
        val session = client.finish(accepted.reply)
        assertThrows(BridgeProtocolException::class.java) { session.encode(JSONObject().put("text", "x".repeat(BridgeSecurity.MAX_BODY_BYTES + 1))) }
        val frame = JSONObject(session.encode(JSONObject().put("op", "observe"))).put("extra", true).toString()
        assertThrows(BridgeProtocolException::class.java) { accepted.session.decode(frame) }
        assertThrows(BridgeProtocolException::class.java) { BridgeHandshake.server("../pair", key) }
    }
}
