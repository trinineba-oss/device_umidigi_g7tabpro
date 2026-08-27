package dev.g7tabpro.gsipatch

/**
 * Just enough ext4 block allocation to replace a file whose new contents will
 * not fit in the blocks it already has.
 *
 * ## Why this exists
 *
 * Everything else in this tool is length-preserving, which is what lets it run
 * without root or a loop mount. Swapping `/system/bin/init` broke that: the
 * destination is a **sparse** file, and the donor carries real data where its
 * holes are, so there is nowhere to put those bytes. See [InitSwap] and
 * docs/INIT_SWAP_FIX.md.
 *
 * ## Relocate, don't fill
 *
 * The file is moved to a freshly allocated run of blocks rather than having its
 * holes filled in place. That is deliberate, and it buys two things:
 *
 * 1. **The extent tree stays inline.** An inode holds 4 extents in its body;
 *    filling three separate holes would push init to 7 and force a real extent
 *    tree block. One contiguous run is a single extent, which always fits.
 * 2. **`shared_blocks` becomes a non-issue.** GSIs ship deduplicated: a block
 *    may back several files at once, so writing into the file's *existing*
 *    blocks could silently corrupt an unrelated file. Writing only to blocks
 *    taken from the free pool cannot.
 *
 * The old blocks are deliberately **abandoned, not freed** -- for the same
 * dedup reason. Marking them free would be wrong if another file still points
 * at them, and there is no cheap way to know. Leaking a few hundred KB inside a
 * disposable GSI image is the right trade; correctness beats tidiness here.
 *
 * **Known, accepted consequence:** `e2fsck -fn` reports the abandoned range as
 * a *Block bitmap difference* ("blocks marked in use but not referenced") and
 * therefore says the filesystem "still has errors". That is accounting only --
 * no inode, directory, size or link error, and nothing dm-verity or AVB reacts
 * to, since the blocks are simply never read. Measured on the real case:
 * `-(137157--137817)`, i.e. the 661 blocks init used to occupy.
 * Making it fully clean would mean proving no other inode references that
 * range, which needs a whole-filesystem reference scan; that is the obvious
 * next increment if a spotless `e2fsck` matters more than the ~2.7 MB.
 *
 * ## Scope
 *
 * Refuses rather than guesses when the filesystem is not the shape these images
 * actually are: `metadata_csum` (would need CRC32c over inodes, bitmaps and
 * group descriptors) and 64-bit group descriptors are both rejected. Verified
 * against real GSIs, whose features are exactly
 * `ext_attr dir_index filetype extent sparse_super large_file huge_file
 * uninit_bg dir_nlink extra_isize shared_blocks` -- notably no `metadata_csum`.
 */
internal object Ext4Alloc {

    /** `BG_BLOCK_UNINIT` -- the group's block bitmap is not written out yet. */
    private const val BG_BLOCK_UNINIT = 0x0002

    class Run(val start: Long, val count: Int)

    private fun bitmapOffsetOf(fs: Ext4, group: Long): Long {
        val gd = fs.ioRef.read(fs.groupDescOffsetRef + group * fs.descSizeRef, fs.descSizeRef)
        val lo = gd.le32(0x00)
        return fs.imageBase + lo * fs.blockSize
    }

    private fun groupFlags(fs: Ext4, group: Long): Int {
        val gd = fs.ioRef.read(fs.groupDescOffsetRef + group * fs.descSizeRef, fs.descSizeRef)
        return gd.le16(0x18)
    }

    /**
     * Finds [count] contiguous free blocks and marks them in use, updating the
     * group's and the superblock's free counts. Returns the run, or null when no
     * single run that size exists.
     *
     * Contiguous-only on purpose: it keeps the result to one extent. With
     * thousands of free blocks in these images and a few hundred needed, the
     * search succeeds in practice, and failing cleanly is better than silently
     * producing a fragmented file that needs an extent tree block.
     */
    fun allocateContiguous(fs: Ext4, count: Int): Run? {
        require(!fs.hasMetadataCsum) {
            "this filesystem uses metadata_csum; allocating blocks would need CRC32c over " +
                "inodes, bitmaps and group descriptors, which this tool does not implement"
        }
        require(fs.descSizeRef == 32) {
            "64-bit group descriptors are not supported by the allocator"
        }

        val blocksPerGroup = fs.blocksPerGroup
        if (blocksPerGroup <= 0 || count <= 0) return null
        val bitmapBytes = (blocksPerGroup / 8).toInt()

        for (group in 0 until fs.groupCount) {
            // An uninitialised bitmap would have to be built from scratch, and
            // getting that subtly wrong corrupts the filesystem. Skip.
            if (groupFlags(fs, group) and BG_BLOCK_UNINIT != 0) continue

            val bmOff = bitmapOffsetOf(fs, group)
            val bitmap = fs.ioRef.read(bmOff, bitmapBytes)

            var run = 0
            var i = 0L
            while (i < blocksPerGroup) {
                val used = (bitmap[(i / 8).toInt()].toInt() shr (i % 8).toInt()) and 1
                if (used == 0) {
                    run++
                    if (run == count) {
                        val firstInGroup = i - count + 1
                        markUsed(bitmap, firstInGroup, count)
                        fs.ioRef.write(bmOff, bitmap)
                        adjustGroupFree(fs, group, -count)
                        adjustSuperblockFree(fs, -count)
                        // Block numbers are absolute, and group 0 starts at the
                        // superblock's first data block.
                        val firstDataBlock = fs.ioRef.read(fs.sbOffset, 1024).le32(0x14)
                        return Run(firstDataBlock + group * blocksPerGroup + firstInGroup, count)
                    }
                } else {
                    run = 0
                }
                i++
            }
        }
        return null
    }

