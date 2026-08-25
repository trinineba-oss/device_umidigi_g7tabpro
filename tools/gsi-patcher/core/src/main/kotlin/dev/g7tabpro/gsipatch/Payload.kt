package dev.g7tabpro.gsipatch

import java.io.ByteArrayInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream

/**
 * Reads Android's OTA `payload.bin` format (`chromeos_update_engine`'s
 * `DeltaArchiveManifest`, see `system/update_engine/update_metadata.proto` in
 * any AOSP tree) far enough to pull one partition's raw image out of a
 * **full** payload -- the kind a fresh GSI/ROM install uses, as opposed to a
 * delta/incremental OTA that patches an existing device.
 *
 * Field numbers below are transcribed directly from that .proto, not guessed:
 * getting one wrong wouldn't fail loudly, it would silently misparse. A
 * synthetic payload built with AOSP's own `update_metadata_pb2` bindings and
 * cross-checked against `update_payload.Payload.Apply()` (the reference
 * Python implementation) is what this was validated against.
 *
 * Deliberately narrow: a full payload's operations are only ever REPLACE,
 * REPLACE_BZ, REPLACE_XZ, ZERO or DISCARD. The delta-only operation types
 * (SOURCE_COPY, *_BSDIFF, PUFFDIFF) need a copy of the *old* partition to
 * apply against, which this tool -- patching a standalone downloaded GSI --
 * never has. Payloads that need them fail with a clear message rather than
 * silently producing a corrupt image.
 */
object Payload {

    // InstallOperation.Type, from update_metadata.proto.
    private const val TYPE_REPLACE = 0
    private const val TYPE_REPLACE_BZ = 1
    private const val TYPE_ZERO = 6
    private const val TYPE_DISCARD = 7
    private const val TYPE_REPLACE_XZ = 8

    private val DELTA_ONLY_TYPES = mapOf(
        2 to "MOVE", 3 to "BSDIFF", 4 to "SOURCE_COPY", 5 to "SOURCE_BSDIFF",
        9 to "PUFFDIFF", 10 to "BROTLI_BSDIFF"
    )

    class Extent(val startBlock: Long, val numBlocks: Long)
    class Operation(val type: Int, val dataOffset: Long, val dataLength: Long, val dstExtents: List<Extent>)
    class PartitionManifest(val name: String, val newSize: Long, val operations: List<Operation>)
    class Header(val blockSize: Int, val dataStart: Long, val partitions: List<PartitionManifest>)

    private fun parseExtent(bytes: ByteArray): Extent {
        var start = 0L
        var num = 0L
        for (f in Protobuf.fields(bytes)) when (f.number) {
            1 -> start = f.varint
            2 -> num = f.varint
        }
        return Extent(start, num)
    }

    private fun parseOperation(bytes: ByteArray): Operation {
        var type = -1
        var dataOffset = 0L
        var dataLength = 0L
        val extents = ArrayList<Extent>()
        for (f in Protobuf.fields(bytes)) when (f.number) {
            1 -> type = f.varint.toInt()
            2 -> dataOffset = f.varint
            3 -> dataLength = f.varint
            6 -> extents.add(parseExtent(f.bytes!!))
        }
        require(type >= 0) { "InstallOperation missing its required type field" }
        return Operation(type, dataOffset, dataLength, extents)
    }

    private fun parsePartitionInfoSize(bytes: ByteArray): Long {
        for (f in Protobuf.fields(bytes)) if (f.number == 1) return f.varint
        return 0L
    }

    private fun parsePartition(bytes: ByteArray): PartitionManifest {
        var name = ""
        var size = 0L
        val ops = ArrayList<Operation>()
        for (f in Protobuf.fields(bytes)) when (f.number) {
            1 -> name = String(f.bytes!!, Charsets.UTF_8)
            7 -> size = parsePartitionInfoSize(f.bytes!!)
            8 -> ops.add(parseOperation(f.bytes!!))
        }
        require(name.isNotEmpty()) { "PartitionUpdate missing its required partition_name field" }
        return PartitionManifest(name, size, ops)
    }

