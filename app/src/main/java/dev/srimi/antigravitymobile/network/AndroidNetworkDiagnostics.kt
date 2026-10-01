package dev.srimi.antigravitymobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import dev.srimi.antigravitymobile.providers.DiagnosticReport
import dev.srimi.antigravitymobile.providers.NetworkDiagnostics
import dev.srimi.antigravitymobile.providers.PrivateDnsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Credential-free reachability probes on the active network, using Android's documented ConnectivityManager
 * state and callbacks (https://developer.android.com/develop/connectivity/network-ops/reading-network-state).
 * Only the hostname is used: no URL paths, headers, keys or request bodies.
 */
class AndroidNetworkDiagnostics(context: Context) : NetworkDiagnostics {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val changes = ArrayDeque<Long>()
    @Volatile private var current: Network? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "network-diagnostics").apply { isDaemon = true } }

    init {
        // Without ACCESS_NETWORK_STATE the callback cannot be registered; reports then say the network is unknown.
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (current != null && current != network) changed(); current = network }
                override fun onLost(network: Network) { if (current == network) { current = null; changed() } }
            })
        }
    }

    private fun changed() = synchronized(changes) {
        val now = System.currentTimeMillis()
        changes.addLast(now)
        while (changes.isNotEmpty() && now - changes.first() > WINDOW_MS) changes.removeFirst()
    }
    private fun recentChanges(): Int = synchronized(changes) { val now = System.currentTimeMillis(); changes.count { now - it <= WINDOW_MS } }

    override suspend fun diagnose(host: String, port: Int): DiagnosticReport = withContext(Dispatchers.IO) {
        require(host.matches(Regex("[A-Za-z0-9.-]{1,253}"))) { "Not a hostname" }
        val network = runCatching { connectivity?.activeNetwork }.getOrNull()
        val caps = network?.let { runCatching { connectivity?.getNetworkCapabilities(it) }.getOrNull() }
        val link = network?.let { runCatching { connectivity?.getLinkProperties(it) }.getOrNull() }
        val transport = caps?.let(::transport)
        val (privateDns, server) = privateDns(link)
        var facts = NetworkFacts(host, transport, network != null && caps != null,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            privateDns, server, link?.isPrivateDnsActive == true, emptyList(), null, null, null, networkChanges = recentChanges())
        if (network == null) return@withContext DiagnosisRules.explain(facts)

        val addresses = timed { network.getAllByName(host).toList() }
        facts = facts.copy(dnsOk = !addresses.isNullOrEmpty(), resolvedAddresses = addresses.orEmpty().map { it.hostAddress.orEmpty() }.take(4))
        if (addresses.isNullOrEmpty()) return@withContext DiagnosisRules.explain(facts)

        val socket = connect(network, addresses, port)
        facts = facts.copy(tcpOk = socket != null)
        if (socket == null) return@withContext DiagnosisRules.explain(facts)

        var tlsError: String? = null
        val tlsOk = try {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true).use { tls ->
                tls as SSLSocket
                tls.soTimeout = PROBE_TIMEOUT_MS
                tls.startHandshake()
                HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session).also { if (!it) tlsError = "certificate does not match the host" }
            }
        } catch (error: Exception) {
            tlsError = error.javaClass.simpleName; false
        } finally { runCatching { socket.close() } }
        DiagnosisRules.explain(facts.copy(tlsOk = tlsOk, tlsError = tlsError, networkChanges = recentChanges()))
    }

    private fun connect(network: Network, addresses: List<InetAddress>, port: Int): java.net.Socket? {
        for (address in addresses.take(2)) {
            val socket = network.socketFactory.createSocket()
            try { socket.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MS); return socket }
            catch (_: Exception) { runCatching { socket.close() } }
        }
        return null
    }

    /** Blocking work with a hard deadline; DNS lookups cannot be interrupted, so the thread is left to finish. */
    private fun <T> timed(block: () -> T): T? = try {
        pool.submit<T> { block() }.get(PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    } catch (_: Exception) { null }

    private fun transport(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other"
    }

    /** Settings value "off" / "opportunistic" / "hostname" when readable, otherwise inferred from LinkProperties. */
    private fun privateDns(link: LinkProperties?): Pair<PrivateDnsMode, String?> {
        val server = link?.privateDnsServerName
        val setting = runCatching { Settings.Global.getString(app.contentResolver, "private_dns_mode") }.getOrNull()
        val mode = when (setting) {
            "off" -> PrivateDnsMode.Off
            "opportunistic" -> PrivateDnsMode.Automatic
            "hostname" -> PrivateDnsMode.Strict
            else -> when {
                server != null -> PrivateDnsMode.Strict
                link?.isPrivateDnsActive == true -> PrivateDnsMode.Automatic
                else -> PrivateDnsMode.Unknown
            }
        }
        val name = server ?: if (mode == PrivateDnsMode.Strict)
            runCatching { Settings.Global.getString(app.contentResolver, "private_dns_specifier") }.getOrNull() else null
        return mode to name?.take(253)
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val WINDOW_MS = 3 * 60_000L
        @Volatile private var instance: AndroidNetworkDiagnostics? = null
        /** One instance per process, so the network callback is registered once. */
        fun shared(context: Context): AndroidNetworkDiagnostics =
            instance ?: synchronized(this) { instance ?: AndroidNetworkDiagnostics(context).also { instance = it } }
    }
}
