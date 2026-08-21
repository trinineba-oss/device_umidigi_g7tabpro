package dev.g7tabpro.gsipatch

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Encodes an RSA public key the way AVB stores it (AvbRSAPublicKeyHeader):
 *
 *     u32 key_num_bits, u32 n0inv, modulus[bits/8], rr[bits/8]   -- big-endian
 *
 * AVB only supports e=65537, so the modulus alone determines the blob. This is
 * needed to tell whether an image's embedded key is the one we can sign with.
 */
internal object AvbKey {

    fun encodePublicKey(modulus: BigInteger): ByteArray {
        val numBits = modulus.bitLength()
        val len = numBits / 8
        val b = BigInteger.ONE.shiftLeft(32)
        val n0inv = b.subtract(modulus.mod(b).modInverse(b))
        val r = BigInteger.ONE.shiftLeft(numBits)
        val rr = r.multiply(r).mod(modulus)
        val out = ByteArray(8 + len * 2)
        out.putBe32(0, numBits.toLong())
        out.putBe32(4, n0inv.toLong())
        writeFixed(modulus, out, 8, len)
        writeFixed(rr, out, 8 + len, len)
        return out
    }

    /** Big-endian, left-zero-padded to exactly [len] bytes. */
    private fun writeFixed(v: BigInteger, dst: ByteArray, off: Int, len: Int) {
        val raw = v.toByteArray()
        val src = if (raw.size > len) raw.copyOfRange(raw.size - len, raw.size) else raw
        java.util.Arrays.fill(dst, off, off + len, 0)
        System.arraycopy(src, 0, dst, off + len - src.size, src.size)
    }
}

/**
 * AVB footer / vbmeta handling, limited to what a length-preserving edit needs.
 *
 * Because the patched image is byte-for-byte the same size, image_size,
 * tree_offset, tree_size and the vbmeta blob length are all unchanged. That
 * means nothing has to be relaid out: we recompute the hashtree in place, poke
 * the new root digest into the existing descriptor, and re-sign. Every other
 * descriptor (chain partitions, properties, kernel cmdline) is preserved
 * verbatim, and so is the embedded public key.
 */
class Avb(private val io: ImageIo) {

    companion object {
        private const val FOOTER_SIZE = 64
        private const val FOOTER_MAGIC = "AVBf"
        private const val VBMETA_MAGIC = "AVB0"
        private const val TAG_HASHTREE = 1L
        const val ALGORITHM_NONE = 0L
    }

    val originalImageSize: Long
    val vbmetaOffset: Long
    val vbmetaSize: Long
    private val blob: ByteArray

    private val authBlockStart: Int
    private val auxBlockStart: Int
    private val hashOffset: Int
    private val hashSize: Int
    private val sigOffset: Int
    private val sigSize: Int
    private val pubKeyOffset: Int
    private val pubKeySize: Int
    val algorithmType: Long

    /** absolute offset of the hashtree descriptor inside the vbmeta blob */
    private val htDesc: Int

    val imageSize: Long
    val treeOffset: Long
    val treeSize: Long
    val dataBlockSize: Int
    val hashBlockSize: Int
    val salt: ByteArray
    val partitionName: String
    val rootDigest: ByteArray
    private val rootDigestOffset: Int
    val fecNumRoots: Long
    val fecSize: Long

    /** True when the image carried a different signing key that we replaced. */
    var signingKeyReplaced: Boolean = false
        private set

