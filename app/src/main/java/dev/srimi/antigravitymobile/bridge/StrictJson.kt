package dev.srimi.antigravitymobile.bridge

import org.json.JSONArray
import org.json.JSONObject

/** Android JSONTokener accepts duplicate/unquoted keys and non-JSON syntax. Bridge input must not. */
internal class StrictJson(private val source: String) {
    private var position = 0
    private var values = 0
    fun objectValue(): JSONObject {
        val result = value(0) as? JSONObject ?: throw BridgeProtocolException()
        space()
        if (position != source.length) throw BridgeProtocolException()
        return result
    }
    private fun space() { while (position < source.length && source[position] in " \t\r\n") position++ }
    private fun take(character: Char): Boolean {
        space()
        if (position < source.length && source[position] == character) { position++; return true }
        return false
    }
    private fun expect(character: Char) { if (!take(character)) throw BridgeProtocolException() }
    private fun value(depth: Int): Any {
        if (depth > 64 || ++values > 50_000) throw BridgeProtocolException()
        space()
        if (position >= source.length) throw BridgeProtocolException()
        return when (source[position]) {
            '{' -> {
                position++
                val result = JSONObject(); val keys = mutableSetOf<String>()
                if (!take('}')) do {
                    val key = string()
                    if (!keys.add(key)) throw BridgeProtocolException()
                    expect(':'); result.put(key, value(depth + 1))
                    if (take('}')) break
                    expect(',')
                } while (true)
                result
            }
            '[' -> {
                position++
                val result = JSONArray()
                if (!take(']')) do {
                    result.put(value(depth + 1))
                    if (take(']')) break
                    expect(',')
                } while (true)
                result
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JSONObject.NULL)
            '-', in '0'..'9' -> number()
            else -> throw BridgeProtocolException()
        }
    }
    private fun literal(text: String, result: Any): Any {
        if (!source.startsWith(text, position)) throw BridgeProtocolException()
        position += text.length
        return result
    }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (position < source.length) {
            val character = source[position++]
            when {
                character == '"' -> return result.toString().also { text ->
                    var index = 0
                    while (index < text.length) {
                        val value = text[index++]
                        if (value.isHighSurrogate()) {
                            if (index == text.length || !text[index++].isLowSurrogate()) throw BridgeProtocolException()
                        } else if (value.isLowSurrogate()) throw BridgeProtocolException()
                    }
                }
                character == '\\' -> {
                    if (position >= source.length) throw BridgeProtocolException()
                    result.append(when (val escape = source[position++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            if (position + 4 > source.length) throw BridgeProtocolException()
                            val value = source.substring(position, position + 4)
                            if (value.any { it !in "0123456789abcdefABCDEF" }) throw BridgeProtocolException()
                            position += 4; value.toInt(16).toChar()
                        }
                        else -> throw BridgeProtocolException()
                    })
                }
                character < ' ' -> throw BridgeProtocolException()
                else -> result.append(character)
            }
        }
        throw BridgeProtocolException()
    }
    private fun number(): Number {
        val start = position
        if (source[position] == '-') position++
        if (position >= source.length) throw BridgeProtocolException()
        if (source[position] == '0') position++ else {
            if (source[position] !in '1'..'9') throw BridgeProtocolException()
            while (position < source.length && source[position] in '0'..'9') position++
        }
        var fractional = false
        if (position < source.length && source[position] == '.') {
            fractional = true; position++
            val first = position
            while (position < source.length && source[position] in '0'..'9') position++
            if (position == first) throw BridgeProtocolException()
        }
        if (position < source.length && source[position] in "eE") {
            fractional = true; position++
            if (position < source.length && source[position] in "+-") position++
            val first = position
            while (position < source.length && source[position] in '0'..'9') position++
            if (position == first) throw BridgeProtocolException()
        }
        val raw = source.substring(start, position)
        return if (fractional) raw.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw BridgeProtocolException()
            else (raw.toLongOrNull() ?: throw BridgeProtocolException()).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    }
}
