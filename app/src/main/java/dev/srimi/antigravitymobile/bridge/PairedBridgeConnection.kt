package dev.srimi.antigravitymobile.bridge

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BridgeDisconnected : IOException("Local CLI bridge disconnected; recorded actions must be checked before reconnecting")

/** The endpoint is always numeric loopback. Cancellation closes blocking socket I/O immediately. */
class PairedBridgeConnection private constructor(private val socket: Socket) : Closeable {
    private val closed = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agm-paired-bridge").apply { isDaemon = true } }
    private val mutex = Mutex()
    private lateinit var input: BufferedInputStream
    private lateinit var output: BufferedOutputStream
    private lateinit var session: BridgeSession

    companion object {
        suspend fun connect(port: Int, pairId: String, secret: ByteArray): PairedBridgeConnection {
            require(port in 1024..65535)
            val connection = PairedBridgeConnection(Socket(Proxy.NO_PROXY))
            try {
                connection.io {
                    connection.socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 5000)
                    connection.socket.soTimeout = 10_000
                    connection.input = BufferedInputStream(connection.socket.getInputStream())
                    connection.output = BufferedOutputStream(connection.socket.getOutputStream())
                    val hello = BridgeHandshake.client(pairId, secret, connection.read(1024))
                    connection.write(hello.hello)
                    connection.session = hello.finish(connection.read(1024))
                }
                return connection
            } catch (error: Exception) { connection.close(); throw error }
        }
    }
    suspend fun call(request: JSONObject): JSONObject = mutex.withLock {
        io {
            write(session.encode(request))
            session.decode(read(BridgeSecurity.MAX_FRAME_BYTES))
        }
    }
    private suspend fun <T> io(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        try {
            executor.execute {
                if (!continuation.isActive) return@execute
                try {
                    val result = block()
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    close()
                    if (continuation.isActive) continuation.resumeWithException(if (error is BridgeProtocolException) error else BridgeDisconnected())
                }
            }
        } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(BridgeDisconnected()) }
    }
    private fun read(maximum: Int): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) throw BridgeDisconnected()
            if (next == 10) return BridgeSecurity.text(bytes.toByteArray())
            if (bytes.size() >= maximum) throw BridgeProtocolException()
            bytes.write(next)
        }
    }
    private fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > BridgeSecurity.MAX_FRAME_BYTES) throw BridgeProtocolException()
        output.write(bytes); output.write(10); output.flush()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        executor.shutdownNow()
    }
}