    private fun markUsed(bitmap: ByteArray, first: Long, count: Int) {
        for (k in 0 until count) {
            val b = first + k
            val idx = (b / 8).toInt()
            bitmap[idx] = (bitmap[idx].toInt() or (1 shl (b % 8).toInt())).toByte()
        }
    }

    private fun adjustGroupFree(fs: Ext4, group: Long, delta: Int) {
        val off = fs.groupDescOffsetRef + group * fs.descSizeRef
        val gd = fs.ioRef.read(off, fs.descSizeRef)
        val cur = gd.le16(0x0C)
        val next = cur + delta
        require(next >= 0) { "group $group free block count would go negative" }
        gd[0x0C] = (next and 0xFF).toByte()
        gd[0x0D] = ((next shr 8) and 0xFF).toByte()
        if (fs.hasGdtCsum) {
            // uninit_bg keeps a crc16 over the descriptor; a stale one makes
            // e2fsck report the group as corrupt.
            val csum = groupDescCsum(fs, group, gd)
            gd[0x1E] = (csum and 0xFF).toByte()
            gd[0x1F] = ((csum shr 8) and 0xFF).toByte()
        }
        fs.ioRef.write(off, gd)
    }

    /**
     * crc16 of the filesystem UUID + group number + the descriptor with its own
     * checksum field zeroed -- the `GDT_CSUM` (uninit_bg) scheme.
     */
    private fun groupDescCsum(fs: Ext4, group: Long, gd: ByteArray): Int {
        val uuid = fs.ioRef.read(fs.sbOffset + 0x68, 16)
        val body = gd.copyOf()
        body[0x1E] = 0; body[0x1F] = 0
        var crc = crc16(0xFFFF, uuid, uuid.size)
        val g = byteArrayOf(
            (group and 0xFF).toByte(), ((group shr 8) and 0xFF).toByte(),
            ((group shr 16) and 0xFF).toByte(), ((group shr 24) and 0xFF).toByte()
        )
        crc = crc16(crc, g, 4)
        crc = crc16(crc, body, body.size)
        return crc and 0xFFFF
    }

    /** The CRC-16/ARC variant the kernel's `ext4_group_desc_csum` uses. */
    private fun crc16(seed: Int, data: ByteArray, len: Int): Int {
        var crc = seed
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc and 0xFFFF
    }

    private fun adjustSuperblockFree(fs: Ext4, delta: Int) {
        val sb = fs.ioRef.read(fs.sbOffset, 1024)
        val cur = sb.le32(0x0C)
        val next = cur + delta
        require(next >= 0) { "superblock free block count would go negative" }
        val off = fs.sbOffset + 0x0C
        fs.ioRef.write(
            off,
            byteArrayOf(
                (next and 0xFF).toByte(), ((next shr 8) and 0xFF).toByte(),
                ((next shr 16) and 0xFF).toByte(), ((next shr 24) and 0xFF).toByte()
            )
        )
    }

    /**
     * Points [ino] at a single [run] holding [size] bytes, rewriting its extent
     * tree inline (depth 0, one entry) and its block count. The previous blocks
     * are left allocated -- see the note at the top on why freeing them is not
     * safe here.
     */
    fun repointToSingleExtent(fs: Ext4, ino: Long, run: Run, size: Long) {
        val off = fs.inodeOffset(ino)
        val inode = fs.ioRef.read(off, fs.inodeSizeRef)

        // i_block[] is 60 bytes at 0x28: extent header then up to 4 entries.
        val EXT = 0x28
        fun put16(o: Int, v: Int) {
            inode[o] = (v and 0xFF).toByte(); inode[o + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun put32(o: Int, v: Long) {
            inode[o] = (v and 0xFF).toByte()
            inode[o + 1] = ((v shr 8) and 0xFF).toByte()
            inode[o + 2] = ((v shr 16) and 0xFF).toByte()
            inode[o + 3] = ((v shr 24) and 0xFF).toByte()
        }

        for (i in EXT until EXT + 60) inode[i] = 0
        put16(EXT + 0, 0xF30A)  // eh_magic
        put16(EXT + 2, 1)       // eh_entries
        put16(EXT + 4, 4)       // eh_max
        put16(EXT + 6, 0)       // eh_depth -- inline, no tree blocks
        put32(EXT + 8, 0)       // eh_generation

        val e = EXT + 12
        put32(e + 0, 0)                    // ee_block: starts at file block 0
        put16(e + 4, run.count)            // ee_len
        put16(e + 6, ((run.start shr 32) and 0xFFFF).toInt())  // ee_start_hi
        put32(e + 8, run.start and 0xFFFFFFFFL)                // ee_start_lo

        // i_size (lo at 0x04, hi at 0x6C) and i_blocks (0x1C), in 512-byte units.
        put32(0x04, size and 0xFFFFFFFFL)
        put32(0x6C, (size shr 32) and 0xFFFFFFFFL)
        put32(0x1C, run.count.toLong() * (fs.blockSize / 512))

        fs.ioRef.write(off, inode)
    }
}
