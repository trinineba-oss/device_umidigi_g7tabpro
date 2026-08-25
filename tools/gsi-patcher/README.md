# GSI KeyMint Patcher

An Android app (and a JVM CLI sharing the same core) that does on-device what
[`tools/patch-gsi-keymint.sh`](../patch-gsi-keymint.sh) does on a Linux box:
rewrite a GSI's reported Android version so a TrustKernel TEE provisioned under
an older release will still configure KeyMint, then rebuild and re-sign the
dm-verity hashtree.

Pick the GSI, pick where to save it, press Patch. No root, no PC, no shell.

Accepts a raw `.img`, `.img.gz`, `.img.xz`, a `.7z` archive, a bare OTA
`payload.bin`, or a full OTA `.zip` with one inside -- so a ROM project's
official release, not just a bare system-image dump, can be handed straight
to this app. See [Input formats](#input-formats) below.

**Using the app? See [USAGE.md](USAGE.md).** This file covers how it works and
how to build it.

Background: [docs/KEYMINT_OS_VERSION_FIX.md](../../docs/KEYMINT_OS_VERSION_FIX.md).

## Why this is much smaller than the shell script

Roughly 60% of the shell script exists to work around one thing: it loop-mounts
the filesystem so it can run `sed`. That forces `truncate +2G`, then
`e2fsck -E unshare_blocks`, `resize2fs`, and a shrink afterwards -- which is
also why it needs root and why it permanently inflates the image.

None of that is necessary, because **every substitution is length-preserving**:

| property | typical before | after | length |
|---|---|---|---|
| `ro.build.version.release` | `15` / `16` | `13` | 2 -> 2 |
| `ro.build.version.release_or_codename` | `15` / `16` | `13` | 2 -> 2 |
| `ro.build.version.security_patch` | `2025-12-01` | `2025-09-05` | 10 -> 10 |

So the bytes are patched where they lie. The file keeps its length, the inode
keeps its size and block allocation, and no `metadata_csum` recomputation is
needed. `ro.build.version.sdk` is deliberately left alone, exactly as in the
shell script, so the framework keeps behaving as its real API level.

Because nothing changes size, `image_size`, `tree_offset`, `tree_size` and the
vbmeta blob length are all unchanged too -- so the AVB work is confined to
recomputing the hashtree, poking the new root digest into the existing
descriptor, and re-signing. Every other descriptor and the embedded public key
are preserved verbatim.

## Two traps this handles

**The properties are not unique.** In a LineageOS 22.2 GSI,
`ro.build.version.release` appears **twice** in `/system/build.prop` (line 39 and
again at line 187 in a second block). All occurrences are rewritten, matching
the shell script's `sed` behaviour.

**A raw byte scan would be wrong.** The same byte string occurs **four** times
across the whole image but only twice inside `build.prop`. That is why
[`Ext4.kt`](core/src/main/kotlin/dev/g7tabpro/gsipatch/Ext4.kt) resolves the path
to an inode and stays inside its extents instead of searching the image.

## Input formats

| format | handling |
|---|---|
| raw `.img` | copied straight through |
| `.img.gz` | `GZIPInputStream` (JDK builtin) |
| `.img.xz` | `org.tukaani:xz`, a pure-Java LZMA2 decoder -- neither the JDK nor Android ships xz support |
| `.7z` | [`Ingest.kt`](core/src/main/kotlin/dev/g7tabpro/gsipatch/Ingest.kt), via `commons-compress`; extracts the first file entry, which then goes through the row above like any other input |
| OTA `.zip` | `Ingest.kt` finds `payload.bin` inside via `java.util.zip.ZipFile`, then extracts it as below |
| `payload.bin` | [`Payload.kt`](core/src/main/kotlin/dev/g7tabpro/gsipatch/Payload.kt) + [`Protobuf.kt`](core/src/main/kotlin/dev/g7tabpro/gsipatch/Protobuf.kt) -- see below |

The format is detected from the **magic bytes, not the file extension**.
Renaming a download is common, and guessing from the name surfaces as a
confusing ext4 or AVB failure much later instead of a clear message up front.

The LZMA2 dictionary is capped at 256 MiB (`xz -9` uses 64 MiB), so a
pathological file fails with a clear memory-limit error rather than an
out-of-memory kill on a phone.

Progress is measured on the **compressed** side, since that is the only total
known up front -- so the bar is accurate for gzip and xz as well as raw images.

### `payload.bin` (OTA packages)

Many ROM projects -- not just pure Treble/GSI maintainers -- only publish a
full OTA `.zip`, with the actual system image buried inside as
`payload.bin` in Android's `update_engine` A/B format
(`chromeos_update_engine.DeltaArchiveManifest`, a protobuf; see
`system/update_engine/update_metadata.proto` in any AOSP tree). Previously
that meant telling the user to go extract it themselves first. Now the app
does it directly: given a `.zip`, it finds `payload.bin` inside; given either
one, it parses the manifest and pulls out the `system` partition.

`Protobuf.kt` is a **hand-rolled, minimal** wire-format reader -- not a
generated-from-.proto client, and deliberately not a dependency on a full
protobuf runtime (Android already has enough jar bloat from elsewhere; this
schema needs maybe a dozen fields). Every field number it reads was
cross-checked against `update_metadata.proto` directly, not transcribed from
memory: a wrong field number wouldn't fail loudly here, it would silently
misparse. Validated against AOSP's own reference implementation
(`update_payload.Payload.Apply()`, the Python library `paycheck.py` uses) by
building a synthetic payload with AOSP's own generated `update_metadata_pb2`
bindings, applying it with both implementations, and diffing the result --
byte-identical across REPLACE, REPLACE_BZ and REPLACE_XZ operations, the three
a **full** (non-incremental) payload ever actually contains.

That "full payload" qualifier matters: `payload.bin` also supports delta/
incremental operations (`SOURCE_COPY`, `*_BSDIFF`, `PUFFDIFF`) that patch an
*existing* installed partition rather than write a new one from scratch. This
tool patches a standalone downloaded GSI, which has no "existing installed
partition" to diff against, so a payload that turns out to need one of those
fails with a clear message rather than silently producing a corrupt image --
it was never going to be able to apply one correctly regardless of how it's
implemented.

## FEC

GSIs ship with FEC (Reed-Solomon) parity. There is no Reed-Solomon
implementation here, and leaving stale parity in place would let dm-verity try
to "correct" good data with it, so the patcher **declares the image as having no
FEC** (`fec_num_roots`, `fec_offset` and `fec_size` are zeroed in the
descriptor). dm-verity works fine without FEC; this only removes the ability to
repair bit-rot. It is the one intentional difference from the shell script's
output.

## Verification status

The core is validated against two real GSIs on a build host, with `avbtool` --
the reference implementation -- as the independent oracle:

| GSI | result |
|---|---|
| LineageOS 22.2 (A15), 3,340,144,640 bytes | size identical, `e2fsck -fn` clean, `avbtool verify_image` passes vbmeta **and** hashtree |
| LineageOS 23.2 (A16), 3,446,353,920 bytes | same |
| the same A15 GSI, gzip-compressed | same |
| the same A15 GSI, xz-compressed | same |

All three input paths (raw, gzip, xz) converge on the **identical** root digest
`60341a07...` for the same source GSI, which is what shows decompression is
byte-exact rather than merely producing something that happens to parse.

`avbtool` accepting the hashtree proves the tree implementation matches
avbtool's byte for byte; accepting the vbmeta signature proves the re-signing
does too.

**Confirmed on hardware (2026-08-21):** a LineageOS 21 (A14) GSI patched by the
**app** booted under DSU on the G7 Tab Pro. That closes the gap this section
used to warn about -- the SAF read/write path is no longer unproven.

Not every GSI has worked. Some images instant-revert under DSU rather than
booting, which is a different failure from the KeyMint splash hang and is not
yet attributed; see [USAGE.md](USAGE.md#when-something-goes-wrong).

## Building

Needs a JDK 17 and the Android SDK (`compileSdk 34`, build-tools 34.0.0).

```sh
./make-key.sh                     # derive the AOSP AVB test key in PKCS#8 form
gradle :app:assembleRelease       # -> app/build/outputs/apk/release/app-release.apk
gradle :cli:installDist           # -> cli/build/install/cli/bin/cli
```

The release APK is signed with the debug key so it installs directly; this is a
local utility, not a Play Store artifact.

### CLI

Useful for validating changes against a real GSI without a device. It patches
**in place**, so copy first:

```sh
cp LineageOS-22.2-GSI.img system.img
cli system.img --key testkey_rsa2048.pkcs8.der
avbtool verify_image --image system.img --key .../testkey_rsa2048.pem
```

For a compressed input, `--out` is required and the CLI decompresses to it
first, exercising exactly the code path the app uses:

```sh
cli LineageOS-22.2-GSI.img.xz --out system.img --key testkey_rsa2048.pkcs8.der
```

Options: `--out`, `--release 13`, `--patch 2025-09-05`, `--keep-fec`.

Note `avbtool verify_image` insists the file be **named after the partition**
(`system.img`), or it fails with a misleading `FileNotFoundError`.

## Signing key

GSIs are signed with the public AOSP AVB test key, which is why re-signing is
possible at all; this is confirmed per-image at runtime, and an image signed with
some other key will fail with a clear signature-size or verification error.

The key is published upstream at `external/avb/test/data/testkey_rsa2048.pem`.
`make-key.sh` converts it to the PKCS#8 DER that Java's `KeyFactory` requires --
the upstream file is PKCS#1. It is generated rather than committed.

## Layout

| module | contents |
|---|---|
| `core` | pure Kotlin/JVM, no Android dependencies: ext4, hashtree, AVB, orchestration |
| `cli` | JVM harness for validating `core` against real images |
| `app` | the Android UI (plain framework views, no AndroidX) |

`core` deliberately has no Android dependency so it can be exercised on a build
host against multi-gigabyte images, which is where the correctness risk lives.
