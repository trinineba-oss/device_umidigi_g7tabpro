# UMIDIGI G7 Tab Pro — complete technical handoff

**Generated 2026-09-01.** Written to be handed to a fresh assistant with **no
prior context**. It is deliberately exhaustive: raw evidence is included inline
rather than summarised, so a reader can audit the reasoning, find the errors in
it, and spot caveats the original investigation missed.

If you are that reader: **§16 (Open questions and unexamined assumptions)** and
**§17 (Anti-patterns)** are where you are most likely to add value. Everything
before them is the evidence you need to evaluate them.

---

## 0. How to read this document

### 0.1 Confidence tags

Every substantive claim carries one. They are not decorative — conflating the
first two with the third is the single most expensive mistake this project has
made, twice, at the cost of a flash-and-boot cycle each time.

| tag | meaning | how to challenge it |
|---|---|---|
| **[HW]** | confirmed on the physical device | re-run the test; check it was single-variable |
| **[PC]** | verified offline against the artefact — hash, disassembly, `e2fsck`, `avbtool` | re-run the command; the artefact paths are given |
| **[INF]** | inference or correlation, **not measured** | look for a confounder or a cheaper direct test |
| **[DEAD]** | tested and falsified | check the test was actually valid — twice it was not |

### 0.2 What this project is trying to do

Two goals, in priority order:

1. **Per-image:** make any Android 14/15/16 GSI boot on this device via DSU.
   **Achieved [HW]**, automated in an on-device app.
2. **Device-wide:** make *unmodified* GSIs boot with no per-image patching, via
   a vendor-side fix. **Built and verified offline, never flashed.**

A third, separate thread: a native LineageOS device tree and a working TWRP.

### 0.3 The constraint that shapes everything

**There is no UART / serial console on this device.** A failed boot produces no
output at all unless adb comes up. Every hardware test is therefore blind, must
be single-variable, and costs a full flash-or-DSU cycle. This is why so much of
the work is offline static analysis, and why the discipline around confidence
tags matters more than it normally would.

---

## 1. Executive summary

Three distinct boot blockers have been identified on this device. They are
**independent** — fixing one does not fix another, and they present differently.

| # | blocker | presentation | status |
|---|---|---|---|
| 1 | KeyMint OS-version rejection | GSI splash hang, `-64 KEYMINT_NOT_CONFIGURED` | **Solved [HW]** |
| 2 | `/system/bin/init` verified-boot spoofing | GSI splash hang, same error | **Solved as a fix [HW]**; mechanism now strongly evidenced [PC] |
| 3 | Axion instant DSU revert | reverts before init runs | **Unsolved**, 6 hypotheses eliminated |

**The headline result of this session (2026-09-01):** disassembly of the failing
`init` reveals a **hardcoded property table that spoofs verified-boot state to
`locked` / `green` / `enforcing` / `release-keys`**, executing inside init's
property-loading routine on every boot (**§5.7**). That table is real and
verified.

**And then, the same day, the obvious explanation for it was falsified
(§5.8a).** The vendor KeyMint HAL was checked directly: it reads **three**
properties, all version-related, and has **zero** root-of-trust plumbing. So it
cannot be consuming the spoofed values. The fix still works; *why* it works is
open again. Read §5.7 and §5.8a together — the second undoes the first's
conclusion while leaving its evidence standing.

---

## 2. Device and environment

### 2.1 Hardware

| | |
|---|---|
| Model | UMIDIGI G7 Tab Pro |
| Actually | a rebranded Alldocube iPlay 50 Mini Pro |
| SoC | MediaTek MT6789 (Helio G99) |
| Stock Android | 13, `TP1A.220624.014`, build `20241121` |
| Vendor API level | **31** (Android 12) |
| TEE | **TrustKernel** |
| KeyMint HAL | **V1** (AIDL v1) |
| Vendor fingerprint | `UMIDIGI/G7_Tab_Pro/G7_Tab_Pro:13/TP1A.220624.014/20241121:user/release-keys` |
| Bootloader | **unlocked**; does **not** publish `ro.boot.vbmeta.*` |
| Serial console | **none** |

Two rows do most of the explanatory work later: the TEE is TrustKernel (which
is strict about the OS version it was provisioned under), and the bootloader
**does not publish `ro.boot.vbmeta.*`** (which is why an init that fabricates
those properties is so consequential).

### 2.2 Build environment

| | |
|---|---|
| Work hub | WSL2 on DESKTOP-NEBA, `192.168.0.4`, mirrored networking |
| AOSP/TWRP tree | `/home/neba` **is itself** the tree root |
| Device repo | `~/device/umidigi/g7tabpro` -> `trinineba-oss/device_umidigi_g7tabpro` |
| LineageOS 21 tree | `/opt/lineage-td` — staged, **unsynced** (22 MB, manifests only) |
| GSI working files | `~/gsi-test/` |
| Toolchain | JDK 17, Gradle `~/opt/gradle-8.7`, SDK `~/android-sdk`, `~/local-bin/fec` |
| avbtool | `~/external/avb/avbtool.py` |
| llvm-objdump | `~/prebuilts/clang/host/linux-x86/clang-r416183b/bin/llvm-objdump` |
| GCP VM | `lineage-builder`, us-west1-b — **stopped**; only needed for the 96 GB AOSP tree |
| Remote access | Tailscale installed; adapter visible in WSL as `eth4` (MTU 1280) but **not yet signed in** |

### 2.3 Environment traps — each cost real time

- **`/home/neba` is itself a repo tree root.** `repo init` from a subdirectory
  searches *upward* and **hijacks the existing tree**. It once silently
  re-pointed the TWRP tree to lineage-21.0. Recovery was
  `git checkout twrp-12.1` in `.repo/manifests`. This is why the LineageOS tree
  now lives at `/opt`.
- **Soong does not follow directory symlinks.** Symlinking the device repo into
  `device/` makes `AndroidProducts.mk.list` regenerate empty and `lunch` fail
  with "Can not locate config makefile". The real directory must be in the
  tree, with the symlink pointing outward.
- **A nested orphan tree blocks all Soong builds.** `/home/neba/twrp` had to be
  moved out entirely.
- **Passwordless sudo is narrow:** `apt-get`, `apt`, `mount`, `umount`,
  `losetup` only. Installing arbitrary packages is not available.
- **Loop-mounted GSI files are root-owned** and unreadable as the normal user.
  Use `debugfs -R "cat /path"` against the image instead — no mount, no root.
- **`avbtool verify_image` requires the file to be named after the partition**
  (`system.img`) or it throws a misleading error. Symlink, do not copy — these
  images are 4 GB.
- **`~/gsi-test/*.img` are multi-GB.** Keep the `.gz`/`.xz` and the small
  `*_init` binaries; delete raw images after use.

---

## 3. Investigation timeline

Rough order, so a reader can see what was believed when — several conclusions
here were later overturned.

| phase | what happened |
|---|---|
| 1 | Every A14+ GSI hangs at splash. logcat dominated by 147k PQ-service errors. **Days lost treating the PQ spam as the fault.** |
| 2 | Traced the real chain to `-64 KEYMINT_NOT_CONFIGURED` from `generateKey`. Root cause: TrustKernel TA rejects an OS version newer than it was provisioned with. |
| 3 | Single-file `build.prop` patch works on A14/A15, fails on A16. Cause: `ro.` props are write-once and A16 GSIs duplicate the version in `/product/etc/build.prop`. Fixed by patching **all** prop files. |
| 4 | Shell script (`patch-gsi-keymint.sh`) working. Requires root, loop mount, `unshare_blocks`, resize. |
| 5 | Rewritten as a Kotlin app + CLI doing length-preserving in-place edits — no root, no mount, no size change. |
| 6 | **Some GSIs still hang with the version patch provably applied.** New blocker. |
| 7 | Maintainer's release notes mention swapping `/system/bin/init`. Tried it: **works [HW]**. Reproduced on 4 ROMs / 3 lineages. |
| 8 | Timing theory proposed (extra early-boot work loses a servicemanager race). **Falsified on hardware [DEAD].** |
| 9 | Root-of-trust theory proposed. Two binary patches built to test it. **Both failed — and both because the patch did not do what was claimed**, not because the theory was wrong. |
| 10 | Axion found to fail *differently* — instant DSU revert, not a splash hang. Six hypotheses eliminated; still unsolved. |
| 11 | **2026-09-01: disassembly reveals the spoof table (§5.7).** Root-of-trust theory promoted from correlation to strong mechanism. |

---

## 4. Blocker 1 — KeyMint OS-version rejection (SOLVED)

### 4.1 Symptom

Every Android 14+ GSI hangs on the boot splash. Stock boots fine, daily. With
adb at the splash, `/data` is **not** mounted (only `/metadata` is):

```
init.svc.zygote    = (empty)     # class main never started
init.svc.pq-2-2    = (empty)     # class main never started
init.svc.vold      = running     # class core did start
init.svc.keystore2 = running
```

logcat is dominated by a retry storm — **147,067 occurrences in a single boot**:

```
E hwcomposer: [IPqDevice] Can't get PQ service tried (0) times
E init: Control message: Could not find
        'vendor.mediatek.hardware.pq@2.14::IPictureQuality/default'
```

