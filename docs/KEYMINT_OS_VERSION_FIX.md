# Fixing GSI boot hangs on MediaTek devices with a TrustKernel KeyMint TA

**TL;DR** — If any Android 14+ GSI hangs forever on the boot splash while the stock ROM boots
fine, and logcat shows `KEYMINT_NOT_CONFIGURED (-64)` from `generateKey`, edit **three lines**
in the GSI's `/system/build.prop` so it reports the Android version your vendor's TEE was
provisioned under, then rebuild the AVB hashtree footer.

```
ro.build.version.release             14 -> 13
ro.build.version.release_or_codename 14 -> 13
ro.build.version.security_patch      2026-06-01 -> 2025-09-05
```

Leave `ro.build.version.sdk` **unchanged** (34). The framework keeps behaving as Android 14;
only what the vendor KeyMint HAL reports to the TEE changes.

Confirmed on a **UMIDIGI G7 Tab Pro** (MT6789; a rebranded Alldocube iPlay 50 Mini Pro),
vendor `TP1A.220624.014`, booting a LineageOS 21 (Android 14) GSI via DSU.

---

## Symptoms

- Stock ROM / older-Android ROM boots normally, every day, no issues.
- **Every** Android 14+ GSI hangs on the boot splash indefinitely — regardless of which GSI.
  Different maintainers, different builds, signed or unsigned: identical failure.
- Depending on the device you may not even reach the GSI's own boot animation.
- If you can get adb at the splash, `/data` is **not mounted** (only `/metadata` is), and:

```
init.svc.zygote   = (empty)     # class main never started
init.svc.pq-2-2   = (empty)     # class main never started
init.svc.vold     = running     # class core did start
init.svc.keystore2= running
```

- logcat is dominated by a retry storm — on the affected device, 147,067 occurrences in a
  single boot:

```
E hwcomposer: [IPqDevice] Can't get PQ service tried (0) times
E init: Control message: Could not find 'vendor.mediatek.hardware.pq@2.14::IPictureQuality/default'
```

**That PQ spam is a red herring.** It is three layers downstream of the actual fault.

---

## Root cause

```
keystore2 generateKey
  -> vendor KeyMint HAL (TrustKernel) passes OS version + patch level to the TEE TA
  -> TA was provisioned under Android 13; it REJECTS an Android 14 system's values
  -> every generateKey returns -64 KEYMINT_NOT_CONFIGURED
  -> vold cannot create the FBE key
  -> installkey /data fails, /data never mounts
  -> class_start main never runs (it needs /data)
  -> pq-2-2 is class main, so the PQ HAL never starts
  -> vendor hwcomposer blocks forever waiting for PQ
  -> boot hangs at the splash
```

The actual error chain, from a real capture:

```
E keymint : TrustKernelKeyMintImplementation.cpp:672: TEE return -64
E keystore2: Error::Km(r#KEYMINT_NOT_CONFIGURED)
E vold    : keystore2 Keystore generateKey returned service specific error: -64
E vold    : read_key failed in mountFstab
I init    : Command 'installkey /data' action=post-fs-data ... failed:
            Failed to create /data/unencrypted: Read-only file system
```

`KEYMINT_NOT_CONFIGURED` means exactly what it says: the KeyMint device was never configured
with its boot parameters.

The TEE is told an OS version at boot, derived from the **boot partition's AVB footer** and
surfaced as `ro.keymaster.xxx.release`. Keystore then asks it to configure using the **system's**
`ro.build.version.release`. **The two must match.** A GSI reporting `14` against a boot image
that says `13` is rejected, and every subsequent key operation fails.

Measured on the affected device *after* the fix, while booting successfully:

```
ro.keymaster.xxx.release        = 13           # from boot.img AVB footer - NOT modified
ro.keymaster.xxx.security_patch = 2019-06-06   # NOT modified
ro.build.version.release        = 13           # patched (was 14)
ro.build.version.security_patch = 2025-09-05   # patched (was 2026-06-01)
```

Two things follow from this, and they matter:

- **`ro.build.version.release` is the critical property.** Note that `security_patch` does *not*
  match (`2019-06-06` vs `2025-09-05`) and the device boots anyway — so the patch-level edit is
  probably unnecessary. It is included below because that is the combination verified to work;
  if you want the minimal change, try `release` + `release_or_codename` alone first.
- **You can fix this from either side.** Raising the boot image's AVB `os_version` prop to 14
  would also align them, but boot.img is AVB-protected and far harder to modify safely. Lowering
  the system's reported version is one line in a text file.

### Why the stock ROM is unaffected

Two independent reasons:

1. It reports Android 13, which the TA accepts.
2. Its `/data` keys already exist, so it only ever *unwraps* keys — it never asks the TEE to
   generate one. DSU, by contrast, always creates a **fresh** `userdata_gsi`, which forces key
   generation on every boot.

### This is not a broken TEE

Easy to prove, and worth doing before assuming hardware damage. On the working ROM, create a
secondary user (Settings → System → Multiple users → Add user) — that forces vold to generate
fresh keys — while watching logcat:

```
D vold  : Generating "key storage" key
E keymint : TrustKernelKeyMintImplementation.cpp:672: TEE return -67
W vold  : Failed to generate rollback-resistant key. This is expected ... Falling back.
I keymint-service.trustkernel: generate key                 <-- retry
D vold  : Created key: /data/misc/vold/user_keys/ce/10/cx0000000000   <-- SUCCESS
```

`-67` is `ROLLBACK_RESISTANCE_UNAVAILABLE` — benign and expected; vold retries without that tag
and the key is created. **Key generation works.** The `-64` is a version rejection, nothing more.

