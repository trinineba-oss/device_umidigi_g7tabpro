package dev.g7tabpro.gsipatch

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * dm-verity hashtree generation, matching avbtool's `generate_hash_tree`.
 *
 * Level 0 hashes each data block as SHA256(salt || block); each level is padded
 * up to a block boundary and then hashed again, until a single block remains.
 * Levels are stored in the tree buffer smallest-first, which is what
 * `calc_hash_level_offsets` encodes.
 */
object HashTree {

    const val DIGEST_SIZE = 32 // sha256; digest_padding is 0 because 32 is a power of two

    fun levelOffsets(imageSize: Long, blockSize: Int): Pair<LongArray, Long> {
        val sizes = ArrayList<Long>()
        var size = imageSize
        var total = 0L
        while (size > blockSize) {
            val numBlocks = (size + blockSize - 1) / blockSize
            val levelSize = roundUp(numBlocks * DIGEST_SIZE, blockSize.toLong())
            sizes.add(levelSize)
            total += levelSize
            size = levelSize
        }
        val offsets = LongArray(sizes.size)
        for (n in sizes.indices) {
            var off = 0L
            for (m in n + 1 until sizes.size) off += sizes[m]
            offsets[n] = off
        }
        return Pair(offsets, total)
    }

    /**
     * @return root digest and the full tree bytes.
     * @param onProgress called with (bytesHashed, totalBytes) for level 0.
     */
    fun generate(
        io: ImageIo,
        imageSize: Long,
        blockSize: Int,
        salt: ByteArray,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Pair<ByteArray, ByteArray> {
        val (offsets, treeSize) = levelOffsets(imageSize, blockSize)
        require(treeSize <= Int.MAX_VALUE) { "hashtree too large for a single array: $treeSize" }
        val tree = ByteArray(treeSize.toInt())
        val md = MessageDigest.getInstance("SHA-256")
        val blk = ByteArray(blockSize)

        var levelNum = 0
        var srcSize = imageSize
        var levelOutput = ByteArray(0)

        while (srcSize > blockSize) {
            val numBlocks = ((srcSize + blockSize - 1) / blockSize).toInt()
            levelOutput = ByteArray(
                roundUp(numBlocks.toLong() * DIGEST_SIZE, blockSize.toLong()).toInt()
            )
            var remaining = srcSize
            var i = 0
            while (remaining > 0) {
                val n = minOf(blockSize.toLong(), remaining).toInt()
                val consumed = srcSize - remaining
                if (levelNum == 0) {
                    io.readInto(consumed, ByteBuffer.wrap(blk, 0, n))
                    if (onProgress != null && (i and 0x3FF) == 0) onProgress(consumed, imageSize)
                } else {
                    System.arraycopy(tree, (offsets[levelNum - 1] + consumed).toInt(), blk, 0, n)
                }
                // Short final block is zero padded up to blockSize, as avbtool does.
                if (n < blockSize) java.util.Arrays.fill(blk, n, blockSize, 0)
                md.reset()
                md.update(salt)
                md.update(blk, 0, blockSize)
                md.digest(levelOutput, i * DIGEST_SIZE, DIGEST_SIZE)
                i++
                remaining -= n
            }
            System.arraycopy(levelOutput, 0, tree, offsets[levelNum].toInt(), levelOutput.size)
            srcSize = levelOutput.size.toLong()
            levelNum++
        }
        onProgress?.invoke(imageSize, imageSize)

        md.reset()
        md.update(salt)
        md.update(levelOutput)
        return Pair(md.digest(), tree)
    }
}
