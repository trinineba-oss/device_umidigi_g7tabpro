package dev.g7tabpro.gsipatch

/**
 * Container checks that must run *before* anything reads the AVB footer.
 *
 * A sparse image has no footer at all, so without this it fails as "no AVB
 * footer in the last 64 bytes", which reads like a corrupt download rather than
 * a file that simply needs converting first.
 */
object ImageFormat {

    private const val SPARSE_MAGIC = 0xED26FF3AL

    fun requireRaw(io: ImageIo) {
        // Too small to classify; Avb reports on those with a better message.
        if (io.size < 4096) return
        val head = io.read(0, 4)
        require(head.le32(0) != SPARSE_MAGIC) {
            "this is an Android sparse image, not a raw one: convert it with simg2img " +
                "first, then patch the result"
        }
    }
}

/**
 * Minimal read/write ext4 support: just enough to resolve a path to an inode,
 * read that file, and write it back at the same length.
 *
 * Why not scan the raw image for the property strings instead? Because they are
 * not unique. In a LineageOS 22.2 GSI the byte sequence "ro.build.version.
 * release=15" occurs four times across the image but only twice inside
 * /system/build.prop; a blind scan would rewrite bytes in unrelated regions.
 * Resolving the inode and staying inside its extents is the only safe way.
 */
class Ext4(private val io: ImageIo, private val base: Long = 0L) {

    val blockSize: Int
    private val inodeSize: Int
    private val inodesPerGroup: Int
    private val descSize: Int
    private val groupDescOffset: Long

    private class Extent(val fileBlock: Long, val phys: Long, val len: Int)

    init {
        val sb = io.read(base + 1024, 1024)
        if (sb.le16(0x38) != 0xEF53) {
            // Name the format when we can. "not an ext4 filesystem" sends people
            // hunting for a corrupt download when the real answer is that the
            // image needs converting, or is a layout this tool cannot patch.
            val head = io.read(base, 8)
            throw IllegalArgumentException(
                when {
                    head.le32(0) == 0xED26FF3AL ->
                        "this is an Android sparse image, not a raw one: convert it with " +
                            "simg2img first, then patch the result"
                    sb.le32(0) == 0xE0F5E1E2L ->
                        "this image uses EROFS, and the patcher only handles ext4 GSIs: " +
                            "look for an EXT4 build of the same GSI"
                    else -> "not an ext4 filesystem (superblock magic is not 0xEF53)"
                }
            )
        }
        blockSize = 1024 shl sb.le32(0x18).toInt()
        inodesPerGroup = sb.le32(0x28).toInt()
        inodeSize = sb.le16(0x58).let { if (it == 0) 128 else it }
        val firstDataBlock = sb.le32(0x14)
        val incompat = sb.le32(0x60)
        val is64bit = (incompat and 0x80L) != 0L
        descSize = if (is64bit) sb.le16(0xFE).let { if (it == 0) 64 else it } else 32
        groupDescOffset = base + (firstDataBlock + 1) * blockSize
    }

    private fun readInode(ino: Long): ByteArray {
        val group = (ino - 1) / inodesPerGroup
        val index = ((ino - 1) % inodesPerGroup).toInt()
        val gd = io.read(groupDescOffset + group * descSize, descSize)
        var table = gd.le32(0x08)
        if (descSize >= 64) table = table or (gd.le32(0x28) shl 32)
        return io.read(base + table * blockSize + index.toLong() * inodeSize, inodeSize)
    }

    private fun fileSize(inode: ByteArray): Long =
        inode.le32(0x04) or (inode.le32(0x6C) shl 32)

    private fun extents(inode: ByteArray): List<Extent> {
        val flags = inode.le32(0x20)
        require((flags and 0x10000000L) == 0L) { "inline-data files are not supported" }
        require((flags and 0x80000L) != 0L) { "block-mapped (non-extent) files are not supported" }
        val out = ArrayList<Extent>()
        walk(inode.copyOfRange(0x28, 0x28 + 60), out)
        return out
    }

    private fun walk(node: ByteArray, out: MutableList<Extent>) {
        require(node.le16(0) == 0xF30A) { "bad extent header magic" }
        val entries = node.le16(2)
        val depth = node.le16(6)
        for (i in 0 until entries) {
            val o = 12 + i * 12
            if (depth == 0) {
                var len = node.le16(o + 4)
                // len > 32768 marks an uninitialised extent; the low bits are
                // the real length and the contents read as zeros.
                if (len > 32768) len -= 32768
                val phys = node.le32(o + 8) or (node.le16(o + 6).toLong() shl 32)
                out.add(Extent(node.le32(o), phys, len))
            } else {
                val leaf = node.le32(o + 4) or (node.le16(o + 8).toLong() shl 32)
                walk(io.read(base + leaf * blockSize, blockSize), out)
            }
        }
    }