> **CAVEAT — the loudest error is not the fault.** That PQ spam is three layers
> downstream. It consumed days. The rule that came out of it: **find the first
> error, not the most frequent one.**

### 4.2 Root cause [HW]

```
keystore2 generateKey
  -> vendor KeyMint HAL (TrustKernel) passes OS version + patch level to the TA
  -> TA was provisioned under Android 13; it REJECTS an Android 14+ system
  -> every generateKey returns -64 KEYMINT_NOT_CONFIGURED
  -> vold cannot create the FBE key
  -> installkey /data fails, /data never mounts
  -> class_start main never runs (it needs /data)
  -> pq-2-2 is class main, so the PQ HAL never starts
  -> vendor hwcomposer blocks forever waiting for PQ
  -> boot hangs at the splash
```

Verbatim from a real capture:

```
E keymint : TrustKernelKeyMintImplementation.cpp:672: TEE return -64
E keystore2: Error::Km(r#KEYMINT_NOT_CONFIGURED)
E vold    : keystore2 Keystore generateKey returned service specific error: -64
E vold    : read_key failed in mountFstab
```

### 4.3 The fix

```
ro.build.version.release             16 -> 13
ro.build.version.release_or_codename 16 -> 13
ro.build.version.security_patch      2026-xx-xx -> 2025-09-05
```

Then **rebuild the AVB hashtree footer** — editing the image invalidates
dm-verity.

`ro.build.version.sdk` is **deliberately left unchanged** (36). The framework
keeps behaving as its real API level; only what the HAL reports to the TEE
changes. Changing sdk would be a far more invasive lie and is not needed.

### 4.4 The write-once subtlety that broke single-file patching

`ro.` properties are **write-once** — whichever file init reads first wins.
Android 16 GSIs carry a generic `ro.build.version.release` in
`/system/product/etc/build.prop` **in addition to** `/system/build.prop`.
Patching only `/system` leaves the runtime reporting the unpatched value.
A14/A15 GSIs lack the duplicate, which is why single-file patching appeared
correct for months.

Full path list now patched:

```
/system/build.prop
/build.prop
/system/product/etc/build.prop
/system/system_ext/etc/build.prop
/system/etc/prop.default
/system/product/build.prop
/system/system_ext/build.prop
```

The patcher **hard-asserts** afterwards that no version property survived
unpatched anywhere — a single stale copy makes the whole exercise pointless.

### 4.5 Choosing the patch target

Prefer what the TEE was actually told over what the system reports:

```
ro.keymaster.*.release     <- authoritative; this is what KeyMint compares against
Build.VERSION.RELEASE      <- fallback only
```

The app reads the former at runtime (`app/.../DeviceProbe.kt`).

> **CAVEAT:** `2025-09-05` as the security patch date is the value that works on
> *this* device. It is not universal. On another device, read
> `ro.keymaster.*.security_patch`.

---

## 5. Blocker 2 — `/system/bin/init` (SOLVED; mechanism now evidenced)

### 5.1 The finding [HW]

Some GSIs hang at their own splash **even with the version patch provably
applied and pre-flight reporting zero blockers**. Replacing `/system/bin/init`
with one from a GSI that boots — changing **nothing else** — makes them boot.

Confirmed across **four ROMs from three separate lineages**, each tested **both
ways**:

| ROM | version patch alone | version patch + donor init |
|---|---|---|
| Infinity-X 3.12 | does not boot | **boots** |
| crDroid 10 | does not boot | **boots** |
| crDroid 11 | does not boot | **boots** |
| Lunaris-AOSP 3.12 | does not boot | **boots** |

Three lineages rules out a maintainer-specific quirk. The crDroid 11 case was
done **entirely in the app, on the tablet**, on a fresh non-booting image — so
the on-device path is hardware-verified, not just the PC path.

> **CAVEAT on "both ways":** the two builds compared for each ROM had a
> byte-identical patching path, so the donor really is the only variable. This
> was checked, not assumed.

### 5.2 How it was found

Doze-off's release notes carry this disclaimer verbatim across Infinity-X,
Lunaris, Project CiRCLE and Axion:

> *for users if not boot in DSU user note #edit_gsi and put this init file in
> system/bin. The reason for this is that some of my patches broke the DSU
> function on some old devices, and since I don't have a user to keep testing
> and testing until I figure it out, it's better if you push the working file.*

`/system/bin/init` had **never been compared** in this project's otherwise
exhaustive static diffing — which covered `keystore2`, `libkm_compat*`, `vold`,
SELinux policy, `keystore2.rc` and `hw/init.rc`, all identical or irrelevant.
init was the gap.

> **CAVEAT worth internalising:** the fix was in a maintainer's release notes
> the whole time. Before deep binary analysis, read what the people who ship
> the artefact already say about it.

### 5.3 Scale of the difference [PC]

| | Project CiRCLE | Infinity-X 3.12 |
|---|---|---|
| `/system/bin/init` size | 2,708,416 | 2,724,744 (+16,328) |
| bytes differing | — | 2,055,573 of 2,724,744 |

For contrast, `/system/bin/vold` differs between the same two images by **16
bytes and one string**. This is a different *build* of init, not a patch.

### 5.4 Donor inventory — verified hashes [PC]

```
7ac91703826d68e04bf98a0bbab5deff12a5bda6aa45cc62b1ea41f3d86de990  circle_init    2708416  BOOTS
a633589ff1c2d1474b9b224ec8a3837d0b1fa63350f346ccc86d913d2085f43e  avium_init     2724880  BOOTS
f52af0f7cda88bf79bc4b334875259ae5b4f70bac2b0fb59096b5be044fbe60a  dozeoff_init   2725064  BOOTS
8ea305a22e59e5d44bce1ddc272024190717c2c5430967b0d016909ab040b77e  inf_init       2724744  HANGS
c18a36392089a3aed09d7c861a5b312f9768ee96435a65a9a18399581e49f095  lunaris_init   2724864  HANGS
b6fd7e143311e76bc789620f3e2e8f3aebc454730ad276205bcc4cb7b88dacdf  axion_init     2758672  (different failure)
```

All in `~/gsi-test/`. `dozeoff_init` is byte-identical to the standalone `init`
asset published on the CiRCLE, Infinity-X and Axion releases — an
**independent** known-good sample, not derived from any ROM this investigation
started with.

**End-to-end verification of the fix [PC]:** the image that boots on hardware
(`inf312-initswap-osver13.img.gz`) was opened and its `/system/bin/init`
extracted. It is **byte-identical to `circle_init`** (md5
`3e95e1893f77e621c11445bf3799941d`, 2,708,416 bytes) and contains **zero**
verified-boot spoof strings. The chain is complete: boots on hardware ->
contains Circle's init -> contains none of the suspect code.

### 5.5 Recommended donor: `circle_init`

Doze-off's init is a **debug/rescue build** — it uniquely contains
`/first_stage.sh`, `/data/local.prop`, `androidboot.first_stage_console`,
`/system/bin/lsof`, and `Permissive SELinux boot, forcing
sys.init.perf_lsm_hooks to 1`. That fits its purpose as a file handed to users
whose device will not boot.

> **CAVEAT — do not conclude the permissive/debug behaviour is what rescues the
> boot.** Circle and AviumUI contain **none** of those strings and boot
> perfectly. The debug features are incidental.

Prefer Circle's: leaner, no first-stage shell hook, no `/data/local.prop`
reading, no permissive-boot path, equally effective.

### 5.6 The string correlation [PC]

Recounted 2026-09-01 directly from the binaries:

| init | boots? | `GetVbmeta*` | `ro.secureboot.*` | `ro.is_ever_orange` | `ro.boot.vbmeta.` prefix | `locked` | `green` |
|---|---|---|---|---|---|---|---|
| Project CiRCLE | **yes** | 0 | 0 | 0 | 0 | **0** | **0** |
| AviumUI | **yes** | 0 | 0 | 0 | 0 | **0** | **0** |
| Doze-off fix init | **yes** | 0 | 0 | 0 | 0 | **0** | **0** |
| Infinity-X 3.12 | no | 10 | 2 | 1 | 1 | **1** | **1** |
| Lunaris-AOSP | no | 10 | 2 | 1 | 1 | **1** | **1** |
| Axion 2.8 | *different failure* | 9 | 0 | 0 | 1 | **1** | **1** |

**AviumUI is what makes this informative.** It carries the *full* property
names `ro.boot.vbmeta.device_state`, `.digest` and `.size` — ordinary
kernel-cmdline handling — yet boots. So those constants alone are harmless.
What separates booting from hanging is the **synthesis** machinery:
`GetVbmeta*`, the `ro.boot.vbmeta.` **prefix** (used to build names
dynamically), and the literals `locked` / `green`.

Exact string set present in **both** failing inits and **none** of the three
booting ones (binary noise removed):

