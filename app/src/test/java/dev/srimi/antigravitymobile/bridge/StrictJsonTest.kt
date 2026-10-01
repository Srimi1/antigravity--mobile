package dev.srimi.antigravitymobile.bridge

import org.junit.Assert.*
import org.junit.Test

class StrictJsonTest {
    @Test fun androidLenientFormsAndDuplicateKeysAreRejected() {
        listOf("{a:1}", "{'a':1}", "{\"a\":1,}", "{\"a\"=1}", "{\"a\":1;\"b\":2}",
            "{\"a\":01}", "{\"a\":+1}", "{\"a\":NaN}", "{\"a\":1e999}", "{\"a\":\"\\uD800\"}",
            "{\"a\":1,\"\\u0061\":2}", "{\"a\":{\"status\":\"failed\",\"status\":\"completed\"}}", "{} trailing").forEach { json ->
            assertThrows(json, BridgeProtocolException::class.java) { BridgeSecurity.json(json) }
        }
    }
    @Test fun standardJsonKeepsTypesAndUnicode() {
        val value = BridgeSecurity.json("""{"id":9,"long":2147483648,"ratio":0.5,"none":null,"ok":true,"text":"\uD83C\uDFAE","array":["x",false]}""")
        assertEquals(9, value.get("id")); assertEquals(2147483648L, value.get("long"))
        assertEquals(0.5, value.get("ratio")); assertTrue(value.isNull("none")); assertEquals(true, value.get("ok"))
        assertEquals("🎮", value.getString("text")); assertEquals(false, value.getJSONArray("array").get(1))
        assertThrows(BridgeProtocolException::class.java) { BridgeSecurity.json("{\"a\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}") }
    }
}
