package dev.srimi.antigravitymobile.runtime

/** Removes peer-to-peer network APIs before project scripts. Defense in depth only. */
object WebGuard {
    val SCRIPT = """
        (() => {
            const keys = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'WebTransport'];
            const remove = w => { for (const key of keys) { try {
                Object.defineProperty(w, key, { value: undefined, writable: false, configurable: false });
            } catch (e) {} } };
            remove(window);
            for (const name of ['contentWindow', 'contentDocument']) {
                const original = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, name);
                if (!original || !original.get) continue;
                try { Object.defineProperty(HTMLIFrameElement.prototype, name, { configurable: false, get() {
                    const value = original.get.call(this);
                    try { remove(name === 'contentWindow' ? value : value && value.defaultView); } catch (e) {}
                    return value;
                } }); } catch (e) {}
            }
        })();
    """.trimIndent()
    // One line, so console line numbers still match the saved file.
    private val tag = "<script>${SCRIPT.lines().joinToString(" ") { it.trim() }}</script>".toByteArray()

    /** Inserts the guard after an optional BOM/doctype so the page keeps standards mode. */
    fun inject(html: ByteArray): ByteArray {
        var start = 0
        if (html.size >= 3 && html[0] == 0xEF.toByte() && html[1] == 0xBB.toByte() && html[2] == 0xBF.toByte()) start = 3
        var i = start
        while (i < html.size && html[i].toInt().toChar().isWhitespace()) i++
        val prefix = "<!doctype"
        val hasDoctype = html.size - i >= prefix.length &&
            String(html, i, prefix.length, Charsets.ISO_8859_1).lowercase() == prefix
        val at = if (hasDoctype) html.indexOf('>'.code.toByte(), i).let { if (it < 0) start else it + 1 } else start
        return html.copyOfRange(0, at) + tag + html.copyOfRange(at, html.size)
    }
    private fun ByteArray.indexOf(value: Byte, from: Int): Int { for (j in from until size) if (this[j] == value) return j; return -1 }
}