```
' set successfully to '
-vbmeta
GetVbmetaDigest: Failed to set property 'ro.boot.vbmeta.digest' to fallback value:
GetVbmetaDigest: Property 'ro.boot.vbmeta.digest' set successfully to dynamic fallback value '
GetVbmetaSize: Attempting to open
GetVbmetaSize: Failed to open
GetVbmetaSize: Failed to set property 'ro.boot.vbmeta.size' to '
GetVbmetaSize: Property 'ro.boot.vbmeta.size' set successfully to '
GetVbmetaSize: Size of
GetVbmetaSize: ioctl(BLKGETSIZE64) failed for
GetVbmetaSize: lseek failed for
GetVbmetaSize: ro.boot.slot_suffix is empty
Property '
green
locked
ro.boot.vbmeta.
ro.is_ever_orange
ro.secureboot.devicelock
ro.secureboot.lockstate
ro.system_dlkm.build.type
```

Note `orange` and `yellow` are absent from **every** init examined. The code
only ever writes `green` and `locked` — it has no path for the honest values.

`oplusboot.verifiedbootstate` is an Oplus (OPPO/OnePlus/Realme) bootloader
property name, so this is near-certainly a fix for Oplus-brand devices baked
into a shared GSI init base that now runs on every device using it.

### 5.7 THE MECHANISM — a hardcoded verified-boot spoof table [PC]

**This is the most important finding in the document, and it is new as of
2026-09-01.** Everything before this section was correlation. This is the code.

Cross-referencing `adrp`/`add` address-materialisation pairs in Infinity's init
(ARM64, llvm-objdump) resolves every suspect string to exactly one code site,
and they cluster in a ~2.4 KB span:

```
locked                        ref at 0xfce08
green                         ref at 0xfce74
/dev/block/by-name/vbmeta     ref at 0xfd324
ro.boot.vbmeta.(prefix)       refs at 0xf8c78, 0xf99b4
ro.boot.vbmeta.device_state   ref at 0xfcc98
ro.boot.vbmeta.digest         ref at 0xfd60c
ro.boot.vbmeta.size           ref at 0xfd474
ro.is_ever_orange             ref at 0xfcedc
ro.secureboot.lockstate       ref at 0xfcee8
ro.secureboot.devicelock      ref at 0xfce14
oplusboot.verifiedbootstate   ref at 0xfce2c
```

Reconstructing the stack table those instructions build — by resolving each
`adrp`/`add` to its string and pairing it with the `str [sp, #N]` slot it is
written to — yields an explicit **name -> value** map:

```
sp+608   ro.boot.flash.locked
sp+624   ro.boot.vbmeta.device_state          sp+632   'locked'
sp+640   ro.boot.vbmeta.hash_alg              sp+648   'sha256'
sp+656   ro.boot.vbmeta.avb_version
sp+672   ro.boot.vbmeta.invalidate_on_error
sp+688   ro.boot.verifiedbootstate            sp+696   'green'
sp+704   ro.boot.veritymode                   sp+712   'enforcing'
sp+720   ro.boot.warranty_bit
sp+736   ro.warranty_bit
sp+752   ro.debuggable
sp+768   ro.force.debuggable
sp+784   ro.adb.secure
sp+800   ro.secure
sp+816   ro.bootimage.build.type              sp+824   'user'
sp+832   ro.build.type                        sp+840   'user'
sp+848   ro.build.keys                        sp+856   'release-keys'
sp+864   ro.build.tags                        sp+872   'release-keys'
sp+880   ro.system.build.tags                 sp+888   'release-keys'
sp+896   ro.product.build.type                sp+904   'user'
sp+912   ro.system_dlkm.build.type            sp+920   'user'
sp+928   ro.odm.build.type                    sp+936   'user'
sp+944   ro.system.build.type                 sp+952   'user'
sp+960   ro.system_ext.build.type             sp+968   'user'
sp+976   ro.vendor.build.type                 sp+984   'user'
sp+992   ro.vendor_dlkm.build.type            sp+1000  'user'
sp+1008  ro.vendor.boot.warranty_bit
sp+1024  ro.vendor.warranty_bit
sp+1040  vendor.boot.vbmeta.device_state      sp+1048  'locked'
sp+1056  vendor.boot.verifiedbootstate        sp+1064  'green'
sp+1072  oplusboot.verifiedbootstate          sp+1080  'green'
sp+1088  sys.oem_unlock_allowed
sp+1104  ro.oem_unlock_supported
sp+1120  ro.crypto.state                      sp+1128  'encrypted'
sp+1136  ro.boot.flash.locked
sp+1152  ro.is_ever_orange
sp+1168  ro.secureboot.devicelock
sp+1184  ro.secureboot.lockstate              sp+1192  'locked'
```

**This is a Play Integrity / SafetyNet property spoof.** It is exactly the kind
of block a ROM maintainer adds so a device with an unlocked bootloader reports
as locked, verified, `user`-built and `release-keys`-signed.

**And it runs inside init's property-loading routine.** All four probed sites
resolve to the same enclosing function entry, `0xfa4ec`:

```
site 0xfcc98 -> enclosing function entry 0xfa4ec
site 0xfce74 -> enclosing function entry 0xfa4ec
site 0xfd324 -> enclosing function entry 0xfa4ec
site 0xfd60c -> enclosing function entry 0xfa4ec
```

`0xfa4ec` was independently identified as init's **property-loading** function
from its other string references — `(Loading properties from`, `' in property
file '`, `while loading .prop files`, `/build.prop`, `/default.prop`,
`.build.version.sdk` — and is reached through a 5-deep call chain from init's
startup: `0xfa4ec <- 0x100414 <- 0xd9fa8 <- 0x98034 <- 0x95dc0 <- 0x75d4c`.
The three innermost links have exactly **one** call site each.

So this executes **early in second stage, on every boot, before services
start** — precisely the window in which the KeyMint HAL and keystore2 come up.

### 5.8 Why this explains the failure

```
failing init's property-load routine sets, unconditionally:
    ro.boot.vbmeta.device_state  = locked
    ro.boot.verifiedbootstate    = green
    ro.boot.veritymode           = enforcing
    ro.secureboot.lockstate      = locked
  (on a device whose bootloader is UNLOCKED and publishes none of these)
  -> vendor KeyMint HAL computes its root of trust from exactly these values
  -> the TA is handed a root of trust the device never actually had
  -> TA rejects -> KEYMINT_NOT_CONFIGURED
  -> (from here, identical to blocker 1) vold cannot create the FBE key
  -> /data never mounts -> splash hang
```

> **SUPERSEDED — see §5.8a immediately below. The first line of this chain was
> checked against the vendor HAL and is false.** The chain is left here
> unaltered because §5.8a is a correction of it, and deleting the claim would
> hide what was believed and why.

Three things this explains that the timing theory never could:

1. **Determinism.** Failures are 2/2 and 3/3 on repeat attempts. A race should
   be flaky; a wrong constant should not be.
2. **Why the same error code appears** as blocker 1 — both end at the TA
   rejecting what the HAL reports, just for different reasons (wrong OS version
   vs wrong root of trust).
3. **Why AviumUI boots** despite carrying the vbmeta property *names*: it never
   sets them to fabricated values.

### 5.8a CORRECTION — the vendor HAL does not read these properties [PC]

Checked directly against `~/gsi-test/libkeymint.so` (122,672 bytes, `ELF
aarch64, for Android 31`). It contains **three** property names in total:

```
   87d0 ro.build.version.release
   87e9 ro.build.version.security_patch
   8f1d ro.vendor.build.security_patch
```

`strings -a libkeymint.so | grep -cE '^ro\.[a-z_.]+$'` returns **3**. No
`ro.boot.vbmeta.*`, no `verifiedbootstate`, no `secureboot`, no `veritymode`.
A case-insensitive symbol search for `rootoftrust|setbootparam|verifiedboot|
vbmeta` returns **zero**. What it exports is version machinery:

```
_ZN9keymaster12GetOsVersionEPKc          keymaster::GetOsVersion(char const*)
_ZN9keymaster12GetOsVersionEv            keymaster::GetOsVersion()
_ZN9keymaster16AndroidKeymaster14EarlyBootEndedEv
```

**The vendor KeyMint HAL cannot be reading what the init spoof sets.** The root
of trust reaches the TEE another way — almost certainly bootloader-to-TEE
directly, which is normal on MediaTek/TrustKernel.

**Dead:** the chain in §5.8.
**Untouched:** the fix itself, hardware-verified on four ROMs / three lineages.
**Untouched:** the spoof table's existence and its position in init.
**Reopened:** why it breaks the boot.

And note what the original analysis already said: in the init-blocker case the
`-64` does **not** come from the TrustKernel TA — the TA is never loaded (zero
TEE kernel lines). It comes from the **emulated fallback** keystore2 installs
after the vendor HAL fails to register with servicemanager. So the real
question was never "what does the TA reject". It is **"why does the vendor
KeyMint HAL fail to register when this init is used"** — and the spoof table is
still the best-evidenced difference, just not via the route assumed.

Unexamined candidates from the table that could affect early service startup:

- `ro.crypto.state=encrypted` — `ro.` props are **write-once**, so pre-setting
  it could make vold's own set fail and change the FBE path
- `ro.build.type=user`, `ro.build.tags=release-keys`, `ro.debuggable`,
  `ro.secure`, `ro.adb.secure` — these gate which services start and how
  SELinux is applied; a domain transition that does not happen would stop the
  HAL registering
- `ro.boot.veritymode=enforcing` — affects fs_mgr / dm-verity setup

