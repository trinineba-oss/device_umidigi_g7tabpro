package dev.g7tabpro.gsipatch

/**
 * What could be discovered about the device the image is destined for.
 *
 * Every field is optional: an unrooted app can read some of these and not
 * others, and a wrong guess is worse than an honest gap.
 */
class DeviceFacts(
    /**
     * `ro.keymaster.*.release` -- the version the bootloader told the TEE. This
     * is the authoritative patch target: it is the value KeyMint compares
     * against, rather than anything inferred from the running system.
     */
    val teeRelease: String? = null,
    val teeSecurityPatch: String? = null,
    /** `ro.vendor.api_level` / `ro.vndk.version` */
    val vendorApiLevel: Int? = null,
    /**
     * KeyMint AIDL version from the vendor VINTF manifest, or null when it
     * could not be read -- the manifest is labelled vendor_configs_file, which
     * an app domain is normally denied.
     */
    val keymintAidlVersion: Int? = null,
    /** `Build.VERSION.RELEASE`; a fallback, not a substitute for [teeRelease]. */
    val runningRelease: String? = null
)

/** What the image says about itself. */
class ImageFacts(
    val sdk: Int? = null,
    val release: String? = null,
    val buildId: String? = null,
    /** Best-effort ROM identity: lineage version, modversion, flavour, filename. */
    val identity: String? = null
)

/**
 * Judges an image against a device before any of it is written.
 *
 * Deliberately **not** a heuristic predictor. LineageOS 23.2 and Infinity-X
 * 3.12 ship the identical `ro.build.id` (BP4A.251205.006) and the same sdk 36,
 * yet the first drives this device's KeyMint V1 HAL and the second does not.
 * Any rule derived from platform version, build id or API gap would therefore
 * condemn a known-good image. So the verdicts come from images actually tested
 * on hardware, and everything else is reported as fact rather than prophecy.
 */
object Compatibility {

    /** The value to patch to. Prefers what the TEE was actually told. */
    fun recommendedTargetRelease(device: DeviceFacts): String? =
        device.teeRelease?.takeIf { it.isNotBlank() }
            ?: device.runningRelease?.takeIf { it.isNotBlank() }

    /** LineageOS 23.2 (sdk 36) boots on a vendor at API 31. */
    private const val LARGEST_GAP_KNOWN_TO_BOOT = 5

    private class Tested(
        val match: Regex,
        val sdk: Int?,
        val boots: Boolean,
        val note: String
    )

    // Outcomes observed on the UMIDIGI G7 Tab Pro (vendor API 31, KeyMint V1).
    private val TESTED = listOf(
        // Matched on ROM family plus sdk, because these GSIs carry no version
        // number in build.prop at all -- LineageOS 23.2 identifies itself only
        // as "lineage_arm64_bgN4-userdebug", with the 23.2 living in the
        // filename. Family comes from ro.build.flavor, the release from sdk.
        Tested(Regex("lineage", RegexOption.IGNORE_CASE), 34, true,
            "LineageOS 21 (sdk 34) boots once patched, verified via DSU."),
        Tested(Regex("lineage", RegexOption.IGNORE_CASE), 35, true,
            "LineageOS 22.x (sdk 35) boots once patched, and is in daily use on this device."),
        Tested(Regex("lineage", RegexOption.IGNORE_CASE), 36, true,
            "LineageOS 23.2 (sdk 36) boots once patched, despite a four-generation gap " +
                "over the vendor."),
        Tested(Regex("circle", RegexOption.IGNORE_CASE), 36, true,
            "Project Circle (sdk 36) boots once patched, verified via DSU with /data mounted " +
                "and no KeyMint errors."),
        Tested(Regex("avium", RegexOption.IGNORE_CASE), 36, true,
            "AviumUI (sdk 36) boots once patched, verified via DSU."),
        Tested(Regex("axion", RegexOption.IGNORE_CASE), null, false,
            "Axion reverts INSTANTLY under DSU -- 2.7 and, on current tooling, 2.8. That is a " +
                "different failure from the splash hang: the image is rejected at or before " +
                "load, so init never runs and the donor-init fix cannot help (confirmed -- 2.8 " +
                "still reverts WITH Project CiRCLE's init swapped in). Not a KeyMint problem. " +
                "Ruled out by measurement on the patched 2.8 image: the filesystem is clean " +
                "(e2fsck -fn), the AVB footer and full sha256 hashtree verify, all three " +
                "build.prop files read release=13, and it is the SMALLEST of the images tried " +
                "here, so space is not it either. Also ruled out on hardware: rebuilding the " +
                "vbmeta so the com.android.build.system.os_version property descriptor reads " +
                "13 instead of its upstream 16 -- a single-variable change, root digest " +
                "byte-identical -- still reverts instantly. Diagnose it from the DSU side " +
                "instead: gsid/logcat during INSTALL (logcat does not survive the reboot, so " +
                "a post-revert capture only ever shows the host booting), and pstore for the " +
                "failed boot itself. The outstanding control is whether STOCK, unpatched Axion " +
                "reverts too -- if it does, none of our patching is implicated."),
        Tested(Regex("infinity", RegexOption.IGNORE_CASE), 36, true,
            "Infinity-X 3.12 BOOTS once its /system/bin/init is replaced with one from a GSI " +
                "that boots here (confirmed on hardware). The version patch alone is not enough: " +
                "it was applied correctly -- TEE and system both reading 13 -- and KeyMint still " +
                "returned KEYMINT_NOT_CONFIGURED, because the vendor KeyMint HAL loses a " +
                "servicemanager registration race and keystore2 caches an emulated fallback. " +
                "Use the donor-init option; see docs/INIT_SWAP_FIX.md."),
        Tested(Regex("infinity", RegexOption.IGNORE_CASE), 35, false,
            "Infinity-X 2.9 reverted instantly under DSU. That test predates the signing-key " +
                "fix, so it is worth retrying before believing it."),
        Tested(Regex("lunaris", RegexOption.IGNORE_CASE), null, true,
            "Lunaris-AOSP 3.12 needs BOTH fixes. Version patch alone: does not boot. Version " +
                "patch + donor init: boots. Both confirmed on hardware, and the patching path " +
                "is byte-identical between the two builds tested, so the donor is the variable."),
        Tested(Regex("crdroid", RegexOption.IGNORE_CASE), null, true,
            "crDroid needs BOTH fixes -- confirmed on hardware for crDroid 10 and, separately, " +
                "crDroid 11. Version patch alone: does not boot. Version patch + donor init: " +
                "boots. The builds compared have a byte-identical patching path, so the donor " +
                "is the variable. (This also retires the older instant-revert result, which " +
                "predated the embedded-signing-key fix -- the no-donor test was run well after " +
                "that fix and still failed.)"),
        Tested(Regex("phh|peter", RegexOption.IGNORE_CASE), null, true,
            "phh-based builds boot without patching -- they spoof the version themselves.")
    )