    /** Resolve an absolute path to an inode number, or null if absent. */
    fun lookup(path: String): Long? {
        var ino = 2L // root
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            ino = lookupIn(ino, part) ?: return null
        }
        return ino
    }

    private fun lookupIn(dirIno: Long, name: String): Long? {
        val inode = readInode(dirIno)
        val target = name.toByteArray(Charsets.UTF_8)
        for (e in extents(inode)) {
            for (b in 0 until e.len) {
                val blk = io.read(base + (e.phys + b) * blockSize, blockSize)
                var o = 0
                while (o + 8 <= blockSize) {
                    val ino = blk.le32(o)
                    val recLen = blk.le16(o + 4)
                    if (recLen < 8) break
                    val nameLen = blk[o + 6].toInt() and 0xFF
                    // inode == 0 covers deleted entries, the htree index node
                    // header, and the metadata_csum tail entry -- all skipped.
                    if (ino != 0L && nameLen == target.size &&
                        target.indices.all { blk[o + 8 + it] == target[it] }
                    ) return ino
                    o += recLen
                }
            }
        }
        return null
    }

    /** name, inode, dirent file_type (1 = regular, 2 = directory) */
    private fun entries(dirIno: Long): List<Triple<String, Long, Int>> {
        val out = ArrayList<Triple<String, Long, Int>>()
        val inode = readInode(dirIno)
        for (e in extents(inode)) {
            for (b in 0 until e.len) {
                val blk = io.read(base + (e.phys + b) * blockSize, blockSize)
                var o = 0
                while (o + 8 <= blockSize) {
                    val ino = blk.le32(o)
                    val recLen = blk.le16(o + 4)
                    if (recLen < 8) break
                    val nameLen = blk[o + 6].toInt() and 0xFF
                    val type = blk[o + 7].toInt() and 0xFF
                    if (ino != 0L && nameLen > 0 && o + 8 + nameLen <= blockSize) {
                        val name = String(blk, o + 8, nameLen, Charsets.UTF_8)
                        if (name != "." && name != "..") out.add(Triple(name, ino, type))
                    }
                    o += recLen
                }
            }
        }
        return out
    }

    /**
     * Map absolute image offsets back to the files that own them.
     *
     * Answers the question the raw version scan cannot: a stray
     * ro.build.version.release somewhere in the image is only actionable once
     * you know whether it sits in a prop file this tool failed to patch or in
     * something inert like an APK. Walks the tree once for all offsets.
     */
    fun findPathsContaining(offsets: Collection<Long>): Map<Long, String> {
        if (offsets.isEmpty()) return emptyMap()
        val wanted = offsets.associateWith { it / blockSize }
        val found = HashMap<Long, String>()
        val stack = ArrayDeque<Pair<Long, String>>()
        stack.add(Pair(2L, ""))
        var visited = 0
        while (stack.isNotEmpty() && found.size < offsets.size && visited < 200_000) {
            val (ino, path) = stack.removeLast()
            visited++
            val kids = try { entries(ino) } catch (e: Exception) { continue }
            for ((name, childIno, type) in kids) {
                val childPath = path + "/" + name
                if (type == 2) {
                    stack.add(Pair(childIno, childPath))
                    continue
                }
                if (type != 1) continue
                val exts = try { extents(readInode(childIno)) } catch (e: Exception) { continue }
                for ((off, blk) in wanted) {
                    if (found.containsKey(off)) continue
                    for (e in exts) {
                        if (blk >= e.phys && blk < e.phys + e.len) { found[off] = childPath; break }
                    }
                }
            }
        }
        return found
    }

    fun readFile(ino: Long): ByteArray {
        val inode = readInode(ino)
        val size = fileSize(inode)
        require(size <= Int.MAX_VALUE) { "file too large: $size" }
        val data = ByteArray(size.toInt())
        for (e in extents(inode)) {
            val fileOff = e.fileBlock * blockSize
            if (fileOff >= size) continue
            val n = minOf(e.len.toLong() * blockSize, size - fileOff).toInt()
            val chunk = io.read(base + e.phys * blockSize, n)
            System.arraycopy(chunk, 0, data, fileOff.toInt(), n)
        }
        return data
    }

    /**
     * Overwrite a file's contents in place. The length must be identical, which
     * keeps i_size and the block allocation untouched -- so no inode rewrite and
     * no metadata_csum recomputation is needed.
     */
    fun writeFileInPlace(ino: Long, data: ByteArray) {
        val inode = readInode(ino)
        val size = fileSize(inode)
        require(data.size.toLong() == size) {
            "in-place write needs identical length (have ${data.size}, file is $size)"
        }
        for (e in extents(inode)) {
            val fileOff = e.fileBlock * blockSize
            if (fileOff >= size) continue
            val n = minOf(e.len.toLong() * blockSize, size - fileOff).toInt()
            io.write(base + e.phys * blockSize, data, fileOff.toInt(), n)
        }
    }
}
