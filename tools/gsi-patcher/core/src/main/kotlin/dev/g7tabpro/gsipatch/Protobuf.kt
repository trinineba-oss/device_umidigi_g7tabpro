package dev.g7tabpro.gsipatch

/**
 * The minimum protobuf wire-format reader needed to parse
 * `chromeos_update_engine.DeltaArchiveManifest` (see
 * `system/update_engine/update_metadata.proto` in any AOSP tree), without
 * pulling in a full protobuf runtime.
 *
 * Deliberately narrow: every field this schema actually uses is either a
 * varint (uint32/uint64/bool/enum -- wire type 0) or length-delimited
 * (string/bytes/embedded message -- wire type 2). Wire types 1 (64-bit) and 5
 * (32-bit) never appear in this .proto, so they are only handled enough to be
 * skipped safely if a future field ever used them.
 */
object Protobuf {

    /** One decoded field: its number, wire type, and payload. */
    class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?)

    /**
     * Reads a base-128 varint starting at [pos] in [buf]. Returns the value
     * and the position just past it.
     */
    fun readVarint(buf: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = pos
        while (true) {
            require(p < buf.size) { "truncated varint at offset $pos" }
            val b = buf[p].toInt() and 0xFF
            p++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 64) { "varint too long at offset $pos" }
        }
        return result to p
    }

    /**
     * Iterates the top-level fields of a serialized message in [buf]. Stops
     * automatically at [end] (defaults to the whole array), which is what
     * makes this safe to reuse for embedded (length-delimited) sub-messages:
     * call it again on the field's own [Field.bytes].
     */
    fun fields(buf: ByteArray, start: Int = 0, end: Int = buf.size): Sequence<Field> = sequence {
        var pos = start
        while (pos < end) {
            val (tag, afterTag) = readVarint(buf, pos)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            pos = afterTag
            when (wireType) {
                0 -> {
                    val (v, afterV) = readVarint(buf, pos)
                    pos = afterV
                    yield(Field(fieldNumber, wireType, v, null))
                }
                2 -> {
                    val (len, afterLen) = readVarint(buf, pos)
                    val l = len.toInt()
                    require(l >= 0 && afterLen + l <= end) {
                        "length-delimited field $fieldNumber overruns message at offset $pos"
                    }
                    yield(Field(fieldNumber, wireType, len, buf.copyOfRange(afterLen, afterLen + l)))
                    pos = afterLen + l
                }
                1 -> { pos += 8; yield(Field(fieldNumber, wireType, 0, null)) }
                5 -> { pos += 4; yield(Field(fieldNumber, wireType, 0, null)) }
                else -> throw IllegalArgumentException("unsupported wire type $wireType at offset $pos")
            }
        }
    }
}