    init {
        // Without this, a text file or a truncated download fails deep inside
        // the footer read as "Negative position", which says nothing useful.
        require(io.size > 1024L * 1024L) {
            "this file is only " + io.size + " bytes, far too small to be a GSI " +
                "system image"
        }
        val footer = io.read(io.size - FOOTER_SIZE, FOOTER_SIZE)
        require(String(footer, 0, 4, Charsets.US_ASCII) == FOOTER_MAGIC) {
            "no AVB footer in the last 64 bytes: is this a GSI system image?"
        }
        originalImageSize = footer.be64(12)
        vbmetaOffset = footer.be64(20)
        vbmetaSize = footer.be64(28)
        require(vbmetaOffset in 0 until io.size && vbmetaSize in 1..(1L shl 20)) {
            "footer describes an implausible vbmeta block (offset " + vbmetaOffset +
                ", size " + vbmetaSize + "): the image looks corrupt"
        }

        blob = io.read(vbmetaOffset, vbmetaSize.toInt())
        require(String(blob, 0, 4, Charsets.US_ASCII) == VBMETA_MAGIC) { "bad vbmeta magic" }

        val authSize = blob.be64(12)
        val auxSize = blob.be64(20)
        algorithmType = blob.be32(28)
        hashOffset = blob.be64(32).toInt()
        hashSize = blob.be64(40).toInt()
        sigOffset = blob.be64(48).toInt()
        sigSize = blob.be64(56).toInt()
        val descOffset = blob.be64(96).toInt()
        val descSize = blob.be64(104).toInt()
        pubKeyOffset = blob.be64(64).toInt()
        pubKeySize = blob.be64(72).toInt()

        authBlockStart = 256
        auxBlockStart = (256 + authSize).toInt()
        require(auxBlockStart + auxSize <= blob.size) { "vbmeta aux block runs past the blob" }

        var found = -1
        var p = auxBlockStart + descOffset
        val end = p + descSize
        while (p + 16 <= end) {
            val tag = blob.be64(p)
            val following = blob.be64(p + 8)
            if (tag == TAG_HASHTREE) {
                found = p
                break
            }
            p += 16 + following.toInt()
        }
        require(found >= 0) { "no hashtree descriptor in vbmeta" }
        htDesc = found

        imageSize = blob.be64(htDesc + 20)
        treeOffset = blob.be64(htDesc + 28)
        treeSize = blob.be64(htDesc + 36)
        dataBlockSize = blob.be32(htDesc + 44).toInt()
        hashBlockSize = blob.be32(htDesc + 48).toInt()
        fecNumRoots = blob.be32(htDesc + 52)
        fecSize = blob.be64(htDesc + 64)
        val hashAlg = String(blob, htDesc + 72, 32, Charsets.US_ASCII).trimEnd(' ', Char(0))
        require(hashAlg == "sha256") { "unsupported hashtree hash algorithm: $hashAlg" }
        val nameLen = blob.be32(htDesc + 104).toInt()
        val saltLen = blob.be32(htDesc + 108).toInt()
        val digestLen = blob.be32(htDesc + 112).toInt()
        val varStart = htDesc + 180
        partitionName = String(blob, varStart, nameLen, Charsets.UTF_8)
        salt = blob.copyOfRange(varStart + nameLen, varStart + nameLen + saltLen)
        rootDigestOffset = varStart + nameLen + saltLen
        rootDigest = blob.copyOfRange(rootDigestOffset, rootDigestOffset + digestLen)
        require(digestLen == HashTree.DIGEST_SIZE) { "unexpected root digest length: $digestLen" }
    }

    fun algorithmName(t: Long = algorithmType): String = when (t) {
        0L -> "NONE"
        1L -> "SHA256_RSA2048"
        2L -> "SHA256_RSA4096"
        3L -> "SHA256_RSA8192"
        else -> "type" + t
    }

    fun describe(): String = buildString {
        appendLine("  partition     : " + partitionName)
        appendLine("  image size    : " + imageSize)
        appendLine("  tree offset   : " + treeOffset + "  size " + treeSize)
        appendLine("  block size    : " + dataBlockSize + " / " + hashBlockSize)
        appendLine("  salt          : " + salt.hex())
        appendLine("  root digest   : " + rootDigest.hex())
        appendLine("  algorithm     : " + algorithmName())
        appendLine("  fec roots     : " + fecNumRoots + "  fec size " + fecSize)
        append("  vbmeta        : offset " + vbmetaOffset + " size " + vbmetaSize)
    }