    /** Reads and parses the CrAU header + manifest. Does not touch the data blob. */
    fun readHeader(io: ImageIo): Header {
        val magic = io.read(0, 4)
        require(String(magic, Charsets.US_ASCII) == "CrAU") {
            "not an update_engine payload -- missing the \"CrAU\" magic"
        }
        val majorVersion = io.read(4, 8).be64(0)
        require(majorVersion >= 2) {
            "payload major version $majorVersion predates the format this reads (needs >= 2, " +
                "which is universal since Android 7)"
        }
        val manifestSize = io.read(12, 8).be64(0)
        val sigSize = io.read(20, 4).be32(0)
        val manifestStart = 24L
        val manifestBytes = io.read(manifestStart, manifestSize.toInt())

        var blockSize = 4096
        val partitions = ArrayList<PartitionManifest>()
        for (f in Protobuf.fields(manifestBytes)) when (f.number) {
            3 -> blockSize = f.varint.toInt()
            13 -> partitions.add(parsePartition(f.bytes!!))
        }
        val dataStart = manifestStart + manifestSize + sigSize
        return Header(blockSize, dataStart, partitions)
    }

    /**
     * Extracts [partitionName] (e.g. "system") from the payload into [out],
     * which must already be sized to the partition's final length -- callers
     * pre-size it from the returned partition's `newSize` the same way the
     * rest of this app pre-sizes output files, rather than this function
     * reaching into file-length APIs [ImageIo] doesn't expose.
     */
    fun extractPartition(
        io: ImageIo,
        header: Header,
        partitionName: String,
        out: ImageIo,
        progress: ((Long, Long) -> Unit)? = null
    ) {
        val part = header.partitions.firstOrNull { it.name == partitionName }
            ?: throw IllegalArgumentException(
                "this payload has no \"$partitionName\" partition (it has: " +
                    header.partitions.joinToString { it.name } + ")"
            )
        val total = part.operations.sumOf { it.dataLength }.coerceAtLeast(1)
        var done = 0L
        for (op in part.operations) {
            DELTA_ONLY_TYPES[op.type]?.let { name ->
                throw IllegalArgumentException(
                    "this payload's \"$partitionName\" partition uses a $name operation, which " +
                        "needs the previous partition to apply against -- this is a delta/" +
                        "incremental OTA, not a full image, and can't be patched standalone"
                )
            }
            val decoded: ByteArray = when (op.type) {
                TYPE_REPLACE -> io.read(header.dataStart + op.dataOffset, op.dataLength.toInt())
                TYPE_REPLACE_BZ -> {
                    val raw = io.read(header.dataStart + op.dataOffset, op.dataLength.toInt())
                    BZip2CompressorInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                }
                TYPE_REPLACE_XZ -> {
                    val raw = io.read(header.dataStart + op.dataOffset, op.dataLength.toInt())
                    XZInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                }
                TYPE_ZERO, TYPE_DISCARD -> ByteArray(0) // written as zeros below, from extent length alone
                else -> throw IllegalArgumentException("unknown InstallOperation type ${op.type}")
            }
            var readPos = 0
            for (ext in op.dstExtents) {
                val byteOffset = ext.startBlock * header.blockSize
                val byteLen = (ext.numBlocks * header.blockSize).toInt()
                if (op.type == TYPE_ZERO || op.type == TYPE_DISCARD) {
                    out.write(byteOffset, ByteArray(byteLen))
                } else {
                    require(readPos + byteLen <= decoded.size) {
                        "operation on \"$partitionName\" decoded to ${decoded.size} bytes, too " +
                            "short for its own dst_extents (need $byteLen more at extent offset $readPos)"
                    }
                    out.write(byteOffset, decoded, readPos, byteLen)
                    readPos += byteLen
                }
            }
            done += op.dataLength
            progress?.invoke(done, total)
        }
    }
}