    /** Pull the image's self-description out of its own build.prop. */
    fun readImageFacts(fs: Ext4, fileName: String? = null): ImageFacts {
        val props = HashMap<String, String>()
        for (path in listOf("/system/build.prop", "/build.prop")) {
            val ino = (try { fs.lookup(path) } catch (e: Exception) { null }) ?: continue
            for (line in String(fs.readFile(ino), Charsets.UTF_8).lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0 || line.startsWith("#")) continue
                props.putIfAbsent(line.substring(0, eq), line.substring(eq + 1))
            }
            break
        }
        // Tag each value with the property it came from. ro.lineage.version is
        // "23.2-2026..." with no "lineage" in the value itself, so an untagged
        // join cannot be matched on the ROM name.
        val identity = listOf(
            "ro.lineage.version", "ro.modversion", "ro.build.flavor",
            "ro.product.system.name", "ro.build.display.id", "ro.build.description"
        ).mapNotNull { k -> props[k]?.let { k.removePrefix("ro.") + "=" + it } }
            .joinToString(" ") + " " + (fileName ?: "")
        return ImageFacts(
            sdk = props["ro.build.version.sdk"]?.toIntOrNull(),
            release = props["ro.build.version.release"],
            buildId = props["ro.build.id"],
            identity = identity.trim().ifEmpty { null }
        )
    }

    fun assess(device: DeviceFacts, image: ImageFacts): List<Preflight.Finding> {
        val out = ArrayList<Preflight.Finding>()
        val I = Preflight.Severity.INFO
        val W = Preflight.Severity.WARNING

        // ---- what we know about the device
        if (device.teeRelease != null) {
            out.add(Preflight.Finding(I,
                "this device's TEE was told Android " + device.teeRelease +
                    ", so that is the version to patch to"))
        } else {
            out.add(Preflight.Finding(W,
                "could not read ro.keymaster.*.release, so the patch target is a guess" +
                    (device.runningRelease?.let { " (falling back to the running system's " + it + ")" } ?: "")))
        }
        if (device.vendorApiLevel != null) {
            out.add(Preflight.Finding(I, "vendor API level " + device.vendorApiLevel))
        }
        out.add(
            when (device.keymintAidlVersion) {
                null -> Preflight.Finding(I,
                    "vendor KeyMint version could not be read (the VINTF manifest is not " +
                        "readable without root), so compatibility below rests on tested images")
                1 -> Preflight.Finding(I,
                    "vendor implements KeyMint V1, which some ROMs no longer drive correctly")
                else -> Preflight.Finding(I, "vendor implements KeyMint V" + device.keymintAidlVersion)
            }
        )

        // ---- what we know about the image
        val desc = buildString {
            append("image reports Android ")
            append(image.release ?: "?")
            append(" (sdk ")
            append(image.sdk?.toString() ?: "?")
            append(")")
            image.buildId?.let { append(", build ").append(it) }
        }
        out.add(Preflight.Finding(I, desc))

        // ---- has this one actually been tried here?
        val hay = ((image.identity ?: "") + " " + (image.buildId ?: "")).trim()
        val hit = TESTED.firstOrNull { t ->
            t.match.containsMatchIn(hay) && (t.sdk == null || t.sdk == image.sdk)
        }
        when {
            hit == null -> out.add(Preflight.Finding(I,
                "this image has not been tested on this device, so there is no verdict for it " +
                    "either way"))
            hit.boots -> out.add(Preflight.Finding(I, "tested on this device: " + hit.note))
            else -> out.add(Preflight.Finding(W, "tested on this device and it did NOT boot: " + hit.note))
        }

        // ---- a gap worth mentioning, stated as a fact rather than a prediction
        val gap = if (image.sdk != null && device.vendorApiLevel != null)
            image.sdk - device.vendorApiLevel else null
        if (gap != null) {
            // LineageOS 23.2 boots at a gap of 5, so only a larger gap is
            // genuinely uncharted. Saying otherwise contradicts the tested
            // verdict printed directly above it.
            out.add(Preflight.Finding(
                if (gap > LARGEST_GAP_KNOWN_TO_BOOT) W else I,
                "the image is " + gap + " API level(s) ahead of the vendor" +
                    if (gap > LARGEST_GAP_KNOWN_TO_BOOT)
                        " -- larger than any gap seen to boot on this device (" +
                            LARGEST_GAP_KNOWN_TO_BOOT + ")"
                    else ""))
        }
        return out
    }
}
