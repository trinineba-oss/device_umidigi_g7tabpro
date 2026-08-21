package dev.g7tabpro.gsipatch.cli

import dev.g7tabpro.gsipatch.Compression
import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageIo
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * JVM harness for the core patcher, so the ext4 / hashtree / AVB / decompression
 * logic can be validated against real multi-gigabyte GSIs (and cross-checked
 * with avbtool) without going near an Android device.
 *
 * Mirrors the app: decompress-and-copy to the destination, then patch the
 * destination in place. A raw image with no --out is patched in place directly.
 */
fun main(argv: Array<String>) {
    try {
        run(argv)
    } catch (e: Exception) {
        // A bad input format or an unsupported image should read as one clear
        // line, not a stack trace with the message buried in it.
        System.err.println("error: " + (e.message ?: e.toString()))
        exitProcess(1)
    }
}

private fun run(argv: Array<String>) {
    if (argv.isEmpty()) {
        System.err.println(
            "usage: gsipatch <image.img|.img.gz|.img.xz> [--out patched.img]\n" +
                "                [--release 13] [--patch 2025-09-05]\n" +
                "                [--key key.pkcs8.der] [--keep-fec]\n" +
                "\n" +
                "  --out is required for compressed input; without it a raw image is\n" +
                "  patched in place."
        )
        exitProcess(2)
    }
    val input = File(argv[0])
    var output: File? = null
    var release = "13"
    var patch = "2025-09-05"
    var keyFile: File? = null
    var dropFec = true

    var i = 1
    while (i < argv.size) {
        when (argv[i]) {
            "--out" -> output = File(argv[++i])
            "--release" -> release = argv[++i]
            "--patch" -> patch = argv[++i]
            "--key" -> keyFile = File(argv[++i])
            "--keep-fec" -> dropFec = false
            else -> {
                System.err.println("unknown argument: " + argv[i]); exitProcess(2)
            }
        }
        i++
    }
    if (!input.isFile) {
        System.err.println("no such file: " + input); exitProcess(1)
    }

    val started = System.currentTimeMillis()

    // ---- decompress / copy stage
    val target: File
    val source = input.inputStream().use { probe -> Compression.open(probe).kind }
    if (source == Compression.Kind.RAW && output == null) {
        target = input
        println("==> raw image, patching in place")
    } else {
        if (output == null) {
            System.err.println("input is " + source.label + "-compressed; --out is required")
            exitProcess(2)
        }
        target = output
        println("==> " + source.label + " input, writing to " + target)
        val totalIn = input.length()
        var lastPct = -1
        input.inputStream().use { raw ->
            val src = Compression.open(raw)
            FileOutputStream(target).use { out ->
                val buf = ByteArray(1 shl 20)
                var written = 0L
                while (true) {
                    val n = src.stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val pct = if (totalIn > 0)
                        ((src.compressedBytesRead * 100) / totalIn).toInt().coerceAtMost(100)
                    else 0
                    if (pct != lastPct && pct % 5 == 0) {
                        print("\r    extracting " + pct + "%")
                        System.out.flush()
                        lastPct = pct
                    }
                }
                println("\r    extracted " + written + " bytes    ")
            }
        }
    }

    val progress = object : GsiPatcher.Progress {
        private var lastPct = -1
        override fun stage(message: String) {
            println("==> " + message)
            lastPct = -1
        }

        override fun hashing(done: Long, total: Long) {
            val pct = if (total == 0L) 100 else ((done * 100) / total).toInt()
            if (pct != lastPct && pct % 5 == 0) {
                print("\r    hashing " + pct + "%")
                System.out.flush()
                lastPct = pct
            }
        }
    }

    RandomAccessFile(target, "rw").use { raf ->
        ImageIo(raf.channel).use { io ->
            val report = GsiPatcher.patch(
                io,
                GsiPatcher.Options(release, patch, dropFec),
                keyFile?.readBytes(),
                progress
            )
            println()
            println(report)
        }
    }
    println("done in " + ((System.currentTimeMillis() - started) / 1000) + "s")
}