**Method note:** this correction cost one `strings` call and one symbol dump on
a file that had been sitting extracted in `~/gsi-test/` the whole time — and it
overturned a conclusion already written into three documents and a commit
message. The disassembly was sound; the error was assuming, rather than
checking, who consumed the table.

**Bonus:** it independently confirms the vendor patch. `VENDOR_SELINUX_PROP_FIX.md`
patches `libkeymint.so` at decimal offsets **34768** and **34793** — exactly
`0x87d0` and `0x87e9` above. Right bytes, and the HAL reading only these three
properties is precisely why that approach works.

### 5.9 Status of this hypothesis — read carefully

**[PC], strongly evidenced — but not yet [HW].**

What is proven: the table exists, its values are `locked`/`green`/`enforcing`/
`release-keys`, it is referenced by live instructions, and it sits in a
function that runs on every boot. That is a large step beyond string presence.

What is **not** proven: that these properties are the *cause*. The chain from
"init sets `ro.boot.vbmeta.device_state=locked`" to "TrustKernel TA rejects" is
**[INF]** — reasoned from what KeyMint implementations generally read, not
measured on this device.

**The test that settles it costs one boot and no binary surgery.** See §5.11.

### 5.10 Theories tested and killed

**Timing theory: [DEAD].** The claim was that extra early-boot work loses the
servicemanager registration race — on a good boot the HAL registers ~54 ms in
and keystore2 finds it; on a bad boot keystore2 gives up and installs the
emulation wrapper **2 ms** in, caches that choice, and never re-checks, so the
TrustKernel trustlet is never loaded at all (34 TEE kernel lines on a good
boot; **zero** on a bad one).

Test: Infinity's own init rebuilt with the vbmeta device path repointed at a
non-existent node — 6 bytes changed, everything else byte-identical — so
`open()` fails instantly and `ioctl`/`lseek` never run. **It still hung [HW].**

> Worth stating plainly because it was the working assumption for days. Note
> the *observations* above (54 ms, 2 ms, 34-vs-0 TEE lines) are real and still
> stand — it is the causal story built on them that failed.

**Two failed isolation attempts — both invalid patches, not invalid theory:**

| # | patch | why it proved nothing |
|---|---|---|
| 1 | repoint vbmeta device path (6 bytes) | on open failure the code **still sets the property** from a fallback — the strings say so: `Property 'ro.boot.vbmeta.digest' set successfully to dynamic fallback value`. It changed *which* value was written, not *whether* one was. |
| 2 | rename prefix `ro.boot.vbmeta.` -> `ro.zzzz.vbmeta.` (7 bytes) | disassembly afterwards showed the function references the **full** names (`.device_state`, `.digest`, `.size`), never the prefix. The three properties feeding the HAL were still set; only two secondary ones were neutralised. |

> **The rule, stated once so it is not broken a third time:** confirm which
> string the *code references* — via `adrp`/`add` cross-reference — before
> patching it. Presence in the binary says nothing about which copy a given
> call site uses, and a miss costs a full flash-and-boot cycle to discover.

§5.7 is the first analysis in this project to actually follow that rule.

### 5.11 The decisive test, not yet run

The hypothesis predicts something **directly observable, with no patching at
all**. Infinity ships `/adb_keys`, so adb authorises even without `/data`:

```sh
adb shell getprop | grep -E "vbmeta|secureboot|is_ever_orange|verifiedboot|flash.locked"
```

Run it on the **hung** boot, and on a **working** one (Project CiRCLE).

- **Hung boot shows `ro.boot.vbmeta.device_state=locked`,
  `ro.boot.verifiedbootstate=green`** while the working boot shows them absent
  or bootloader-supplied -> **hypothesis confirmed outright.**
- **Both look the same** -> hypothesis is dead, and the difference is elsewhere
  in init.

A corrected binary patch (`inf_init_norot2`, 19 bytes, the **full** names
redirected) is built and published for afterwards — but **the observation
should come first.** That ordering is the lesson from §5.10.

### 5.12 A user-facing consequence nobody has evaluated

If §5.7 is right, swapping in Circle's init **removes the Play Integrity
spoofing** — `ro.build.tags`, `ro.build.type`, `ro.boot.verifiedbootstate` and
the rest revert to their honest values.

**[INF] — untested.** Practical implications to check on a booted, init-swapped
ROM:

- Play Integrity basic/device verdict
- Google Wallet / banking apps
- Netflix HD (though that is Widevine L1, which is vendor-side and probably
  unaffected)
- The crDroid 11 "allow HD Netflix by spoof" toggle — likely a *different*
  mechanism, but worth confirming it still functions

This has never been measured, and it is a real trade-off a daily-driver user
would care about.

> **Also worth noting:** an earlier observation that "Axion 2.8 reports
> `release-keys`" may itself be an artefact of this spoof rather than a
> property of the build. Any past reasoning that used `ro.build.tags` as
> evidence about a ROM's provenance should be re-examined.

---

## 6. Blocker 3 — Axion instant DSU revert (UNSOLVED)

### 6.1 Failure-shape triage

The shape of the failure determines which fix applies. Getting this wrong wastes
cycles.

| shape | meaning | applicable fix |
|---|---|---|
| **GSI-splash hang** | boot reached userspace; init ran | version patch + donor init |
| **Instant revert** | image rejected before init ran | **unknown — this section** |
| **OEM-splash hang** | never reached userspace | out of scope |

Axion 2.7 and 2.8 both instant-revert.

### 6.2 Six hypotheses eliminated, with the evidence

| # | hypothesis | verdict | evidence |
|---|---|---|---|
| 1 | in-place patch corrupted the image | **[PC] NO** | `e2fsck -fn` fully clean |
| 2 | dm-verity / AVB invalidated | **[PC] NO** | `avbtool verify_image` passes footer **and** full sha256 hashtree |
| 3 | version patch did not apply | **[PC] NO** | all three `build.prop` read `release=13` |
| 4 | image too large for DSU | **[PC] NO** | Axion 3.84 GB is the **smallest** of the three; Infinity 4.36 GB and Lunaris 4.71 GB both boot |
| 5 | init is at fault | **[HW] NO** | 2.8 still reverts with Circle's init swapped in — and a revert precedes init anyway |
| 6 | AVB `os_version` property descriptor | **[HW] NO** | see §6.4 |

Verbatim `e2fsck` output on the patched image:

```
$ e2fsck -fn axion28-work.img
e2fsck 1.47.2 (1-Jan-2025)
Pass 1: Checking inodes, blocks, and sizes
Pass 2: Checking directory structure
Pass 3: Checking directory connectivity
Pass 4: Checking reference counts
Pass 5: Checking group summary information
/: 8966/9280 files (3.5% non-contiguous), 913596/921809 blocks
```

Verbatim AVB verification:

```
$ avbtool verify_image --image system.img
Verifying image system.img using embedded public key
vbmeta: Successfully verified footer and SHA256_RSA2048 vbmeta struct in system.img
system: Successfully verified sha256 hashtree of system.img for image of 3775729664 bytes
```

### 6.3 The Axion image's own properties [PC]

```
ro.system.build.fingerprint=google/axion_gsi/tdgsi_arm64_ab:16/BP4A.251205.006/26081809:user/release-keys
ro.system.build.id=BP4A.251205.006
ro.system.build.tags=release-keys
ro.system.build.version.release=13
ro.system.build.version.release_or_codename=13
ro.system.build.version.sdk=36
ro.build.version.release=13
ro.build.version.release_or_codename=13
ro.build.version.release_or_preview_display=16
ro.build.version.security_patch=2025-09-05
ro.build.tags=release-keys
ro.build.flavor=mainline-user
ro.axion.version=2.8-ONEIRA_FINAL-20260824-UNOFFICIAL-PICO-GSI-Dozeoff
ro.axion.build.version=2.8
ro.axion.releasetype=UNOFFICIAL
persist.sys.axion_maintainer=Doze-off
```

Compare the **booting** Infinity image:

```
ro.build.fingerprint=google/infinity_gsi_gapps/tdgsi_arm64_ab:16/BP4A.251205.006/eng.androi.20260803.014040:user/release-keys
ro.build.id=BP4A.251205.006
ro.build.version.release=13
ro.build.version.release_or_codename=13
ro.build.version.release_or_preview_display=16
ro.build.version.security_patch=2025-09-05
ro.build.version.sdk=36
ro.build.tags=release-keys
ro.build.flavor=infinity_gsi_gapps-user
```

> **Identical `ro.build.id` (BP4A.251205.006) and identical sdk (36)** — one
> boots, one does not. This is exactly why the patcher's compatibility table
> refuses to predict from version/build-id heuristics and only reports outcomes
> actually observed on hardware.

Note also: `ro.build.version.release_or_preview_display=16` is **not** patched
in either image. It is not a blocker.

Axion is a **PICO** GSI (minimal, no GMS). Whether that is relevant is
**[INF] — unexamined.**

### 6.4 The `os_version` experiment (2026-08-31) [HW] — FAILED

The Kotlin patcher preserves the original vbmeta **verbatim** (by design — the
edit is length-preserving, see §9.2), so Axion retained upstream property
descriptors absent from every booting image:

