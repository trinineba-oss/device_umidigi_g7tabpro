# Using the GSI KeyMint Patcher

How to patch a GSI on the tablet itself and boot it. No PC, no root, no shell.

If you want to know *why* any of this is necessary, read
[docs/KEYMINT_OS_VERSION_FIX.md](../../docs/KEYMINT_OS_VERSION_FIX.md). If you
want to build the app or change it, read [README.md](README.md). This file is
just the how-to.

---

## What it does, in one paragraph

This device's TrustKernel TEE was provisioned under Android 13. Its KeyMint HAL
reports the *system's* Android version to the TEE at boot, and the TEE rejects
any version newer than the one it was provisioned with. Every Android 14+ GSI
therefore fails key generation, `/data` never mounts, and the boot hangs at the
splash screen. The patcher rewrites three properties so the GSI reports Android
13, then rebuilds and re-signs the dm-verity hashtree that the edit invalidated.

`ro.build.version.sdk` is deliberately **not** changed, so the system still
behaves as its real Android version. Only what the TEE is told changes.

---

## Before you start

- **Free space:** you need room for a second copy of the image. A 3.3 GB GSI
  needs ~3.3 GB free *in addition to* the file you already have. If the download
  is compressed, budget for the **decompressed** size (a 1.6 GB `.gz` of one
  real GSI expands to 3.3 GB).
- **Time:** a few minutes for a 3 GB image. Copying and decompressing dominate;
  the hashtree pass is the shorter half. Not yet measured on this tablet.
- **Battery:** keep it plugged in. The screen is held awake during the run.
- **Source:** get GSIs from their maintainer (AndyYan, MisterZtr, etc.).

## Install the app

`GsiKeyMintPatcher.apk` is signed with a debug key, so Android will ask you to
allow installing from your browser or file manager. Allow it for that app, then
install.

There are **no runtime permissions** to grant. The app never browses storage on
its own; it only ever touches the two files you hand it through the system file
picker.

---

## Patching

1. **Choose GSI.** Accepts a raw `.img`, `.img.gz`, `.img.xz`, a `.7z`
   archive, or a full OTA package -- either the `.zip` a ROM project actually
   publishes, or a bare `payload.bin` if you already extracted one. The format
   is detected from the file's contents, so a renamed download still works.
   For a zip/payload.bin, the app pulls the `system` partition out of it
   automatically; there's nothing extra to do first.
2. **Choose where to save.** The picker suggests a name like
   `LineageOS-22.2-...-osver13.img`. Save it somewhere on internal storage or
   the SD card. **Not** Drive/OneDrive or any cloud location -- the patcher needs
   random access to the file, which cloud providers do not offer.
