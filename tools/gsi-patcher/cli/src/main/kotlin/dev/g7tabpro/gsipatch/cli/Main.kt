package dev.g7tabpro.gsipatch.cli

import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageIo
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * JVM harness for the core patcher, so the ext4 / hashtree / AVB logic can be
 * validated against real multi-gigabyte GSIs (and cross-checked with avbtool)
 * without going near an Android device.
 *
 * Patches the image in place -- copy it first.
 */
fun main(argv: Array<String>) {
    if (argv.isEmpty()) {
        System.err.println(
            "usage: gsipatch <image.img> [--release 13] [--patch 2025-09-05] " +
                "[--key key.pkcs8.der] [--keep-fec]"
        )
        exitProcess(2)
    }
    val image = File(argv[0])
    var release = "13"
    var patch = "2025-09-05"
    var keyFile: File? = null
    var dropFec = true

    var i = 1
    while (i < argv.size) {
        when (argv[i]) {
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
    if (!image.isFile) {
        System.err.println("no such file: " + image); exitProcess(1)
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

    val started = System.currentTimeMillis()
    RandomAccessFile(image, "rw").use { raf ->
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
