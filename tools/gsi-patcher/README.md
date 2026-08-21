# GSI KeyMint Patcher

An Android app (and a JVM CLI sharing the same core) that does on-device what
[`tools/patch-gsi-keymint.sh`](../patch-gsi-keymint.sh) does on a Linux box:
rewrite a GSI's reported Android version so a TrustKernel TEE provisioned under
an older release will still configure KeyMint, then rebuild and re-sign the
dm-verity hashtree.

Pick the GSI, pick where to save it, press Patch. No root, no PC, no shell.

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

`avbtool` accepting the hashtree proves the tree implementation matches
avbtool's byte for byte; accepting the vbmeta signature proves the re-signing
does too.

**The Android UI itself has not been run on a device yet** -- only the core
logic is verified. Treat the first run as a test.

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

Options: `--release 13`, `--patch 2025-09-05`, `--keep-fec`.

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
