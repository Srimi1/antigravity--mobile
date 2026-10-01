package dev.srimi.antigravitymobile.bridge

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PairedBridgeConnectionTest {
    @Test fun authenticatedSocketRoundTripAndCancellationOfBlockedRead() = runBlocking {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val observed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    val handshake = BridgeHandshake.server("pair-1", key)
                    writer.appendLine(handshake.challenge); writer.flush()
                    val accepted = handshake.accept(reader.readLine())
                    writer.appendLine(accepted.reply); writer.flush()
                    val first = accepted.session.decode(reader.readLine())
                    writer.appendLine(accepted.session.encode(JSONObject().put("taskId", first.getString("taskId")))); writer.flush()
                    accepted.session.decode(reader.readLine()); observed.countDown()
                    if (reader.readLine() == null) closed.countDown()
                }
            }
            PairedBridgeConnection.connect(server.localPort, "pair-1", key).use { connection ->
                assertEquals("task-1", connection.call(JSONObject().put("op", "status").put("taskId", "task-1")).getString("taskId"))
                val pending = launch { connection.call(JSONObject().put("op", "observe").put("taskId", "task-1")) }
                withContext(Dispatchers.IO) { assertTrue(observed.await(2, TimeUnit.SECONDS)) }
                withTimeout(1000) { pending.cancelAndJoin() }
                withContext(Dispatchers.IO) { assertTrue(closed.await(1, TimeUnit.SECONDS)) }
            }
            worker.join(1000)
        }
    }
}