    /**
     * Write the recomputed tree, poke in the new root digest, optionally drop
     * FEC, re-sign, and flush the new vbmeta blob to disk.
     *
     * @param pkcs8Key PKCS#8 DER private key, required unless the image is unsigned.
     */
    fun writeBack(
        newTree: ByteArray,
        newRootDigest: ByteArray,
        dropFec: Boolean,
        pkcs8Key: ByteArray?
    ) {
        require(newTree.size.toLong() == treeSize) {
            "recomputed tree is " + newTree.size + " bytes but the descriptor says " + treeSize
        }
        io.write(treeOffset, newTree)

        System.arraycopy(newRootDigest, 0, blob, rootDigestOffset, newRootDigest.size)

        if (dropFec && fecSize != 0L) {
            // There is no Reed-Solomon implementation here, so rather than
            // leave stale parity that dm-verity might try to "correct" with,
            // declare that this image carries no FEC. dm-verity works without it.
            blob.putBe32(htDesc + 52, 0)
            blob.putBe64(htDesc + 56, 0)
            blob.putBe64(htDesc + 64, 0)
        }

        if (algorithmType != ALGORITHM_NONE) {
            requireNotNull(pkcs8Key) {
                "image is signed (" + algorithmName() + ") but no signing key was supplied"
            }
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Key))

            // The signature is only meaningful against the public key embedded
            // in this same vbmeta. Third-party GSIs are often signed with the
            // maintainer's own key, and simply signing with ours would leave an
            // image whose signature cannot verify -- silently, because the
            // sizes still match. Replace the embedded key with ours, which is
            // exactly what `avbtool --key` does when it rebuilds a footer.
            val ourModulus = (key as java.security.interfaces.RSAPrivateKey).modulus
            val ourPub = AvbKey.encodePublicKey(ourModulus)
            val embeddedAt = auxBlockStart + pubKeyOffset
            val embedded = blob.copyOfRange(embeddedAt, embeddedAt + pubKeySize)
            if (!ourPub.contentEquals(embedded)) {
                require(ourPub.size == pubKeySize) {
                    "this image is signed with a " + (pubKeySize - 8) / 2 * 8 + "-bit key but " +
                        "the patcher holds a " + ourModulus.bitLength() + "-bit one, so it " +
                        "cannot be re-signed"
                }
                System.arraycopy(ourPub, 0, blob, embeddedAt, ourPub.size)
                signingKeyReplaced = true
            }

            val header = blob.copyOfRange(0, 256)
            val aux = blob.copyOfRange(auxBlockStart, blob.size)

            val md = MessageDigest.getInstance("SHA-256")
            md.update(header)
            md.update(aux)
            val digest = md.digest()
            require(digest.size == hashSize) { "hash size mismatch" }
            System.arraycopy(digest, 0, blob, authBlockStart + hashOffset, digest.size)

            // SHA256withRSA performs exactly avbtool's operation: sha256 the
            // payload, wrap it in PKCS#1 v1.5 padding with the sha256
            // DigestInfo prefix, then raw-sign.
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(key)
            signer.update(header)
            signer.update(aux)
            val sig = signer.sign()
            require(sig.size == sigSize) {
                "signature is " + sig.size + " bytes but the header reserves " + sigSize +
                    " (wrong key size?)"
            }
            System.arraycopy(sig, 0, blob, authBlockStart + sigOffset, sig.size)

            // Verify what we just wrote, with the public half of the same key.
            // Previously only the root digest was checked, so a signature that
            // failed to round-trip would not surface until the device rejected
            // the image.
            val crt = key as? java.security.interfaces.RSAPrivateCrtKey
            if (crt != null) {
                val pub = KeyFactory.getInstance("RSA").generatePublic(
                    java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
                )
                val v = Signature.getInstance("SHA256withRSA")
                v.initVerify(pub)
                v.update(header)
                v.update(aux)
                check(v.verify(sig)) {
                    "the vbmeta signature failed to verify immediately after signing"
                }
            }
        }

        io.write(vbmetaOffset, blob)
        io.force()
    }
}