3. **Check the two fields.** The release defaults to whatever Android version
   this device currently reports, which is the value its TEE will accept. On the
   G7 Tab Pro running LineageOS 20 that is `13`. Leave it alone unless you know
   otherwise (see [Other devices](#other-devices)). One caveat: run the app from
   inside a booted GSI and it will detect *that* GSI's version, not the stock
   one -- patch from your normal ROM.
4. **Patch.** The progress bar covers the copy, then the hashtree pass. The log
   shows each property it changed and the old and new root digest, and finishes
   with a pre-flight report (below).

There is also a **Check an image** button, which runs the same pre-flight against
any uncompressed `.img` without modifying it. Useful for judging an image you
patched earlier, or one from someone else, before spending a DSU cycle on it.

Success looks like this:

```
partition    : system
build.prop   : /system/build.prop
  changed    : line 39: ro.build.version.release=15 -> 13
  changed    : line 40: ro.build.version.release_or_codename=15 -> 13
  changed    : line 42: ro.build.version.security_patch=2025-12-01 -> 2025-09-05
  changed    : line 187: ro.build.version.release=15 -> 13
  changed    : line 188: ro.build.version.security_patch=2025-12-01 -> 2025-09-05
root digest  : af2d84b2...
          -> : 60341a07...
re-signed    : SHA256_RSA2048
fec          : dropped (descriptor zeroed)
```

Seeing the properties listed **twice** is correct, not a bug: a GSI's
`build.prop` carries a second block of the same properties further down the
file, and all of them have to match.

If anything goes wrong the app says `FAILED:` with the reason and tells you the
output file must not be flashed. Delete it and start again.

### The pre-flight report

Every patch ends with one, and it is the thing to read before installing:

- **BLOCKER** -- a version property in a prop file still has the wrong value.
  Since `ro.` properties are write-once and whichever file init reads first
  wins, one stale entry is enough to make the whole patch pointless. Do not
  install an image with blockers.
- **warn** -- a version string was found somewhere else in the image. This is
  normally harmless: known-good images that boot carry inert copies inside APKs
  or in free space. It only matters if the image fails to boot reporting the
  wrong version, in which case one of these may live in a prop file the tool
  does not yet know to patch.
- **info** -- the image's API level, whether dm-verity hashes correctly, and a
  note if `ro.adb.secure=1` on a non-debuggable build (such an image gives you
  no adb if it hangs before `/data` mounts, which turns any failure into a black
  box).

---

## Installing the patched image

The output is a raw `.img`. Install it with **DSU Sideloader**, which loads it as
a Dynamic System Update alongside your existing ROM.

DSU is the safe way to try a GSI:

- Your real ROM is untouched. The GSI lives in `/data`.
- A **normal reboot returns you to LineageOS 20.** You have to explicitly choose
  to boot the DSU again.
- Nothing is wiped.

Given this project has already had two `/data` corruption scares from flashing,
try every image under DSU first.

### Did it work?

On the tablet, the GSI booting to its lock screen is the answer. If you have adb:

```sh
adb shell getprop sys.boot_completed          # 1
adb shell "mount | grep ' /data '"            # /data is mounted
adb shell getprop init.svc.zygote             # running
adb logcat -d | grep "generateKey returned"   # -67 is good, -64 is the failure
```

`-67` is `ROLLBACK_RESISTANCE_UNAVAILABLE` and is harmless -- vold retries
without the tag and the key succeeds. `-64` is `KEYMINT_NOT_CONFIGURED`, the
failure this whole tool exists to avoid.

### Known results on this tablet

Vendor is API 31 (Android 12).

| GSI | unpatched | patched |
|---|---|---|
| LineageOS 20 (A13) | boots | not needed |
| LineageOS 21 (A14) | hangs at splash | **boots** -- confirmed with an image patched by this app |
| LineageOS 22.2 (A15) | hangs at splash | **boots** |
| LineageOS 23.x (A16) | instant DSU revert | untested since the fix was found |
| PeterGSI (A17, phh) | boots | not needed -- phh builds spoof the version themselves |

---

## When something goes wrong

The app reports one clear line. The common ones:

| message | what it means |
|---|---|
| `this file is only N bytes, far too small to be a GSI system image` | Wrong file -- you picked a text file, or the download is truncated. |
| `no AVB footer in the last 64 bytes: is this a GSI system image?` | Not a GSI `system.img`. A `boot.img`, `vendor.img` or a full-ROM archive will do this. |
| `this is an Android sparse image, not a raw one` | Run it through `simg2img` first, then patch the result. |
| `this image uses EROFS` | Only ext4 GSIs can be patched in place. Look for an EXT4 build of the same GSI. |
| `this zip has no payload.bin inside it` | Not an OTA package this tool recognises -- some other kind of zip. |
| `this payload has no "system" partition` | The OTA package doesn't carry a full system image (e.g. it's a partial/firmware-only update). |
| `...needs the previous partition to apply against` | This `payload.bin` is a delta/incremental OTA, not a full one -- it only makes sense applied on top of an existing install, which a standalone downloaded GSI isn't. |
| `the destination does not support random access` | You chose a cloud location. Save to internal storage or the SD card. |
| `no version properties needed changing: this image already reports release 13` | Already patched, or a GSI that is genuinely Android 13. Nothing to do. |
| `this image is signed with a N-bit key but the patcher holds a M-bit one` | The GSI uses a larger signing key (RSA4096). Not currently re-signable. |
| `patched build.prop is N bytes longer than the original` | A build whose version string changes length (a codename rather than a number). Rare; not patchable in place. |

### The GSI instantly reverts to your normal ROM

This is **not** the KeyMint failure. The version problem makes the device hang
at the GSI splash, because it gets far enough to try to mount `/data`. An
instant revert means the image was rejected *before* that point, so the version
patch is not what is failing.

Two causes are known. Until v2 the patcher signed with the AOSP test key while
leaving whatever public key the image already carried, so any GSI signed by its
maintainer came out with a signature that could not verify -- silently, because
nothing about the sizes looked wrong. crDroid and Infinity builds are likely to
have hit this. That is fixed; the report now says when the key was replaced.

Separately, not all GSIs boot on this device even when correctly patched. The
vendor is API 31, and images several Android generations newer have been seen to
instant-revert for reasons unrelated to KeyMint. If one image reverts, try a
known-good build (AndyYan's LineageOS 21, or 22.2) before suspecting the patch.

To tell a bad patch from a bad GSI, compare the patched file against a reference
build of the same source image:

```sh
sha256sum yourfile-osver13.img
```

Patching the same input twice is deterministic, so two correct runs produce the
same hash.

### It hangs at the GSI splash

If the patch succeeds but the GSI still hangs at the splash, the version fix is
not your problem -- capture a log and look for `-64`:

```sh
adb logcat -d > hang.txt
```

adb has stayed alive at the GSI splash on this device in past tests, so a live
log is usually obtainable even when it never finishes booting.

---

## Things worth knowing

**Images signed by their maintainer are re-signed with the AOSP test key.**
Most GSIs use the public AOSP test key, but some third-party builds are signed
with the maintainer's own. The patcher cannot know their private key, so it
replaces the embedded public key with the test key's and signs with that,
leaving the image self-consistent. This is what the shell script always did
implicitly. When it happens the report says so:

```
re-signed    : SHA256_RSA2048 (embedded key replaced with the AOSP test key)
```

**FEC is removed.** GSIs ship Reed-Solomon parity that dm-verity can use to
repair bit-rot. The patcher does not regenerate it and instead declares the image
as having none, because stale parity is worse than no parity. dm-verity itself
still works normally. This is the only intentional difference from the
[shell script](../patch-gsi-keymint.sh).

**The patched image is exactly the same size as the original.** That is by
design -- the edit is byte-for-byte length-preserving, which is what avoids
resizing the filesystem. A different output size means something went wrong.

**Patching is not destructive to your source file.** The input is only ever
read.

## Other devices

The defaults target this tablet, but the mechanism is generic: any device whose
TEE was provisioned under an older Android shows the same signature -- the stock
ROM works, and every newer GSI returns `-64` from `generateKey`.

Set **Report release** to the Android version your device's stock ROM reports
(`getprop ro.build.version.release` while running stock). The security patch
field matters much less -- this device happily runs a system reporting
`2025-09-05` against a keymaster reporting `2019-06-06`.

## Doing this on a PC instead

Two options, both in this repo:

- [`tools/patch-gsi-keymint.sh`](../patch-gsi-keymint.sh) -- the original; needs
  Linux, root, `avbtool`, `fec` and e2fsprogs.
- The CLI built from this project (`gradle :cli:installDist`) -- same core as the
  app, no root needed. See [README.md](README.md).