```
Footer version:           1.0
Image size:               3836354560 bytes
Original image size:      3775729664 bytes
VBMeta offset:            3835555840
Release String:           'avbtool 1.3.0'
Public key (sha1):        cdbb77177f731920bbe0a0f94f84d9038ae0617d   # AOSP testkey
Rollback Index:           1782864000
    Hashtree descriptor:
      Salt:         395c5d5223d09d6fe10158403ebf2c4af95d48c223d1a0ccfc357e518a0263e2
      Root Digest:  b2aa2d0faf042b00bdef0abe7b22bf679779cdcd4cd979c262cdb909c560b134
    Prop: com.android.build.system.os_version -> '16'          <-- the suspect
    Prop: com.android.build.system.fingerprint -> 'google/axion_gsi/...:16/...'
    Prop: com.android.build.system.security_patch -> '2026-07-01'
```

Booting Infinity, for comparison, has **no property descriptors at all** and
`Release String: 'avbtool 1.2.0'`.

The vbmeta was rebuilt with `os_version:13`, everything else held constant:

```sh
avbtool erase_footer --image axion28-avb13.img
truncate -s 3775729664 axion28-avb13.img       # erase_footer already does this
avbtool add_hashtree_footer \
  --image axion28-avb13.img --partition_name system \
  --partition_size 3836354560 --hash_algorithm sha256 \
  --salt 395c5d5223d09d6fe10158403ebf2c4af95d48c223d1a0ccfc357e518a0263e2 \
  --rollback_index 1782864000 --algorithm SHA256_RSA2048 \
  --key external/avb/test/data/testkey_rsa2048.pem --do_not_generate_fec \
  --prop com.android.build.system.os_version:13 \
  --prop com.android.build.system.fingerprint:'google/axion_gsi/...' \
  --prop com.android.build.system.security_patch:2025-09-05
```

**Single variable confirmed:** the rebuilt image's root digest came back
**byte-identical** — `b2aa2d0faf042b00bdef0abe7b22bf679779cdcd4cd979c262cdb909c560b134`
— proving the filesystem data was untouched and only the vbmeta changed. Final
size matched the original exactly.

**Result: instant revert again [HW]. Hypothesis dead.**

Artefact: `~/gsi-test/axion28-avb13.img.gz`, sha256
`f3de0e3592d877582e6be71612371c00eac3a88f28d75b161f272566a6c59fae`.

### 6.5 Why the logcat captured so far is uninformative

The capture taken after a revert shows only the **host** booting:

```
08-31 18:14:39.478  1692  1692 I update_engine: [INFO:cleanup_previous_update_action.cc(192)] Boot completed, waiting on markBootSuccessful()
08-31 18:14:39.486  1692  1692 I update_engine: [INFO:boot_config.cpp(46)] [libfstab] Using Android DT directory /proc/device-tree/firmware/android/
08-31 18:14:39.489  1692  1692 I update_engine: [INFO:snapshot.cpp(4055)] EnsureMetadataMounted does nothing in Android mode.
08-31 18:14:39.498  1692  1692 I update_engine: [INFO:cleanup_previous_update_action.cc(291)] Waiting for any previous merge request to complete.
08-31 18:14:39.500  1692  1692 I update_engine: [INFO:snapshot.cpp(1148)] CheckMergeState for snapshots returned: None
08-31 18:14:39.502  1692  1692 I update_engine: [INFO:cleanup_previous_update_action.cc(328)] Can't find any snapshot to merge.
08-31 18:14:39.507  1692  1692 I update_engine: [INFO:action_processor.cc(116)] ActionProcessor: finished last action CleanupPreviousUpdateAction with code ErrorCode::kSuccess
```

Not one `gsid` or `DynamicSystemInstallationService` line.

> **CAVEAT — logcat does not survive a reboot.** By the time you are back in the
> host, the failed boot's log is gone. A post-revert capture can only ever show
> the aftermath. This is why every Axion diagnosis so far has been inference.

### 6.6 The control that has NOT been run — highest priority

**Does stock, unmodified Axion 2.8 instant-revert too?**

Six hypotheses eliminated with nothing found should shift the suspicion:
perhaps our patching was never implicated at all. This control was never run,
and it should have been first.

- **Stock also reverts** -> Axion is simply DSU-incompatible on this device.
  Nothing in the patcher can fix it; stop spending cycles here. **[INF] most
  likely.**
- **Stock boots, even to a hang** -> our pipeline breaks it. That is a real bug
  affecting every future image, not just Axion.

Cost: one DSU cycle, no patching, no risk.

### 6.7 Provenance caveat — a real confounder

`inf312-initswap-osver13` and `lunaris310-osver13` were produced by the **shell
script** (`Release String: avbtool 1.2.0`, `shared_blocks` cleared by
`e2fsck -E unshare_blocks`, image grown). `axion28-osver13` was produced by the
**Kotlin patcher** (original vbmeta preserved, `avbtool 1.3.0`, `shared_blocks`
still set, size unchanged).

Superblock `s_feature_ro_compat` values [PC]:

```
axion28-osver13.img.gz            0x407b   shared_blocks SET
inf312-initswap-osver13.img.gz    0x007b   shared_blocks CLEARED
lunaris310-osver13.img.gz         0x007b   shared_blocks CLEARED
circle.img.xz      (unpatched)    0x407b   shared_blocks SET
inf312.img.xz      (unpatched)    0x407b   shared_blocks SET
```

**This is a difference in tooling provenance, NOT evidence about Axion.**
crDroid 11 was patched by the app alone — `shared_blocks` intact — and boots.
So `shared_blocks` is not fatal.

> But it has **not** been ruled out as an interaction specific to Axion.
> Re-patching Axion through the shell-script path (unshared, resized, re-signed
> with avbtool 1.2.0, props dropped) would eliminate the last provenance
> difference. It is a cheap test and has not been done.

---

## 7. Goal 2 — the vendor-side fix (built, never flashed)

Make *any* unmodified GSI boot with no per-image patching, by redirecting the
properties the KeyMint HAL reports to the TA.

### 7.1 The patch

`/vendor/lib64/libkeymint.so` binary-patched. Both replacements are shorter, so
they are written in place and NUL-padded — no relocation, no length change:

| offset | original (len) | replacement (len) |
|---|---|---|
| 34768 | `ro.build.version.release` (24) | `ro.vendor.kmosver` (17) |
| 34793 | `ro.build.version.security_patch` (31) | `ro.vendor.kmospatch` (19) |

`/vendor/build.prop` lines 643-644:

```
ro.vendor.kmosver=13
ro.vendor.kmospatch=2025-09-05
```

### 7.2 Why the first attempt bootlooped

**The properties were defined but never labeled.**

A property with no matching entry in any `*_property_contexts` file falls back
to the `default_prop` label. Property *reads* are not free on Android: since
Oreo the property area is split into one file per SELinux context under
`/dev/__properties__/`, and the reading domain needs `read`/`map` on that file.

The HAL's domain is `hal_keymint_default` (`vendor_file_contexts` labels the
binary `hal_keymint_default_exec`, and the policy carries
`type_transition init hal_keymint_default_exec:process hal_keymint_default`).
Querying the shipped `/vendor/etc/selinux/precompiled_sepolicy` with `sesearch
--allow -s hal_keymint_default -t default_prop -c file` returns nothing — the
read is denied, the HAL gets no value, and it bootloops.

Fixed by adding `vendor_property_contexts` lines 811-812.

### 7.3 Verification status [PC]

- Base confirmed **20241121**, matching the vendor actually running on the
  device. (An earlier build of this patch used base 20250423 — **wrong** — and
  was caught before flashing.)
- All three components present in `vendor_kmsel.img`: `build.prop` 643-644
  define the props, `vendor_property_contexts` 811-812 label them, and
  `libkeymint.so` contains `ro.vendor.kmosver` / `ro.vendor.kmospatch` with
  `ro.build.version.release` / `ro.build.version.security_patch` no longer
  present.
- AVB re-verified: stock salt `15aa12b7...`, stock partition size 1006141440,
  root digest `b9faed28...`, `avbtool verify_image` passes footer and full
  hashtree.

**Never flashed. Flashing vendor risks `/data`.**

### 7.4 Does the init finding change the vendor patch? [INF]

Probably not, and this matters for prioritisation. The vendor patch addresses
the **OS version** the HAL reports (blocker 1). The init spoof concerns the
**root of trust** (blocker 2). They are different inputs to the same TA.

> **ANSWERED 2026-09-01 — and the answer is no.** `libkeymint.so` reads only
> three properties, all version-related, and has no root-of-trust plumbing at
> all (§5.8a). There is nothing to redirect: the RoT reaches the TEE by another
> path, almost certainly bootloader-to-TEE directly. A vendor patch cannot
> neutralise blocker 2 this way.
>
> The upside: this **confirms the vendor patch is correctly targeted**. Its
> documented offsets 34768 / 34793 are exactly the `0x87d0` / `0x87e9` string
> locations, and the HAL reading only these three properties is why redirecting
> them works at all.

---

## 8. TWRP

Recovery builds and touch works. Real builds run in **GitHub Actions**, not
locally; `m nothing` locally is the fast sanity check.

