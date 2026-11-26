package it.simulafranz.core

/**
 * JSON minimale del progetto (niente librerie: uguale su Android e sul Mac).
 * Tipi prodotti da parse: Map<String, Any?> (ordine di inserimento), List<Any?>, String, Boolean,
 * Long (numeri interi), Double (numeri con virgola o esponente), null.
 * ATTENZIONE: Boolean e numeri restano distinti, come NSNumber booleano / numerico sull'iPad
 * (l'API rifiuta un intero dove serve un booleano e viceversa).
 */
object Json {
    class ParseException(msg: String) : Exception(msg)

    /** null se il testo non è JSON valido. */
    fun parseOrNull(text: String): Any? = try { parse(text) } catch (e: Exception) { null }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("testo dopo la fine del JSON")
        return v
    }

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> writeDouble(sb, v)
            is Float -> writeDouble(sb, v.toDouble())
            is Number -> sb.append(v.toLong())
            is CharSequence -> writeString(sb, v.toString())
            is Map<*, *> -> {
                sb.append('{'); var first = true
                for ((k, x) in v) { if (!first) sb.append(','); first = false; writeString(sb, k.toString()); sb.append(':'); write(sb, x) }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('['); var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) { sb.append(d.toLong()); return }
        sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun value(): Any? {
            if (i >= s.length) throw ParseException("fine inattesa")
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ParseException("carattere inatteso '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw ParseException("valore non valido"); i += w.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw ParseException("attesa una chiave")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw ParseException("atteso ':'")
                i++; ws(); m[k] = value(); ws()
                if (i >= s.length) throw ParseException("fine inattesa")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw ParseException("atteso ',' o '}'")
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                if (i >= s.length) throw ParseException("fine inattesa")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw ParseException("atteso ',' o ']'")
            }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (true) {
                if (i >= s.length) throw ParseException("stringa non chiusa")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) throw ParseException("escape incompleto")
                when (val e = s[i++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { if (i + 4 > s.length) throw ParseException("escape \\u incompleto"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> throw ParseException("escape non valido \\$e")
                }
            }
        }
        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var frac = false
            if (i < s.length && s[i] == '.') { frac = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { frac = true; i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++; while (i < s.length && s[i] in '0'..'9') i++ }
            val t = s.substring(st, i)
            if (t == "-" || t.isEmpty()) throw ParseException("numero non valido")
            return if (frac) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}
