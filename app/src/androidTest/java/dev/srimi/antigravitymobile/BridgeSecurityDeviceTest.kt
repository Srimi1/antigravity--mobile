package dev.srimi.antigravitymobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.srimi.antigravitymobile.bridge.BridgeProtocolException
import dev.srimi.antigravitymobile.bridge.BridgeSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeSecurityDeviceTest {
    @Test fun actualAndroidParserCannotAcceptMalformedBridgeInput() {
        // Tests packaged production parsing, not the JVM org.json dependency's different behavior.
        listOf("{a:1}", "{'a':1}", "{\"status\":\"failed\",\"status\":\"completed\"}", "{\"a\":1,}").forEach { raw ->
            // Direct parser is internal to the app module; instrumentation receives it through reflection.
            val method = BridgeSecurity::class.java.declaredMethods.single { it.name.startsWith("json") && it.parameterCount == 2 }
            method.isAccessible = true
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { method.invoke(BridgeSecurity, raw, 1024) }
            assertTrue(error.cause is BridgeProtocolException)
        }
    }
}
