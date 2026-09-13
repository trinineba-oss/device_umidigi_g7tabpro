package dev.g7tabpro.gsipatch.cli

import dev.g7tabpro.gsipatch.Compression
import dev.g7tabpro.gsipatch.DeviceFacts
import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageIo
import dev.g7tabpro.gsipatch.Ingest
import dev.g7tabpro.gsipatch.InitSwap
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
    // Standalone: read a dumped MediaTek expdb partition. No image argument.
    //   gsipatch --boot-log expdb.bin [--digest <root digest>]
    if (argv.isNotEmpty() && argv[0] == "--boot-log") {
        if (argv.size < 2) {
            System.err.println("--boot-log needs a dumped expdb file"); exitProcess(2)
        }
        val dump = File(argv[1])
        if (!dump.isFile) {
            System.err.println("no such file: " + dump); exitProcess(1)
        }
        val digestAt = argv.indexOf("--digest")
        val digest = if (digestAt >= 0 && digestAt + 1 < argv.size) argv[digestAt + 1] else null
        val attempts = dump.inputStream().buffered().use { dev.g7tabpro.gsipatch.BootLog.scan(it) }
        println(dev.g7tabpro.gsipatch.BootLog.report(attempts, digest))
        exitProcess(0)
    }
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
                "  --boot-log <expdb.bin> [--digest <root digest>] reads a dumped MediaTek\n" +
                "  expdb partition and reports why GSI boots crashed, per image. Standalone,\n" +
                "  with no image argument. Dump it with: dd if=/dev/block/by-name/expdb\n" +
                "  --enable-adb turns off adb authorisation in the image, so a failed boot\n" +
                "  can still be diagnosed over adb.\n" +
                "  --device-tee/-api/-keymint describe the target device, enabling the\n" +
                "  device-vs-image assessment (the app reads these from the device itself).\n" +
                "\n" +
                "  --vendor-selinux <file|dir> reads the target device's SELinux policy\n" +
                "  (a *.cil file, or a directory such as an extracted /vendor/etc/selinux).\n" +
                "  Repeatable. Any genfscon rule in the GSI that claims the same path as the\n" +
                "  vendor with a different context is commented out. Without this the policy\n" +
                "  may not compile on the device, and init fatal-reboots in second stage with\n" +
                "  no boot animation -- the AxionOS failure, confirmed and fixed on hardware.\n" +
                "  --vendor-sepolicy-vers <n.n> is the vendor's sepolicy version, normally\n" +
                "  picked up from plat_sepolicy_vers.txt beside --vendor-selinux. The image\n" +
                "  must ship mapping/<n.n>.cil or its policy cannot compile on the device.\n" +
                "  --swap <path-in-image>=<file> replaces any file inside the image,\n" +
                "  preserving its inode and SELinux label. Repeatable. Added for the DSU\n" +
                "  first-stage daemons (/system/bin/snapuserd, /system/bin/gsid), which\n" +
                "  run before init and which no init patch can reach.\n" +
                "  --fix-init neutralises the verified-boot spoof entries in the image's\n" +
                "  OWN init (3 bytes) -- preferred over a donor swap: no donor file, and\n" +
                "  the ROM keeps its other spoofing. Confirmed on hardware.\n" +
                "  --donor-init <file> replaces /system/bin/init with the given binary, and\n" +
                "  --donor-image <gsi.img> takes that binary out of another (raw) GSI.\n" +
                "  Use one when an image hangs at its own splash despite a correct version\n" +
                "  patch -- see docs/INIT_SWAP_FIX.md. The donor must come from a GSI that\n" +
                "  boots on the target device, and cannot be larger than the image's own."
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
    var fixInitSpoof = false
    val fileSwaps = LinkedHashMap<String, java.io.File>()
    val vendorSelinux = ArrayList<java.io.File>()
    var vendorSepolicyVers: String? = null
    var donorInitFile: File? = null
    var donorImageFile: File? = null

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
            "--fix-init" -> fixInitSpoof = true
            "--swap" -> {
                // --swap /system/bin/snapuserd=/path/to/donor
                val spec = argv[++i]
                val eq = spec.indexOf('=')
                if (eq <= 0) {
                    System.err.println("--swap needs <path-in-image>=<local-file>, got: " + spec)
                    exitProcess(2)
                }
                fileSwaps[spec.substring(0, eq)] = File(spec.substring(eq + 1))
            }
            "--vendor-selinux" -> vendorSelinux.add(File(argv[++i]))
            "--vendor-sepolicy-vers" -> vendorSepolicyVers = argv[++i]
            "--donor-init" -> donorInitFile = File(argv[++i])
            "--donor-image" -> donorImageFile = File(argv[++i])
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

    if (fixInitSpoof && (donorInitFile != null || donorImageFile != null)) {
        System.err.println(
            "use --fix-init or a donor, not both: they are two ways to fix the same blocker, " +
                "and a donor overwrites the init --fix-init would have patched"
        )
        exitProcess(2)
    }
    if (donorInitFile != null && donorImageFile != null) {
        System.err.println("use --donor-init or --donor-image, not both")
        exitProcess(2)
    }
    // Resolved up front: a bad donor should fail before the image is touched,
    // not after a multi-gigabyte copy and a hashtree pass.
    val donorInit: ByteArray? = when {
        donorInitFile != null -> {
            if (!donorInitFile.isFile) {
                System.err.println("no such file: " + donorInitFile); exitProcess(1)
            }
            donorInitFile.readBytes()
        }
        donorImageFile != null -> {
            if (!donorImageFile.isFile) {
                System.err.println("no such file: " + donorImageFile); exitProcess(1)
            }
            println("==> extracting " + InitSwap.INIT_PATH + " from " + donorImageFile.name)
            RandomAccessFile(donorImageFile, "r").use { raf ->
                ImageIo(raf.channel).use { io -> InitSwap.extractFrom(io) }
            }
        }
        else -> null
    }
    if (donorInit != null) {
        InitSwap.validate(donorInit)
        println("    donor init: " + donorInit.size + " bytes")
    }

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
    // Refuse EROFS before decompressing gigabytes into a file that can never be patched.
    val erofs = unwrapped.inputStream().use { probe ->
        dev.g7tabpro.gsipatch.ImageFormat.looksLikeErofs(Compression.open(probe).stream.readNBytes(1028))
    }
    if (erofs) {
        System.err.println("error: " + dev.g7tabpro.gsipatch.ImageFormat.EROFS_MESSAGE)
        exitProcess(1)
    }
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

    // Read once: preflight and the patch both need the vendor's policy.
    val vendorRules = readVendorGenfscon(vendorSelinux)
    val vendorVers = vendorSepolicyVers ?: readSepolicyVers(vendorSelinux)

    if (preflightOnly) {
        RandomAccessFile(target, "r").use { raf ->
            ImageIo(raf.channel).use { io ->
                var last = -1
                val result = Preflight.check(io, release, patch, progress = { done, total ->
                    val pct = if (total == 0L) 100 else ((done * 100) / total).toInt()
                    if (pct != last && pct % 10 == 0) {
                        print("\r    scanning " + pct + "%"); System.out.flush(); last = pct
                    }
                }, device = device, imageName = target.name,
                    vendorGenfscon = vendorRules, vendorSepolicyVersion = vendorVers)
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
                GsiPatcher.Options(
                    release, patch, dropFec, enableAdb, donorInit, fixInitSpoof,
                    fileSwaps.mapValues { (_, f) ->
                        if (!f.isFile) {
                            System.err.println("no such donor file: " + f); exitProcess(1)
                        }
                        f.readBytes()
                    },
                    vendorRules,
                    vendorVers
                ),
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
                Preflight.check(
                    io, release, patch, device = device, imageName = target.name,
                    vendorGenfscon = vendorRules, vendorSepolicyVersion = vendorVers
                )
                    .toString().prependIndent("    ")
            )
        }
    }
    println("done in " + ((System.currentTimeMillis() - started) / 1000) + "s")
}