**`/data` decryption is broken.** Crypto was disabled to get TWRP building at
all. `BoardConfig.mk` currently has the flags re-enabled but **uncommitted**:

```diff
-# TW_INCLUDE_CRYPTO := true
-# TW_INCLUDE_FBE := true
-# TW_INCLUDE_FBE_METADATA_DECRYPT := true
+TW_INCLUDE_CRYPTO := true
+TW_INCLUDE_FBE := true
+TW_INCLUDE_FBE_METADATA_DECRYPT := true
```

### The untried fix — recommended next action overall

```make
RELEASE_PLATFORM_VERSION := 13
```

**Rationale [INF]:** the same version mismatch that breaks GSI KeyMint plausibly
breaks TWRP's FBE metadata decrypt, since TWRP must talk to the same TA under
the same version rules.

**Why it is the best next action:** zero flash risk (build-time only), the
crypto flags are already staged, and builds run in CI. If it works, `/data`
decryption comes without touching vendor at all — making the risky
`vendor_kmsel.img` flash unnecessary for that purpose.

---

## 9. The patcher tool

`tools/gsi-patcher` — **3,572 lines of Kotlin** across `core` / `cli` / `app`.
Shipped as **v5.1** (`versionCode 9`), debug-signed (local utility, not a Play
distribution), `minSdk 26`, `compileSdk 34`, JVM target 17.

### 9.1 Module map

| module | file | lines | role |
|---|---|---|---|
| core | `Ext4.kt` | 350 | read/write ext4 without mounting or root |
| core | `Ext4Alloc.kt` | 240 | real ext4 block allocation, on-device |
| core | `Avb.kt` | 290 | vbmeta parse, root-digest poke, re-sign |
| core | `HashTree.kt` | 95 | dm-verity hashtree rebuild |
| core | `GsiPatcher.kt` | 269 | orchestration |
| core | `Preflight.kt` | 275 | pre-write checks |
| core | `Compatibility.kt` | 222 | tested-image verdicts |
| core | `Payload.kt` | 176 | OTA `payload.bin` |
| core | `Ingest.kt` | 154 | container detection / extraction |
| core | `Protobuf.kt` | 74 | minimal wire-format reader |
| core | `InitSwap.kt` | 151 | donor init validation + write |
| core | `Compression.kt` | 120 | gz / xz |
| core | `Io.kt` | 100 | random-access image IO |
| core | `BuildProp.kt` | 138 | length-preserving property edits |
| cli | `Main.kt` | 264 | JVM CLI |
| app | `MainActivity.kt` | 577 | Android UI |
| app | `DeviceProbe.kt` | 77 | read `ro.keymaster.*`, vendor API level |

### 9.2 The core design decision: length-preserving edits

~60% of the original shell script exists to work around one thing: it
loop-mounts the filesystem so it can run `sed`. That forces `truncate +2G`,
`e2fsck -E unshare_blocks`, `resize2fs`, and a shrink afterwards — which is why
it needs root and why it permanently inflates the image.

None of it is necessary, because **every substitution is length-preserving**:

| property | typical before | after | length |
|---|---|---|---|
| `ro.build.version.release` | `15` / `16` | `13` | 2 -> 2 |
| `ro.build.version.release_or_codename` | `15` / `16` | `13` | 2 -> 2 |
| `ro.build.version.security_patch` | `2025-12-01` | `2025-09-05` | 10 -> 10 |

Bytes are patched where they lie. Same length, same inode, same block
allocation, no `metadata_csum` recomputation. Consequently `image_size`,
`tree_offset`, `tree_size` and the vbmeta blob length are all unchanged — so
the hashtree is recomputed in place, the new root digest poked into the
existing descriptor, and the whole re-signed.

From `Avb.kt`:

> *Because the patched image is byte-for-byte the same size, image_size,
> tree_offset, tree_size and the vbmeta blob length are all unchanged. That
> means nothing has to be relaid out: we recompute the hashtree in place, poke
> the new root digest into the existing descriptor, and re-sign. Every other
> descriptor (chain partitions, properties, kernel cmdline) is preserved
> verbatim, and so is the embedded public key.*

> **CAVEAT — that verbatim preservation is exactly why Axion kept its
> `os_version=16` prop (§6.4).** Correct behaviour, but it is a design choice
> with observable consequences, and it is what made the two patched-image
> families differ in provenance (§6.7).

### 9.3 The silent-corruption bug (pre-existing, found and fixed)

`writeFileInPlace` **silently discarded data written into sparse holes** —
7,514 non-zero bytes lost on a real init swap, the file reading back with zeros
where they should have been. Found by extracting init back out of the image and
comparing it against the donor.

```kotlin
private fun requireNoDataInHole(data: ByteArray, from: Long, to: Long, size: Long) {
    var nonZero = 0L
    for (i in from until to) if (data[i.toInt()].toInt() != 0) nonZero++
    require(nonZero == 0L) {
        "this file is sparse -- it has a hole at bytes $from..${to - 1} (of $size) with no " +
            "block allocated, and the new content puts $nonZero non-zero byte(s) there. " +
            "Writing them would need block allocation, which this in-place patcher does not " +
            "do; they would otherwise be silently lost and the file would read back with " +
            "zeros in their place."
    }
}
```

Called for every gap between mapped extents:

```kotlin
for (r in mappedRanges) {
    if (r.first > cursor) requireNoDataInHole(data, cursor, r.first, size)
    cursor = maxOf(cursor, r.last + 1)
}
if (cursor < size) requireNoDataInHole(data, cursor, size, size)
```

Plus an **unconditional read-back assertion** after every init write:

```kotlin
val padded = if (donor.size == destSize) donor else donor.copyOf(destSize)
var relocated = false
try { fs.writeFileInPlace(ino, padded) }
catch (e: IllegalArgumentException) { fs.writeFileRelocated(ino, padded); relocated = true }
val readBack = fs.readFile(ino)
require(readBack.size == padded.size && readBack.contentEquals(padded)) {
    "init did not read back as written -- refusing to continue; the image is not safe to use"
}
```

> That assertion immediately caught a **second, brand-new** bug: my own
> allocator write-loop passed `chunk` with offset 0, so every block received
> block 0's bytes. Fixed to `io.write(base + run.start * blockSize, data, 0,
> data.size)`.
>
> **Lesson:** verify against the artefact, not the build log. Read the file
> back out of the image and compare it.

### 9.4 Block allocation (`Ext4Alloc.kt`)

Needed because donor inits differ in size from the destination. It **relocates**
the file to freshly allocated contiguous blocks rather than filling holes —
keeping a single inline extent and, critically, avoiding writes into
**deduplicated** blocks.

Implements `allocateContiguous`, `repointToSingleExtent`, and crc16 group-
descriptor checksums. The filesystems in question carry
`ext_attr resize_inode dir_index filetype extent 64bit flex_bg sparse_super
large_file huge_file uninit_bg dir_nlink extra_isize shared_blocks` — notably
**no `metadata_csum`**.

Old blocks are **abandoned, not freed**, for dedup safety.

> **Known, accepted consequence:** `e2fsck` reports a block-bitmap difference on
> relocated images. This is expected, not corruption. Do not "fix" it — freeing
> those blocks could corrupt files that share them.

GSIs ship with `shared_blocks` (`s_feature_ro_compat` bit `0x4000`). Because a
length-preserving edit never needs to allocate, the patcher never unshares, and
images keep their original size.

### 9.5 Donor input handling

`resolveDonor()` accepts **either** a bare `init` binary **or** an entire raw
GSI image, deciding by ELF magic, and validates in the background at pick time
(so a bad donor is rejected before the user commits to a long patch run).

Donors smaller than the destination are **zero-padded**. This is safe for ELF —
trailing zeros are not parsed — and it preserves the length-preserving
invariant, keeping the in-place path available. Circle's init (2,708,416) is
smaller than every destination seen so far, so relocation has not been needed
in practice.

### 9.6 Container ingestion

Accepts raw `.img`, `.img.gz`, `.img.xz`, `.7z`, a bare OTA `payload.bin`, or a
full OTA `.zip` containing one. Field numbers in `Payload.kt` were transcribed
from `system/update_engine/update_metadata.proto`.

This exists so a ROM project's **official release** can be handed to the app
directly, rather than requiring someone to extract a bare system image first.

### 9.7 The compatibility table's design principle

From `Compatibility.kt`:

> *Deliberately **not** a heuristic predictor. LineageOS 23.2 and Infinity-X
> 3.12 ship the identical `ro.build.id` (BP4A.251205.006) and the same sdk 36,
> yet the first drives this device's KeyMint V1 HAL and the second does not.
> Any rule derived from platform version, build id or API gap would therefore
> condemn a known-good image. So the verdicts come from images actually tested
> on hardware, and everything else is reported as fact rather than prophecy.*

Tested verdicts currently encoded:

| match | sdk | boots | note |
|---|---|---|---|
| lineage | 34 | yes | LineageOS 21, verified via DSU |
| lineage | 35 | yes | LineageOS 22.x — **daily driver** |
| lineage | 36 | yes | LineageOS 23.2, four-generation gap over vendor |
| circle | 36 | yes | verified with `/data` mounted, no KeyMint errors |
| avium | 36 | yes | verified via DSU |
| infinity | 36 | yes | **requires donor init** |
| infinity | 35 | no | 2.9 instant-reverted — predates the signing-key fix, worth retrying |
| lunaris | any | yes | requires **both** fixes |
| crdroid | any | yes | requires **both** fixes; crDroid 10 and 11 |
| axion | any | **no** | instant revert; see §6 |
| phh / peter | any | yes | boots unpatched — spoofs the version itself |

`LARGEST_GAP_KNOWN_TO_BOOT = 5` (LineageOS 23.2 sdk 36 on a vendor at API 31).
A larger gap produces a warning; anything at or below is reported as fact.

---

## 10. Native LineageOS build

Reached **99%** via `mka bacon` with a full image set produced. **Never
flash-tested.**

- Tree at `/opt/lineage-td` is **staged but unsynced** — 22 MB, manifests only.
  Run `~/sync-lineage-td.sh`.
- Needs the GCP VM (`lineage-builder`, us-west1-b, currently **stopped**) for
  the 96 GB AOSP tree.
- Moved to `/opt` specifically because of the `repo init` hijack trap (§2.3).

An earlier prediction that `netbpfload` would cause a bootloop was **wrong**,
and LineageOS 23.2 does boot. Recorded because it is a corrected belief.

---

## 11. Complete artefact inventory

### 11.1 Images (`~/gsi-test/`)

```
1934463099  axion28-avb13.img.gz            os_version=13 test — FAILED [HW]
                                            sha256 f3de0e3592d877582e6be71612371c00eac3a88f28d75b161f272566a6c59fae