On the device this was found on, the TEE had previously been assumed "permanently wiped" by an
SP Flash Tool *Format All*. That assumption was wrong and cost weeks. Attestation
(Play Integrity) may well be broken by such a wipe, but basic key generation is a separate thing —
test it before concluding anything.

---

## The fix

### 1. Find the values your TEE accepts

On the ROM that boots correctly:

```bash
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.security_patch
```

Use exactly those values. On the reference device: `13` and `2025-09-05`.

### 2. Patch the GSI's build.prop

`/system/build.prop`:

```
ro.build.version.release=13
ro.build.version.release_or_codename=13
ro.build.version.security_patch=2025-09-05
```

**Do not change `ro.build.version.sdk`.** It stays at the GSI's real API level (34). Only the
KeyMint HAL consumes the version/patch strings; the framework uses the SDK level.

### 3. Rebuild the AVB hashtree footer — mandatory

Editing the image invalidates its dm-verity hashtree. Capture the original parameters *before*
you touch anything:

```bash
avbtool info_image --image system.img
```

Then:

```bash
# 1. remove the old footer cleanly (do NOT just truncate/extend the file)
avbtool erase_footer --image system.img

# 2. GSI images ship with shared_blocks set and almost no free space,
#    so they cannot be mounted read-write until this is done
truncate -s +2G system.img
e2fsck -fy system.img
resize2fs system.img
e2fsck -E unshare_blocks -fy system.img
e2fsck -fy system.img

# 3. edit build.prop
mount -o loop,rw system.img /mnt/x
#   ...edit /mnt/x/system/build.prop...
umount /mnt/x

# 4. shrink back
e2fsck -fy system.img
resize2fs -M system.img
e2fsck -fy system.img

# 5. rebuild the footer with the ORIGINAL parameters from step 0
avbtool add_hashtree_footer \
  --image system.img \
  --partition_name system \
  --partition_size <original Image size> \
  --algorithm SHA256_RSA2048 \
  --key external/avb/test/data/testkey_rsa2048.pem \
  --salt <original salt> \
  --hash_algorithm sha256 \
  --fec_num_roots 2 \
  --rollback_index <original rollback index> \
  --prop com.android.build.system.os_version:14 \
  --prop com.android.build.system.security_patch:<original>

# 6. verify
avbtool info_image --image system.img
```

Then gzip and install via DSU Sideloader as usual.

#### Gotchas that will bite you

- **`avbtool add_hashtree_footer` shells out to a `fec` binary that is not on the default
  PATH.** It lives at `out/host/linux-x86/bin/fec` in an AOSP tree. Without it you get
  `FileNotFoundError: 'fec'`.
- **`avbtool verify_image` requires the file be named after the partition** (`system.img`), or
  it throws a confusing `FileNotFoundError` traceback while looking for the hashtree target.
  Symlink it; don't mistake that for a verification failure.
- **Simply extending the file with `truncate` orphans the footer.** The footer lives in the last
  bytes of the image, so growing the file leaves it stranded in the middle and `avbtool` reports
  *"Given image does not look like a vbmeta image."* Always `erase_footer` first and rebuild at
  the end.
- **Verify the finished image, not the build log.** Extract the edited file back out with
  `debugfs -R 'cat /system/build.prop'` and confirm the changes are actually present. A step
  exiting 0 is not evidence the result is correct.

---

## Confirming it worked

```
E vold: keystore2 Keystore generateKey returned service specific error: -67   # accepted
```

`-67` instead of `-64` is the whole ballgame. Then:

```bash
adb shell getprop sys.boot_completed      # 1
adb shell getprop init.svc.zygote         # running
adb shell getprop init.svc.pq-2-2         # running
adb shell "mount | grep ' /data '"        # mounted
adb shell "logcat -d | grep -c \"Can't get PQ service\""   # 0  (was 147067)
```

---

## Things that are NOT the cause

Each of these was investigated at length and disproven. Every one is downstream of the single
version check:

- **Build signing** — signed release pipeline vs unsigned "personal" builds: no difference.
- **VNDK / `KEEP_VNDK`** — the missing `com.android.vndk.current.apex` is a symptom of a newer
  build base, unrelated to the hang.
- **Tree base** (QPR3 vs pre-QPR2) — pre-QPR2 GSIs fail identically.
- **Magisk in the GSI, ABI/symbol mismatches, SELinux, init.rc drift.**
- **The MediaTek PQ HAL.** The most visible symptom by volume, and three layers downstream.
- **Replacing the vendor with a generic MediaTek vendor image (`mod-vendor.img`).** It "works"
  only because its KeyMint is pure software (`libpuresoftkeymasterdevice.so`) and ignores TEE
  version binding — but swapping the KeyMint backend means existing TEE-sealed `/data` keys can
  no longer be unwrapped, so it **requires wiping userdata**. The version-prop fix needs no wipe.
- **Disabling AVB.** Unnecessary. And note `fastboot --disable-verification` changes the
  verified-boot state that FBE keys are bound to, which makes an existing `/data` unreadable
  ("data is corrupt" in recovery). `--disable-verity` alone is safe.

---

## Scope

This should apply to **any** device whose vendor ships a TrustKernel KeyMint TA provisioned under
an older Android release, when running a GSI newer than that vendor. The signature is:

- stock/older ROM boots fine
- newer GSI hangs at the splash
- `generateKey` returns `-64 KEYMINT_NOT_CONFIGURED`
- `/data` never mounts, `class main` never starts

Adjust the two version values to match whatever your working ROM reports.

If your device uses a different TEE vendor, the same *shape* of bug is plausible — a TA
validating OS version/patch level — but the error code and HAL name will differ. Check what your
vendor's KeyMint service logs when `generateKey` fails.
