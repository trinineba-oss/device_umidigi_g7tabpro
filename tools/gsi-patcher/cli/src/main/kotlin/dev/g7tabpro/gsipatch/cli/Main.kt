package dev.g7tabpro.gsipatch.cli

import dev.g7tabpro.gsipatch.Compression
import dev.g7tabpro.gsipatch.DeviceFacts
import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageIo
import dev.g7tabpro.gsipatch.Ingest
import dev.g7tabpro.gsipatch.Preflight
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
                "  patched in place.\n" +
                "\n" +
                "  --preflight checks an image and exits without modifying it; the exit\n" +
                "  code is non-zero when a blocker is found.\n" +
                "  --enable-adb turns off adb authorisation in the image, so a failed boot\n" +
                "  can still be diagnosed over adb.\n" +
                "  --device-tee/-api/-keymint describe the target device, enabling the\n" +
                "  device-vs-image assessment (the app reads these from the device itself)."
        )
        exitProcess(2)
    }
    val input = File(argv[0])
    var output: File? = null
    var release = "13"
    var patch = "2025-09-05"
    var keyFile: File? = null
    var dropFec = true
    var preflightOnly = false
    var enableAdb = false
    // The JVM harness has no device to probe, so these stand in for one. They
    // also make the device-vs-image assessment testable off-hardware.
    var teeRelease: String? = null
    var vendorApi: Int? = null
    var keymintVer: Int? = null

    var i = 1
    while (i < argv.size) {
        when (argv[i]) {
            "--out" -> output = File(argv[++i])
            "--release" -> release = argv[++i]
            "--patch" -> patch = argv[++i]
            "--key" -> keyFile = File(argv[++i])
            "--keep-fec" -> dropFec = false
            "--preflight" -> preflightOnly = true
            "--enable-adb" -> enableAdb = true
            "--device-tee" -> teeRelease = argv[++i]
            "--device-api" -> vendorApi = argv[++i].toIntOrNull()
            "--device-keymint" -> keymintVer = argv[++i].toIntOrNull()
            else -> {
                System.err.println("unknown argument: " + argv[i]); exitProcess(2)
            }
        }
        i++
    }
    if (!input.isFile) {
        System.err.println("no such file: " + input); exitProcess(1)
    }

    val device = if (teeRelease != null || vendorApi != null || keymintVer != null)
        DeviceFacts(
            teeRelease = teeRelease,
            vendorApiLevel = vendorApi,
            keymintAidlVersion = keymintVer
        ) else null

    val started = System.currentTimeMillis()

    // ---- container stage
    // An OTA zip, a bare payload.bin, or a 7z archive all need reducing to a
    // plain system image before anything below (which only ever understood
    // raw/gz/xz) can run. workDir has to be a real directory the process can
    // write scratch files into; --out's parent is the natural choice when
    // there is one, since a compressed/container input already requires it.
    val workDir = (output?.parentFile ?: input.parentFile ?: File(".")).also { it.mkdirs() }
    val unwrapped = Ingest.unwrap(input, workDir, progress = { done, total ->
        val pct = if (total == 0L) 100 else ((done * 100) / total).toInt()
        print("\r    extracting payload " + pct + "%"); System.out.flush()
    })
    if (unwrapped !== input) println("\r    extracted from container: " + unwrapped.name + "    ")

    // ---- decompress / copy stage
    // Must run before --preflight, not just before patching: preflight reads
    // AVB/ext4 structure near the end of the file, and a compressed source's
    // tail bytes are compressed-stream bytes, not the image's. Checking the
    // input directly here would always fail with a confusing "no AVB footer"
    // error, regardless of whether the underlying image is actually fine.
    val target: File
    val source = unwrapped.inputStream().use { probe -> Compression.open(probe).kind }
    // A container/payload extraction always lands in workDir under a
    // generated name, never the user's own file -- always copy it to an
    // explicit --out rather than silently "patching in place" somewhere the
    // user didn't choose and might lose track of.
    if (source == Compression.Kind.RAW && output == null && unwrapped === input) {
        target = input
        println("==> raw image" + (if (preflightOnly) "" else ", patching in place"))
    } else {
        if (output == null) {
            System.err.println(
                (if (unwrapped === input) "input is " + source.label + "-compressed"
                else "input needed container extraction") + "; --out is required"
            )
            exitProcess(2)
        }
        target = output
        println("==> " + source.label + " input, writing to " + target)
        val totalIn = unwrapped.length()
        var lastPct = -1
        unwrapped.inputStream().use { raw ->
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
        if (unwrapped !== input) unwrapped.delete()
    }

    if (preflightOnly) {
        RandomAccessFile(target, "r").use { raf ->
            ImageIo(raf.channel).use { io ->
                var last = -1
                val result = Preflight.check(io, release, patch, progress = { done, total ->
                    val pct = if (total == 0L) 100 else ((done * 100) / total).toInt()
                    if (pct != last && pct % 10 == 0) {
                        print("\r    scanning " + pct + "%"); System.out.flush(); last = pct
                    }
                }, device = device, imageName = target.name)
                println("\r                    ")
                println(result)
                exitProcess(if (result.willLikelyBoot) 0 else 1)
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
                GsiPatcher.Options(release, patch, dropFec, enableAdb),
                keyFile?.readBytes(),
                progress
            )
            println()
            println(report)
        }
    }

    // The patch verifies its own work, but preflight is the check that answers
    // "will this actually boot" -- it re-reads every prop file the patcher knows
    // about and sweeps the rest of the image for anything left behind.
    println()
    println("==> pre-flight")
    RandomAccessFile(target, "r").use { raf ->
        ImageIo(raf.channel).use { io ->
            println(
                Preflight.check(io, release, patch, device = device, imageName = target.name)
                    .toString().prependIndent("    ")
            )
        }
    }
    println("done in " + ((System.currentTimeMillis() - started) / 1000) + "s")
}