1847253659  axion28-osver13.img.gz          version patch only — instant revert [HW]
2018735710  inf312-initswap-osver13.img.gz  BOOTS [HW] — contains circle_init
2442415940  lunaris310-osver13.img.gz       BOOTS [HW]
1273862876  circle.img.xz                   donor source
1403772476  inf312.img.xz                   pristine Infinity-X 3.12
```

### 11.2 Init binaries

See §5.4 for hashes. Also present:

```
inf_init_novbmeta   2724744   test 1 — vbmeta path repointed (6 bytes). FAILED [HW]
inf_init_norot      2724744   test 2 — prefix renamed (7 bytes). FAILED [HW]
inf_init_norot2     2724744   corrected — FULL names redirected (19 bytes). UNTESTED
```

### 11.3 Other

```
GsiKeyMintPatcher-v5.1.apk   1728167   the shipped app
libkeymint.so                 122672   vendor HAL, extracted for analysis
circle_init_strings.txt       144437   full string dump
inf_init_strings.txt          145920   full string dump
circle_vold / inf_vold       1056744   differ by 16 bytes — ruled out early
```

### 11.4 Repository

`trinineba-oss/device_umidigi_g7tabpro`, branch `main`.

Key documents:

```
docs/PROJECT_DOSSIER.md          this project, condensed
docs/INIT_SWAP_FIX.md            blocker 2 in depth
docs/KEYMINT_OS_VERSION_FIX.md   blocker 1 in depth
docs/VENDOR_SELINUX_PROP_FIX.md  the vendor-side fix
docs/SESSION_HANDOFF.md          earlier handoff
docs/INSTALL.md                  end-user instructions
tools/patch-gsi-keymint.sh       the original PC-side script
tools/patch-vendor-keymint-selinux.sh
tools/gsi-patcher/               the Kotlin app + CLI
```

Releases: `gsi-patcher-v5.1` (latest), `v1.0-touch-working` (TWRP, pre-release).

> **Uncommitted work in the tree:** `BoardConfig.mk` carries a parallel
> session's TWRP crypto re-enable (§8). It has been deliberately excluded from
> every commit made by this line of work. Do not sweep it into an unrelated
> commit.

---

## 12. Reproducing the analysis

Every claim tagged **[PC]** can be re-derived with these commands. If you doubt
a number in this document, re-run the command rather than trusting the text.

### 12.1 Read an image without mounting or root

```sh
debugfs -R "cat /system/build.prop" image.img
debugfs -R "ls -l /system/etc/vintf"  image.img
debugfs -R "dump /system/bin/init /tmp/extracted_init" image.img
```

> Loop-mounting works but the files are root-owned and unreadable as the normal
> user. `debugfs` sidesteps that entirely.

### 12.2 Integrity and AVB

```sh
e2fsck -fn image.img

python3 ~/external/avb/avbtool.py info_image --image image.img

ln -s /path/to/image.img system.img     # MUST be named after the partition
python3 ~/external/avb/avbtool.py verify_image --image system.img
```

### 12.3 ext4 feature flags (`shared_blocks` detection)

`s_feature_ro_compat` lives at offset `0x464` (superblock at `0x400` + `0x64`):

```sh
xxd -s 0x464 -l 4 image.img      # 7b40 0000 = 0x407b -> shared_blocks SET
                                 # 7b00 0000 = 0x007b -> cleared
```

### 12.4 The init string correlation

```sh
cd ~/gsi-test
for f in circle_init avium_init dozeoff_init inf_init lunaris_init axion_init; do
  printf "%-16s GetVbmeta=%-3s secureboot=%-3s ever_orange=%-3s locked=%-3s green=%-3s\n" $f \
    "$(strings -a $f | grep -c 'GetVbmeta')" \
    "$(strings -a $f | grep -c 'ro.secureboot')" \
    "$(strings -a $f | grep -c 'ro.is_ever_orange')" \
    "$(strings -a $f | grep -cx 'locked')" \
    "$(strings -a $f | grep -cx 'green')"
done
```

Exact set unique to the failing inits:

```sh
for f in circle_init avium_init dozeoff_init inf_init lunaris_init; do
  strings -a $f | LC_ALL=C sort -u > /tmp/$f.str
done
LC_ALL=C comm -12 /tmp/inf_init.str /tmp/lunaris_init.str > /tmp/fail_common.str
cat /tmp/circle_init.str /tmp/avium_init.str /tmp/dozeoff_init.str \
  | LC_ALL=C sort -u > /tmp/boot_any.str
LC_ALL=C comm -23 /tmp/fail_common.str /tmp/boot_any.str
```

> **Use `LC_ALL=C sort`.** Without it `comm` reports "input is not in sorted
> order" and produces subtly wrong output — an error present in an earlier run
> of this analysis.

### 12.5 Cross-referencing strings to code (the method that matters)

This is the technique that turned §5.7 from correlation into evidence, and the
one whose *absence* caused two wasted flash cycles.

```sh
OD=~/prebuilts/clang/host/linux-x86/clang-r416183b/bin/llvm-objdump
$OD -d --no-show-raw-insn inf_init > inf_init.dis
readelf -lW inf_init | head -14      # confirm file offset == vaddr for LOADs
strings -a -t x inf_init | grep -E "locked|green|GetVbmeta"   # get target offsets
```

Then resolve `adrp`/`add` pairs to string addresses:

```python
import re, collections
adrp = {}; hits = collections.defaultdict(list)
pa  = re.compile(r'^\s*([0-9a-f]+):\s+adrp\s+(x\d+), (0x[0-9a-f]+)')
pad = re.compile(r'^\s*([0-9a-f]+):\s+add\s+(x\d+), (x\d+), #(\d+)')
for line in open('inf_init.dis'):
    m = pa.match(line)
    if m: adrp[m.group(2)] = int(m.group(3), 16); continue
    m = pad.match(line)
    if m and m.group(3) in adrp:
        addr = adrp[m.group(3)] + int(m.group(4))
        if addr in targets: hits[targets[addr]].append(int(m.group(1), 16))
```

To find the **enclosing function**, collect every `bl` target and take the
largest one at or below the site — `bl` targets are real function entries:

```python
targets = {int(m.group(1),16) for line in open('inf_init.dis')
           if (m := re.search(r'\bbl\s+(0x[0-9a-f]+)', line))}