/**
 * Every `genfscon` rule in the policy files under [paths].
 *
 * A path may be a single `.cil` file or a directory of them -- an extracted
 * `/vendor/etc/selinux` is the usual case. Unreadable entries are reported and
 * skipped rather than aborting: a partial vendor policy still finds real
 * conflicts, and an empty result is handled as "not checked" downstream.
 */
private fun readVendorGenfscon(paths: List<java.io.File>): List<dev.g7tabpro.gsipatch.Sepolicy.Rule> {
    val out = ArrayList<dev.g7tabpro.gsipatch.Sepolicy.Rule>()
    for (p in paths) {
        val files = when {
            p.isDirectory -> p.listFiles()?.filter { it.isFile && it.name.endsWith(".cil") } ?: emptyList()
            p.isFile -> listOf(p)
            else -> {
                System.err.println("no such vendor policy path: " + p); emptyList()
            }
        }
        for (f in files) {
            out.addAll(
                dev.g7tabpro.gsipatch.Sepolicy.parse(
                    f.readText(Charsets.ISO_8859_1), "vendor/" + f.name
                )
            )
        }
    }
    return out
}

/**
 * The vendor's sepolicy version, if `plat_sepolicy_vers.txt` sits beside one of
 * the policy paths the caller gave.
 *
 * Convenient rather than clever: an extracted `/vendor/etc/selinux` contains
 * that file, so pointing --vendor-selinux at the directory supplies the version
 * too and there is nothing extra to remember.
 */
private fun readSepolicyVers(paths: List<java.io.File>): String? {
    for (p in paths) {
        val dir = if (p.isDirectory) p else p.parentFile ?: continue
        val f = java.io.File(dir, "plat_sepolicy_vers.txt")
        if (f.isFile) return f.readText().trim().ifBlank { null }
    }
    return null
}
