package dev.g7tabpro.gsipatch.app

import android.os.Build
import dev.g7tabpro.gsipatch.DeviceFacts
import java.io.File

/**
 * Discovers what this device expects, so the patch target is read rather than
 * guessed.
 *
 * Everything here degrades to null rather than throwing: an unrooted app can
 * read properties but is normally denied the vendor VINTF manifest, and an
 * honest gap beats a confident wrong answer.
 */
object DeviceProbe {

    fun read(): DeviceFacts {
        val props = allProps()

        // The property carrying the version the bootloader handed the TEE is
        // vendor-specific in the middle (TrustKernel uses a literal "xxx"), so
        // match the shape instead of hardcoding one device's spelling.
        val teeReleaseKey = props.keys.firstOrNull {
            Regex("^ro\\.keymaster\\..*\\.release$").matches(it)
        }
        val teePatchKey = props.keys.firstOrNull {
            Regex("^ro\\.keymaster\\..*\\.security_patch$").matches(it)
        }

        val vendorApi = listOf("ro.vendor.api_level", "ro.vndk.version", "ro.board.first_api_level")
            .firstNotNullOfOrNull { props[it]?.toIntOrNull() }

        return DeviceFacts(
            teeRelease = teeReleaseKey?.let { props[it] }?.ifBlank { null },
            teeSecurityPatch = teePatchKey?.let { props[it] }?.ifBlank { null },
            vendorApiLevel = vendorApi,
            keymintAidlVersion = readKeymintVersion(),
            runningRelease = Build.VERSION.RELEASE
        )
    }

    /** `getprop` with no arguments, parsed from its `[key]: [value]` form. */
    private fun allProps(): Map<String, String> = try {
        val proc = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        val re = Regex("^\\[([^\\]]+)\\]: \\[(.*)\\]$")
        text.lineSequence().mapNotNull { line ->
            re.find(line.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] }
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * KeyMint's declared AIDL version from the vendor manifest. An AIDL <hal>
     * with no <version> is version 1.
     *
     * Usually unreadable: /vendor/etc is vendor_configs_file and app domains
     * are denied it. Returning null is expected, not a failure.
     */
    private fun readKeymintVersion(): Int? = try {
        val dirs = listOf(File("/vendor/etc/vintf/manifest"), File("/vendor/etc/vintf"))
        val file = dirs.asSequence()
            .mapNotNull { it.listFiles()?.asSequence() }
            .flatten()
            .firstOrNull { it.isFile && it.name.contains("keymint", ignoreCase = true) }
        file?.readText()?.let { xml ->
            val hal = xml.substringAfter("security.keymint", "")
            Regex("<version>\\s*(\\d+)").find(hal)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
    } catch (e: Exception) {
        null
    } catch (e: Error) {
        null
    }
}