entry = max(t for t in targets if t <= site)
```

> **Do NOT find function starts by walking back to the previous `ret`.** That
> was tried, it lands mid-function, and it produced a wrong answer earlier in
> this project.

---

## 13. Diagnostic playbook

### 13.1 DSU install phase — no root, no reboot

The most informative capture available, and it has **never been run**. Start it
*before* the install:

```sh
adb logcat -b all -c && adb logcat -b all | grep -iE 'gsid|DynamicSystem|dm-verity|device-mapper|avb'
```

### 13.2 The failed boot itself

Survives only in pstore. Needs root. Wiped by a cold power-off; a normal reboot
preserves it.

```sh
adb shell su -c 'cat /sys/fs/pstore/console-ramoops' | grep -iE 'dsu|gsid|verity|dm-|init'
```

### 13.3 Did DSU even try?

```sh
adb shell getprop | grep -iE 'dynamic_system|dsu|gsid'
```

If `ro.boot.dynamic_system` is absent or `0`, first-stage init never attempted
the DSU mount — which points at the host/bootloader side and means **no amount
of image patching helps.**

### 13.4 The root-of-trust observation (§5.11)

```sh
adb shell getprop | grep -E "vbmeta|secureboot|is_ever_orange|verifiedboot|flash.locked"
```

Run on a hung boot **and** on a working one. Infinity ships `/adb_keys`, so adb
authorises without `/data`.

### 13.5 Confirming a splash hang is the KeyMint chain

```sh
adb shell getprop | grep -E 'init.svc.(zygote|vold|keystore2|pq)'
adb logcat -b all | grep -E 'KEYMINT_NOT_CONFIGURED|TEE return|read_key failed'
```

Ignore the PQ storm entirely — it is downstream noise (§4.1).

---

## 14. Recommended next actions, ranked

| # | action | why now | risk | evidence value |
|---|---|---|---|---|
| 1 | TWRP `RELEASE_PLATFORM_VERSION := 13`, build in CI | flags already staged; unblocks `/data` without touching vendor | **none** | high |
| 2 | `getprop` on a hung vs working boot (§5.11) | settles the §5.7 mechanism outright | **none** | **decisive** |
| 3 | Stock unpatched Axion via DSU (§6.6) | splits "Axion is incompatible" from "our pipeline breaks it" | **none** | decisive |
| 4 | Check whether `libkeymint.so` reads root-of-trust props (§7.4) | could neutralise **both** blockers device-wide | none (static) | **very high** |
| 5 | Re-patch Axion via the shell-script path (§6.7) | removes the last provenance confounder | none | medium |
| 6 | Evaluate Play Integrity after an init swap (§5.12) | unmeasured user-facing trade-off | none | medium |
| 7 | Flash `vendor_kmsel.img` | goal 2 | **risks /data** | high |
| 8 | `repo sync` `/opt/lineage-td`, flash-test native build | separate thread | high | — |

Items 1-4 are all zero-risk and none has been done. **Do those before anything
that involves flashing.**

---

## 15. Errors already made — and corrected

Recorded so they are not repeated, and so a reader can calibrate how much to
trust the rest.

| error | correction |
|---|---|
| Treated 147k PQ-service errors as the fault | they were three layers downstream; the real error was `-64` |
| Patched only `/system/build.prop` | A16 GSIs duplicate the version in `/product/etc/build.prop`; `ro.` props are write-once |
| Timing theory presented as the explanation | falsified on hardware [DEAD] |
| Patched a binary based on string presence | twice; both patches missed the string the code actually references |
| Found function starts by walking back to `ret` | lands mid-function; use `bl` targets |
| Assumed Axion ships EROFS | it does not — plain ext4 |
| Assumed "UNOFFICIAL" in a title predicts `ro.build.tags` | Axion 2.8 is `release-keys` — and per §5.7 that may itself be spoofed |
| Assumed AVB was invalidated by our patch (this session) | `verify_image` passes; the patcher rebuilds the hashtree correctly |
| Built a vendor patch against base 20250423 | wrong base; caught before flashing |
| `comm` without `LC_ALL=C` | silently mis-sorted output |
| Allocator wrote block 0's bytes to every block | caught by the read-back assertion added minutes earlier |

---

## 16. Open questions and unexamined assumptions

**This is the section most likely to contain the next real finding.** Each item
is something nobody has checked.

### 16.1 About the init spoof table (§5.7)

1. ~~**Does `libkeymint.so` actually read those properties?**~~ **ANSWERED
   2026-09-01: NO.** Three properties total, all version-related, zero
   root-of-trust symbols. See §5.8a. This killed the §5.8 chain. The follow-on
   question is now: **what does read them on this device, if anything?**
   `keystore2` is the obvious candidate and has never been extracted or
   examined.
2. **Which specific property matters?** The table sets ~35. The suspicion falls
   on `ro.boot.vbmeta.device_state` and `ro.boot.verifiedbootstate`, but
   `ro.boot.veritymode=enforcing` and `ro.crypto.state=encrypted` are also
   plausible and completely unexamined.
3. **Are the properties actually *set*, or merely present in a table?** The
   stack table is built; nothing here proves the subsequent
   `__system_property_set` calls succeed. `ro.` props are write-once — if the
   bootloader or an earlier prop file already set them, init's write would be
   *rejected*, and the whole hypothesis collapses. **Nobody has checked
   whether these writes win.**
4. **Is the table conditional?** The disassembly shows the table being built,
   not the branch conditions around it. There may be a guard (`if
   bootloader_unlocked`, `if property absent`) that was never examined.
5. **Does Axion's init have the same table?** It has `locked`/`green` and 9
   `GetVbmeta*` strings but zero `ro.secureboot.*` — a *partial* profile. The
   table reconstruction was only run on Infinity's init.

### 16.2 About the vendor-side fix

6. **Could the `libkeymint.so` redirect technique be extended to root-of-trust
   properties?** (§7.4) If the HAL reads `ro.boot.vbmeta.device_state`
   directly, redirecting it to a vendor-owned property might make even
   spoof-carrying GSIs boot **unmodified**. This would collapse two blockers
   into one device-wide fix. **Nobody has looked.**
7. **Does the vendor patch survive an OTA / factory reset?** Unexamined.

### 16.3 About Axion

8. **Does stock unpatched Axion revert?** (§6.6) The missing control.
9. **Is `shared_blocks` implicated specifically for Axion?** (§6.7) Ruled out
   generally by crDroid 11, but not for Axion specifically.
10. **Is "PICO" relevant?** Axion is a PICO (minimal, no-GMS) GSI. No other
    PICO GSI has been tested on this device. There may be a whole class effect
    here and the sample size is one.
11. **What does DSU actually reject on?** No one has read `gsid` source or logs
    for this device. The entire Axion investigation is inference from the
    outside.
12. **Axion 2.7 vs 2.8** — both revert, but were they tested with the same
    tooling generation? 2.7's result predates several fixes.

### 16.4 About the fix's side effects

13. **Play Integrity / banking apps after an init swap** (§5.12) — completely
    unmeasured.
14. **Does the init swap affect anything else the spoof table touched?**
    `ro.debuggable`, `ro.adb.secure`, `ro.secure`, `ro.crypto.state` are all in
    that table. Swapping init changes all of them at once. Nobody has diffed a
    booted init-swapped system's `getprop` against a normal one.
15. **Is Circle's init missing features the host ROM needs?** It is a different
    build, 16 KB smaller. Four ROMs boot with it, but "boots" is not "behaves
    identically".

### 16.5 About the older conclusions

16. **Infinity-X 2.9 (sdk 35) "instant revert"** predates the signing-key fix.
    The compatibility table itself flags it as worth retrying. Never retried.
17. **The 54 ms / 2 ms / 34-vs-0 TEE-line observations** are real but were
    interpreted through the now-dead timing theory. **They have never been
    re-interpreted under the root-of-trust hypothesis** — and a fabricated root
    of trust would also produce zero TEE lines, since the TA would reject
    before loading. Worth revisiting.
18. **`vendor_kmsel.img` was verified offline months ago.** Re-verify before
    flashing; the tree has moved on.

### 16.6 Method-level

19. Only **one** device has ever been tested. Every "this device" claim is
    n=1.
20. All GSIs tested come from **one maintainer** (Doze-off) except LineageOS.
    The "three separate lineages" claim controls for ROM base, **not** for
    build pipeline. If Doze-off applies the same spoof patch across all their
    builds, the real variable might be "which of Doze-off's builds got the
    patch", not the lineage. **This weakens §5.1's independence claim and has
    not been acknowledged before now.**

---

## 17. Anti-patterns — rules earned the hard way

1. **String presence ≠ code path.** Cross-reference `adrp`/`add` before
   patching. Cost: two flash cycles.
2. **Verify against the artefact, not the build log.** Read the file back out
   of the image and hash it. Cost: one silent-corruption bug that shipped.
3. **Function starts come from `bl` targets**, not from walking back to `ret`.
4. **The loudest error is rarely the fault.** Find the *first* error. Cost:
   days on PQ spam.
5. **logcat does not survive a reboot.** Capture during install, or use pstore.
6. **Run the cheap observation before the expensive surgery.** Every decisive
   test in this project cost one boot and no patching; every wasted cycle
   involved patching first.
7. **Run the control.** "Does the unmodified thing also fail?" was never asked
   about Axion, through six eliminated hypotheses.
8. **Read the maintainer's release notes.** The init fix was written down by
   the person who shipped the file, the whole time.
9. **Single-variable or it proves nothing.** Confirm the two artefacts differ
   in exactly the way you claim — hash them.
10. **Record negative results.** Half this document's value is knowing what has
    already been eliminated.

---

## 18. Quick start for a new assistant

```sh
cat ~/.claude/projects/-home-neba/memory/MEMORY.md    # loaded automatically
cat ~/START-HERE.md                                   # environment bootstrap
cd ~/device/umidigi/g7tabpro && git pull              # ALWAYS pull first
ls ~/gsi-test/                                        # the artefacts
```

**Always `git pull` before trusting local state** — this project runs across
several machines and this box has been behind more than once.

If asked "what should we do next", the answer is §14, and items 1-4 are all
zero-risk.

If asked "why doesn't X boot", triage by **failure shape** first (§6.1) — it
determines which of the three blockers you are looking at, and getting it wrong
wastes a hardware cycle.

If you are about to patch a binary, read §17 rule 1, then §12.5.

---

*End of handoff. Generated 2026-09-01 on DESKTOP-NEBA (WSL2).*
