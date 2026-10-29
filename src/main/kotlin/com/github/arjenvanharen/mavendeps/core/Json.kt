package com.github.arjenvanharen.mavendeps.core

/**
 * Minimal JSON reader and string writer, enough for the OSV API; keeps `core/` free of libraries. Objects become
 * `Map<String, Any?>`, arrays `List<Any?>`, numbers `Double`.
 */
object Json {
    fun parse(text: String): Any? = Reader(text).run { value().also { skipWs(); if (i < s.length) fail("trailing data") } }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private class Reader(val s: String) {
        var i = 0
        var depth = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("Invalid JSON at $i: $msg")

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> nested { obj() }
                '[' -> nested { arr() }
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        private fun <T> nested(read: () -> T): T {
            if (++depth > 200) fail("nested too deeply")
            return read().also { depth-- }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') return map.also { i++ }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val key = str()
                skipWs()
                if (i >= s.length || s[i++] != ':') fail("expected ':'")
                map[key] = value()
                skipWs()
                when (if (i < s.length) s[i++] else ' ') {
                    ',' -> continue
                    '}' -> return map
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') return list.also { i++ }
            while (true) {
                list += value()
                skipWs()
                when (if (i < s.length) s[i++] else ' ') {
                    ',' -> continue
                    ']' -> return list
                    else -> fail("expected ',' or ']'")
                }
            }
        }
    }
}

/** Typed access to parsed JSON; missing or mistyped values read as null/empty. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(key: String): Map<String, Any?>? = (this as? Map<String, Any?>)?.get(key) as? Map<String, Any?>

internal fun Any?.arr(key: String): List<Any?> = ((this as? Map<*, *>)?.get(key) as? List<Any?>).orEmpty()

internal fun Any?.str(key: String): String? = (this as? Map<*, *>)?.get(key) as? String
