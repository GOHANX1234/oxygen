package com.oxygens.core.loader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin parser for Android Binary XML (AXML / ResXML) format, as stored in
 * AndroidManifest.xml inside APK zip files.
 *
 * Reference: AOSP frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 *
 * Layout at a glance
 * ──────────────────
 *   File:        ResXMLTree_header (type=0x0003, headerSize=8, fileSize)
 *   Chunk 1:     ResStringPool_header + offsets + string data
 *   Chunk 2..N:  ResXMLTree_node (lineNumber, comment) +
 *                  one of: START_NAMESPACE / END_NAMESPACE /
 *                          START_ELEMENT (+ ResXMLTree_attrExt + attributes) /
 *                          END_ELEMENT
 *
 * Attribute typed value (Res_value, 8 bytes):
 *   uint16 size | uint8 res0 | uint8 dataType | uint32 data
 *   As a little-endian uint32 pair: typeWord = size|(res0<<16)|(dataType<<24), data.
 *   dataType = (typeWord ushr 24) & 0xFF
 */
internal class AxmlParser(private val bytes: ByteArray) {

    // ── Public event model ────────────────────────────────────────────────────

    sealed class Event {
        /** Attributes map: local name → string value (null when value is a resource ref). */
        data class StartTag(val name: String, val attrs: Map<String, String?>) : Event()
        data class EndTag(val name: String) : Event()
    }

    // ── Parser entry point ────────────────────────────────────────────────────

    fun parse(): List<Event> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // File header (8 bytes): type, headerSize, fileSize
        buf.short; buf.short; buf.int

        var strings = emptyArray<String>()
        val events = mutableListOf<Event>()

        while (buf.hasRemaining()) {
            val chunkStart = buf.position()
            val chunkType = buf.uShort()
            val chunkHeaderSize = buf.uShort()
            val chunkSize = buf.int
            if (chunkSize < 8 || chunkStart + chunkSize > bytes.size) break

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    strings = readStringPool(buf, chunkStart)
                }
                CHUNK_START_NAMESPACE, CHUNK_END_NAMESPACE -> { /* namespace mappings — ignore */ }
                CHUNK_START_ELEMENT -> {
                    events += readStartElement(buf, strings)
                }
                CHUNK_END_ELEMENT -> {
                    events += readEndElement(buf, strings)
                }
                // else: skip unknown chunk
            }

            buf.position(chunkStart + chunkSize)
        }

        return events
    }

    // ── Chunk readers ─────────────────────────────────────────────────────────

    /** Parse ResStringPool. Position on entry: right after chunkType+headerSize+chunkSize. */
    private fun readStringPool(buf: ByteBuffer, chunkStart: Int): Array<String> {
        val stringCount = buf.int
        val styleCount  = buf.int
        val flags       = buf.int
        val stringsStart = buf.int  // byte offset from chunkStart to first string
        val stylesStart  = buf.int

        val offsets = IntArray(stringCount) { buf.int }
        val isUtf8  = flags and STRING_POOL_UTF8_FLAG != 0

        return Array(stringCount) { i ->
            val absPos = chunkStart + stringsStart + offsets[i]
            try { readStringAt(absPos, isUtf8) } catch (_: Exception) { "" }
        }
    }

    /**
     * Parse ResXMLTree_attrExt + its attribute array.
     * Position on entry: right after chunk header (type+headerSize+chunkSize = 8 bytes already consumed).
     *
     * Spec-correct attribute seeking:
     *   `attrStart` = byte offset from start of attrExt to first attribute (typically 20).
     *   `attrSize`  = byte stride per attribute entry (typically 20).
     * We honour both so that non-canonical-but-valid encodings don't misparse.
     */
    private fun readStartElement(buf: ByteBuffer, strings: Array<String>): Event.StartTag {
        // ResXMLTree_node: lineNumber + comment  (8 bytes)
        buf.int; buf.int

        // ResXMLTree_attrExt starts here; record its position so we can seek by attrStart
        val extStart  = buf.position()
        val nsIdx     = buf.int
        val nameIdx   = buf.int
        val attrStart = buf.uShort()   // byte offset from extStart to first attribute
        val attrSize  = buf.uShort()   // byte stride of each attribute (ResXMLTree_attribute)
        val attrCount = buf.uShort()
        buf.short; buf.short; buf.short // idAttribute, classAttribute, styleAttribute

        // Seek to declared attribute start rather than assuming they follow immediately
        val firstAttrPos = extStart + attrStart
        val attrs = LinkedHashMap<String, String?>(attrCount * 2)

        for (i in 0 until attrCount) {
            buf.position(firstAttrPos + i * attrSize)

            val attrNs   = buf.int
            val attrName = buf.int
            val rawValue = buf.int      // string-pool index when TYPE_STRING, else -1
            val typeWord = buf.int      // Res_value packed: size(16)|res0(8)|dataType(8) LE
            val data     = buf.int      // Res_value.data

            val name     = strings.str(attrName)
            val dataType = (typeWord ushr 24) and 0xFF
            val value: String? = when (dataType) {
                TYPE_STRING    -> strings.strOrNull(rawValue)
                TYPE_INT_BOOL  -> if (data != 0) "true" else "false"
                // Integer.toUnsignedString so versionCode values > Int.MAX_VALUE
                // don't arrive as negative strings (e.g. versionCode=2200000000)
                TYPE_INT_DEC   -> Integer.toUnsignedString(data)
                TYPE_INT_HEX   -> data.toUInt().toString(16)
                TYPE_REFERENCE -> null  // resource ref — callers fall back to packageName/etc.
                else           -> strings.strOrNull(rawValue) ?: Integer.toUnsignedString(data)
            }
            attrs[name] = value
        }

        return Event.StartTag(strings.str(nameIdx), attrs)
    }

    private fun readEndElement(buf: ByteBuffer, strings: Array<String>): Event.EndTag {
        buf.int; buf.int           // lineNumber, comment
        val nsIdx   = buf.int
        val nameIdx = buf.int
        return Event.EndTag(strings.str(nameIdx))
    }

    // ── String reading ────────────────────────────────────────────────────────

    /**
     * Read one string from the raw [bytes] array at absolute byte offset [pos].
     *
     * UTF-8 encoding (flags bit 8 set):
     *   • 1 or 2 bytes: UTF-16 char count  (high bit set → 2-byte big-endian-ish encoding)
     *   • 1 or 2 bytes: UTF-8 byte count   (same encoding)
     *   • UTF-8 bytes
     *   • 0x00 null terminator
     *
     * UTF-16LE encoding (flags bit 8 clear):
     *   • 1 or 2 × uint16: char count      (high bit set → 2-uint16 encoding)
     *   • char count × uint16 LE chars
     *   • 0x0000 null terminator
     */
    private fun readStringAt(pos: Int, utf8: Boolean): String {
        var p = pos
        return if (utf8) {
            // Skip UTF-16 char count
            var v = bytes[p++].toInt() and 0xFF
            if (v and 0x80 != 0) { v = ((v and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF) }
            // Read UTF-8 byte count
            var byteLen = bytes[p++].toInt() and 0xFF
            if (byteLen and 0x80 != 0) { byteLen = ((byteLen and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF) }
            String(bytes, p, byteLen, Charsets.UTF_8)
        } else {
            // Read UTF-16 char count (uint16, possibly extended to 2 uint16s)
            var len = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            if (len and 0x8000 != 0) {
                val hi = (len and 0x7FFF) shl 16
                val lo = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                len = hi or lo
            }
            String(bytes, p, len * 2, Charsets.UTF_16LE)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteBuffer.uShort(): Int = short.toInt() and 0xFFFF

    private fun Array<String>.str(idx: Int): String =
        if (idx in indices) this[idx] else ""

    private fun Array<String>.strOrNull(idx: Int): String? =
        if (idx in indices) this[idx] else null

    // ── Constants ─────────────────────────────────────────────────────────────

    private companion object {
        const val CHUNK_STRING_POOL     = 0x0001
        const val CHUNK_START_NAMESPACE = 0x0100
        const val CHUNK_END_NAMESPACE   = 0x0101
        const val CHUNK_START_ELEMENT   = 0x0102
        const val CHUNK_END_ELEMENT     = 0x0103

        const val STRING_POOL_UTF8_FLAG = 0x0000_0100

        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING    = 0x03
        const val TYPE_INT_DEC   = 0x10
        const val TYPE_INT_HEX   = 0x11
        const val TYPE_INT_BOOL  = 0x12
    }
}
