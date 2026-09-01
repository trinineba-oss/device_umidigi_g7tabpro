# UMIDIGI G7 Tab Pro — full project dossier

**Generated 2026-09-01.** A single-file technical record of everything
established on this device: what is solved, what is not, what was tried and
failed, and the exact evidence behind each claim. Written so a reader with no
prior context can audit the reasoning and find errors in it.

Companion docs (deeper on one topic each):
[KEYMINT_OS_VERSION_FIX.md](KEYMINT_OS_VERSION_FIX.md),
[INIT_SWAP_FIX.md](INIT_SWAP_FIX.md),
[VENDOR_SELINUX_PROP_FIX.md](VENDOR_SELINUX_PROP_FIX.md),
[SESSION_HANDOFF.md](SESSION_HANDOFF.md).

---

## 0. How to read this document

Claims are tagged so confidence is never ambiguous:

| tag | meaning |
|---|---|
| **[HW]** | confirmed on the physical device |
| **[PC]** | verified offline against the artefact (hash, disassembly, `e2fsck`, `avbtool`) |
| **[INF]** | inference or correlation — plausible, **not** measured |
| **[DEAD]** | tested and falsified |

The single most expensive mistake in this project was treating **[INF]** as
**[HW]**. Two binary patches were flashed on the strength of string presence
alone; both cost a full flash-and-boot cycle and proved nothing. See §4.6.

---

## 1. Device identity

| | |
|---|---|
| Model | UMIDIGI G7 Tab Pro (rebranded Alldocube iPlay 50 Mini Pro) |
| SoC | MediaTek MT6789 (Helio G99) |
| Stock | Android 13, `TP1A.220624.014`, build `20241121` |
| Vendor API level | 31 (Android 12) |
| TEE | TrustKernel |
| KeyMint HAL | **V1** (AIDL v1) |
| Vendor fingerprint | `UMIDIGI/G7_Tab_Pro/G7_Tab_Pro:13/TP1A.220624.014/20241121:user/release-keys` |
| Bootloader | unlocked; does **not** publish `ro.boot.vbmeta.*` |
| Recovery | TWRP, built in GitHub Actions, touch working |
| No UART | serial console is not available — every boot test is blind |

The last row shapes everything. With no serial console, a failed boot yields no
output at all unless adb comes up. Diagnosis is therefore mostly offline
analysis plus carefully designed single-variable hardware tests.

---

## 2. Current state at a glance

| goal | state |
|---|---|
| Boot Android 14/15/16 GSIs via DSU | **Solved [HW]** — two independent fixes, both automated in the app |
| On-device patcher app (no root, no PC) | **Shipped** — v5.1, `gsi-patcher-v5.1` |
| Make *unmodified* GSIs boot (vendor-side fix) | Built + verified offline **[PC]**; **never flashed** |
| TWRP `/data` decryption | **Broken** — untried fix available, zero flash risk |
| Native LineageOS build | Reached 99% (`mka bacon`), full image set, **never flash-tested** |
| Axion GSI | **Unsolved** — instant DSU revert, 6 hypotheses eliminated |

**Daily driver today:** LineageOS 22.x (sdk 35), patched, in active use **[HW]**.

---

## 3. Blocker 1 — the KeyMint OS-version rejection (SOLVED)

### 3.1 Symptom

Every Android 14+ GSI hangs on the boot splash. Stock boots fine. With adb at
the splash, `/data` is not mounted and only `class core` services are running:

```
init.svc.zygote    = (empty)     # class main never started
init.svc.pq-2-2    = (empty)     # class main never started
init.svc.vold      = running     # class core did start
init.svc.keystore2 = running
```

logcat is dominated by a retry storm — **147,067 occurrences in one boot**:

```
E hwcomposer: [IPqDevice] Can't get PQ service tried (0) times
E init: Control message: Could not find
        'vendor.mediatek.hardware.pq@2.14::IPictureQuality/default'
```

**That PQ spam is a red herring** — three layers downstream of the fault. Days
were lost to it before the real chain was found.

### 3.2 Root cause [HW]

```
keystore2 generateKey
  -> vendor KeyMint HAL (TrustKernel) passes OS version + patch level to the TA
  -> TA was provisioned under Android 13; it REJECTS an Android 14 system
  -> every generateKey returns -64 KEYMINT_NOT_CONFIGURED
  -> vold cannot create the FBE key
  -> installkey /data fails, /data never mounts
  -> class_start main never runs (it needs /data)
  -> pq-2-2 is class main, so the PQ HAL never starts
  -> vendor hwcomposer blocks forever waiting for PQ
  -> boot hangs at the splash
```

Real capture:

```
E keymint : TrustKernelKeyMintImplementation.cpp:672: TEE return -64
E keystore2: Error::Km(r#KEYMINT_NOT_CONFIGURED)
E vold    : keystore2 Keystore generateKey returned service specific error: -64
E vold    : read_key failed in mountFstab
```

### 3.3 The fix

Rewrite the version in **every** prop file that declares one, then rebuild the
AVB hashtree footer (editing the image invalidates dm-verity):

```
ro.build.version.release             16 -> 13
ro.build.version.release_or_codename 16 -> 13
ro.build.version.security_patch      2026-xx-xx -> 2025-09-05
```

`ro.build.version.sdk` is **deliberately left alone** (36). The framework keeps
behaving as its real API level; only what the HAL reports to the TEE changes.

### 3.4 The subtlety that broke single-file patching

`ro.` properties are **write-once**: whichever file init reads first wins. An
Android 16 GSI carries a generic `ro.build.version.release` in
`/system/product/etc/build.prop` **as well as** `/system/build.prop`. Patching
only `/system` leaves the runtime reporting the unpatched value. Android 14/15
GSIs lack the duplicate, which is why single-file patching appeared to work for
so long.

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

Verified landing correctly on Axion 2.8 **[PC]** — all three present files:

```
ro.system.build.version.release=13          # /system/build.prop
ro.system_ext.build.version.release=13      # /system/system_ext/etc/build.prop
ro.product.build.version.release=13         # /product/etc/build.prop
ro.build.version.release=13
ro.build.version.release_or_codename=13
ro.build.version.security_patch=2025-09-05
ro.build.version.sdk=36                     # untouched, by design
```

### 3.5 The right patch target

Prefer what the TEE was actually told, not what the running system reports:

```
ro.keymaster.*.release     <- authoritative
Build.VERSION.RELEASE      <- fallback only
```

The app reads the former at runtime (`DeviceProbe.kt`) rather than assuming 13.

---

## 4. Blocker 2 — `/system/bin/init` (SOLVED as a fix; cause UNKNOWN)

### 4.1 The finding [HW]

Some GSIs hang at their own splash **even with the version patch provably
applied**. Replacing `/system/bin/init` with one from a GSI that boots — and
changing **nothing else** — makes them boot.

Confirmed across **four ROMs from three lineages**, each tested **both ways**:

| ROM | version patch alone | + donor init |
|---|---|---|
| Infinity-X 3.12 | does not boot | **boots** |
| crDroid 10 | does not boot | **boots** |
| crDroid 11 | does not boot | **boots** |
| Lunaris-AOSP 3.12 | does not boot | **boots** |

Three separate lineages rules out a maintainer-specific quirk. The crDroid 11
case was done entirely in the app, on the tablet, on a fresh non-booting image.

### 4.2 How it was found

Doze-off's release notes carry this disclaimer verbatim across Infinity-X,
Lunaris, Project CiRCLE and Axion:

> *for users if not boot in DSU user note #edit_gsi and put this init file in
> system/bin. The reason for this is that some of my patches broke the DSU
> function on some old devices...*

`/system/bin/init` had never been compared. The static diffing to that point
covered `keystore2`, `libkm_compat*`, `vold`, SELinux policy, `keystore2.rc`
and `hw/init.rc` — all identical or irrelevant. init was the gap.

Scale of the difference (Circle vs Infinity-X):

| | Circle | Infinity-X |
|---|---|---|
| size | 2,708,416 | 2,724,744 (+16,328) |
| bytes differing | — | 2,055,573 of 2,724,744 |

For contrast `vold` differs by **16 bytes**. This is a different build of init,
not a small patch.

### 4.3 Donor inventory (verified hashes) [PC]

```
7ac91703826d68e04bf98a0bbab5deff12a5bda6aa45cc62b1ea41f3d86de990  circle_init    2708416
a633589ff1c2d1474b9b224ec8a3837d0b1fa63350f346ccc86d913d2085f43e  avium_init     2724880
f52af0f7cda88bf79bc4b334875259ae5b4f70bac2b0fb59096b5be044fbe60a  dozeoff_init   2725064
8ea305a22e59e5d44bce1ddc272024190717c2c5430967b0d016909ab040b77e  inf_init       2724744
c18a36392089a3aed09d7c861a5b312f9768ee96435a65a9a18399581e49f095  lunaris_init   2724864
b6fd7e143311e76bc789620f3e2e8f3aebc454730ad276205bcc4cb7b88dacdf  axion_init     2758672
```

`dozeoff_init` is byte-identical to the standalone `init` asset published on
the CiRCLE, Infinity-X and Axion releases — an **independent** known-good
sample, not one of the ROM inits this investigation started from.

**Recommended donor: `circle_init`.** Equally effective, and the leanest — no
first-stage shell hook, no `/data/local.prop` reading, no permissive-boot path.
Doze-off's init is a debug/rescue build (`/first_stage.sh`,
`androidboot.first_stage_console`, `Permissive SELinux boot...`); those features
are **incidental**, not the mechanism — Circle and AviumUI have none of them and
boot perfectly.

### 4.4 The correlation [PC]

Verified-boot string counts, recounted 2026-09-01:

| init | boots? | `GetVbmeta*` | `ro.secureboot.*` | `ro.is_ever_orange` | `ro.boot.vbmeta.` prefix |
|---|---|---|---|---|---|
| Project CiRCLE | **yes** | 0 | 0 | 0 | 0 |
| AviumUI | **yes** | 0 | 0 | 0 | 0 |
| Doze-off fix init | **yes** | 0 | 0 | 0 | 0 |
| Infinity-X 3.12 | no | 10 | 2 | 1 | 1 |
| Lunaris-AOSP | no | 10 | 2 | 1 | 1 |
| **Axion 2.8** | **no (different failure)** | **9** | **0** | **0** | **1** |

Every init that boots carries **none** of the verified-boot synthesis; both
splash-hang failures carry **all** of it. No exceptions in either direction.

**Axion is the anomaly** and this is newly recorded: a *partial* profile —
`GetVbmeta*` and the prefix present, but neither `ro.secureboot.*` nor
`ro.is_ever_orange`. Since Axion's failure mode is also different (§5), it does
not belong in the same family, and it should **not** be used to argue either
side of the correlation.

The strings unique to failing inits:

```
GetVbmetaSize: Attempting to open
/dev/block/by-name/vbmeta
GetVbmetaSize: ioctl(BLKGETSIZE64) failed for
GetVbmetaSize: lseek failed for
GetVbmetaSize: ro.boot.slot_suffix is empty
GetVbmetaDigest: Failed to set property 'ro.boot.vbmeta.digest' ...
ro.boot.vbmeta.            (prefix — used to build names dynamically)
ro.is_ever_orange
ro.secureboot.devicelock
ro.secureboot.lockstate
oplusboot.verifiedbootstate
```

`oplusboot.*` is an Oplus (OPPO/OnePlus/Realme) bootloader property — almost
certainly an Oplus-device fix baked into a shared GSI init base that now runs
on every device using it.

### 4.5 Disassembly — the code is live [PC]

A string proves code is *compiled in*, not that it *runs*. llvm-objdump on
Infinity's init (ARM64) settles it:

- `GetVbmetaSize` strings are referenced by real instructions at `0xfdbc4` and
  `0xfe0fc` — `adrp`/`add` pairs materialising their addresses. **Not dead.**
- They sit in the function entered at `0xfa4ec`, reached via a 5-deep chain:
  `0xfa4ec <- 0x100414 <- 0xd9fa8 <- 0x98034 <- 0x95dc0 <- 0x75d4c`. The three
  innermost links have exactly **one** call site each.
- That function's other string references identify it: `(Loading properties
  from`, `' in property file '`, `while loading .prop files`, `/build.prop`,
  `/default.prop`, `.build.version.sdk` — init's **property-loading** routine.

So the probe runs early in second stage, on every boot, before services start.

> **Method note:** an earlier attempt to find function starts by "walking back
> to the previous `ret`" landed mid-function and was wrong. The correct method
> is to use actual `bl` targets as function entries.

### 4.6 Hypotheses — one dead, one untested

**Timing theory: [DEAD].** The proposal was that extra early-boot work loses the
servicemanager registration race (the HAL registers ~54 ms in on a good boot;
keystore2 gives up and installs the emulation wrapper **2 ms** in on a bad one).

Infinity's init was rebuilt with the vbmeta device path repointed at a
non-existent node — 6 bytes changed, everything else byte-identical — so
`open()` fails instantly and `ioctl`/`lseek` never run. **It still hung [HW].**

The theory also never explained determinism: failures are **2/2 and 3/3 on
repeat attempts**. A race should be flaky.

**Root-of-trust theory: [DEAD] as stated.** The failing inits do synthesise
`ro.boot.vbmeta.*` — disassembly later found the full hardcoded spoof table
(see INIT_SWAP_FIX.md). But the premise that the vendor KeyMint HAL reads those
properties was **checked on 2026-09-01 and is false**: `libkeymint.so` contains
exactly three property names, all version-related, and zero root-of-trust
symbols. It cannot be consuming the spoofed values.

The spoof table is real and remains the cleanest structural difference between
inits that boot here and inits that do not. Why it breaks the boot is open.
The precise question is **"why does the vendor KeyMint HAL fail to register
with servicemanager when this init is used"** — because in this failure mode
the `-64` comes from keystore2's emulated fallback, not from the TA at all.

**Both attempts to test it failed because the patch did not do what I claimed:**

| # | patch | why it proved nothing |
|---|---|---|
| 1 | repoint vbmeta device path (6 bytes) | on open failure the code **still sets the property** from a fallback: `Property 'ro.boot.vbmeta.digest' set successfully to dynamic fallback value` — it changed *which* value, not *whether* |
| 2 | rename prefix `ro.boot.vbmeta.` -> `ro.zzzz.vbmeta.` (7 bytes) | disassembly showed the function references the **full** names (`.device_state`, `.digest`, `.size`), never the prefix — the three properties feeding the HAL were still set |

> **The lesson, stated once so it is not repeated a third time:** confirm which
> string the *code references* before patching it. Presence in the binary says
> nothing about which copy a call site uses, and a miss costs a full
> flash-and-boot cycle.

### 4.7 The cheap test that has not been run

The hypothesis predicts something **directly observable with no binary
surgery**. On the hung boot (Infinity ships `/adb_keys`, so adb authorises
without `/data`):

```sh
adb shell getprop | grep -E "vbmeta|secureboot|is_ever_orange|verifiedboot"
```

- Synthesised values on the hang, absent/bootloader-supplied on a working boot
  (Project CiRCLE) -> hypothesis **confirmed**.
- Same on both -> **dead**, and the difference is elsewhere in init.

`inf_init_norot2` (19 bytes, full names redirected) is built and published for
afterwards — but **the observation should come first**.

---

## 5. Blocker 3 — Axion instant DSU revert (UNSOLVED)

Distinct failure class. The image is rejected **at or before load**, so init
never runs. Affects 2.7 and 2.8.

### 5.1 Failure-shape triage

| shape | meaning | fix |
|---|---|---|
| GSI-splash hang | boot reached userspace | version patch + donor init |
| **Instant revert** | rejected before init | **unknown — this section** |
| OEM-splash hang | never reached userspace | out of scope |

### 5.2 Everything eliminated, with evidence

| # | hypothesis | verdict | evidence |
|---|---|---|---|
| 1 | image corrupted by in-place patch | **[PC] no** | `e2fsck -fn` clean: `8966/9280 files (3.5% non-contiguous), 913596/921809 blocks` |
| 2 | dm-verity / AVB invalidated | **[PC] no** | `avbtool verify_image` passes footer **and** full sha256 hashtree |
| 3 | version patch failed to apply | **[PC] no** | all three `build.prop` read `release=13` (§3.4) |
| 4 | image too large for DSU | **[PC] no** | Axion 3.84 GB is the **smallest** — Infinity 4.36 GB and Lunaris 4.71 GB both boot |
| 5 | init is at fault | **[HW] no** | 2.8 still reverts with Circle's init swapped in; and revert precedes init |
| 6 | AVB `os_version` property descriptor | **[HW] no** | see below |

### 5.3 The `os_version` experiment (2026-08-31) [HW] — FAILED

The Kotlin patcher preserves the original vbmeta **verbatim** (by design — the
edit is length-preserving), so Axion retained an upstream property descriptor
absent from every booting image:

```
Footer version:           1.0
Image size:               3836354560 bytes
Original image size:      3775729664 bytes
Release String:           'avbtool 1.3.0'
    Prop: com.android.build.system.os_version -> '16'      <-- suspect
    Prop: com.android.build.system.fingerprint -> 'google/axion_gsi/...:16/...'
    Prop: com.android.build.system.security_patch -> '2026-07-01'
```

Booting Infinity, for comparison, has **no property descriptors at all**.

The vbmeta was rebuilt with `os_version:13` (and the stale patch date aligned),
everything else held constant:

```sh
avbtool erase_footer --image axion28-avb13.img
truncate -s 3775729664 axion28-avb13.img
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

**Single variable confirmed:** the root digest came back **byte-identical**
(`b2aa2d0faf042b00bdef0abe7b22bf679779cdcd4cd979c262cdb909c560b134`), proving
only the vbmeta changed. Result: **instant revert again [HW].** Hypothesis dead.

Artefact: `~/gsi-test/axion28-avb13.img.gz`, sha256
`f3de0e3592d877582e6be71612371c00eac3a88f28d75b161f272566a6c59fae`.

### 5.4 Why the logcat captured so far is uninformative

The capture taken after a revert shows only the **host** booting:

```
I update_engine: [INFO:cleanup_previous_update_action.cc(192)] Boot completed, waiting on markBootSuccessful()
I update_engine: [INFO:snapshot.cpp(1148)] CheckMergeState for snapshots returned: None
I update_engine: [INFO:cleanup_previous_update_action.cc(328)] Can't find any snapshot to merge.
I update_engine: [INFO:action_processor.cc(116)] ActionProcessor: finished last action CleanupPreviousUpdateAction with code ErrorCode::kSuccess
```

Not one `gsid` or `DynamicSystemInstallationService` line. **logcat does not
survive a reboot** — by the time you are back in the host, the failed boot's log
is gone. A post-revert capture can only ever show the aftermath.

### 5.5 The control that has not been run — highest priority

**Does stock, unmodified Axion 2.8 instant-revert too?**

Six hypotheses eliminated and nothing found should shift the suspicion: perhaps
our patching was never implicated.

- **Stock also reverts** -> Axion is simply DSU-incompatible on this device.
  Nothing in the patcher can fix it; stop spending cycles. **Most likely [INF].**
- **Stock boots (even to a hang)** -> our pipeline breaks it. A real bug that
  would affect every future image, not just Axion.

Costs one DSU cycle. Should have been run first.

---

## 6. Goal 2 — the vendor-side fix (built, never flashed)

Make *any* unmodified GSI boot with no per-image patching, by redirecting the
properties the KeyMint HAL reports to the TA.

`/vendor/lib64/libkeymint.so` binary-patched — both replacements shorter, so
written in place and NUL-padded, no relocation:

| offset | original (len) | replacement (len) |
|---|---|---|
| 34768 | `ro.build.version.release` (24) | `ro.vendor.kmosver` (17) |
| 34793 | `ro.build.version.security_patch` (31) | `ro.vendor.kmospatch` (19) |

`/vendor/build.prop` lines 643-644:

```
ro.vendor.kmosver=13
ro.vendor.kmospatch=2025-09-05
```

### 6.1 Why the first attempt bootlooped

**The properties were defined but never labeled.** A property with no
`*_property_contexts` entry falls back to `default_prop`. Since Oreo the
property area is one file per SELinux context under `/dev/__properties__/`, and
the reading domain needs `read`/`map` on it. The HAL's domain is
`hal_keymint_default`, which is denied `default_prop`. Fixed by adding
`vendor_property_contexts` lines 811-812.

### 6.2 Verification status [PC]

- Base confirmed **20241121**, matching the running vendor. (An earlier build
  used base 20250423 — wrong — and was caught before flashing.)
- All three components present in `vendor_kmsel.img`.
- AVB re-verified: stock salt `15aa12b7...`, stock partition size 1006141440,
  root digest `b9faed28...`, `avbtool verify_image` passes.

**Risk:** flashing vendor risks `/data`. See §7 for the cheaper alternative.

---

## 7. TWRP — `/data` decryption broken

Crypto was disabled to get TWRP building. `BoardConfig.mk` currently has the
flags **re-enabled but uncommitted**:

```diff
-# TW_INCLUDE_CRYPTO := true
-# TW_INCLUDE_FBE := true
-# TW_INCLUDE_FBE_METADATA_DECRYPT := true
+TW_INCLUDE_CRYPTO := true
+TW_INCLUDE_FBE := true
+TW_INCLUDE_FBE_METADATA_DECRYPT := true
```

### The untried fix — recommended next action

```make
RELEASE_PLATFORM_VERSION := 13
```

Rationale: the same version mismatch that breaks GSI KeyMint plausibly breaks
TWRP's FBE metadata decrypt, since TWRP must talk to the same TA. **Zero flash
risk** — it is a build-time change, the crypto flags are already staged, and
TWRP builds run in CI. If it works, `/data` decryption comes without touching
vendor at all.

Real TWRP builds run in **GitHub Actions**, not locally; `m nothing` locally is
the fast sanity check.

---

## 8. The patcher tool

`tools/gsi-patcher` — 3,572 lines of Kotlin across `core` / `cli` / `app`.
Shipped as **v5.1** (`versionCode 9`), debug-signed, minSdk 26.

| module | file | lines | role |
|---|---|---|---|
| core | `Ext4.kt` | 350 | read/write ext4 without mounting |
| core | `Ext4Alloc.kt` | 240 | real block allocation on-device |
| core | `Avb.kt` | 290 | vbmeta parse / re-sign |
| core | `HashTree.kt` | 95 | dm-verity hashtree rebuild |
| core | `GsiPatcher.kt` | 269 | orchestration |
| core | `Preflight.kt` | 275 | pre-write checks |
| core | `Compatibility.kt` | 222 | tested-image verdicts |
| core | `Payload.kt` / `Protobuf.kt` / `Ingest.kt` | 404 | OTA zip / `payload.bin` / 7z |
| core | `InitSwap.kt` | 151 | donor init validation + write |
| app | `MainActivity.kt` | 577 | UI |

### 8.1 Why it is far smaller than the shell script

~60% of `patch-gsi-keymint.sh` exists to loop-mount so it can run `sed` —
forcing `truncate +2G`, `e2fsck -E unshare_blocks`, `resize2fs`, then a shrink.
That needs root and permanently inflates the image.

None of it is necessary, because **every substitution is length-preserving**:

| property | before | after | length |
|---|---|---|---|
| `ro.build.version.release` | `15`/`16` | `13` | 2 -> 2 |
| `ro.build.version.release_or_codename` | `15`/`16` | `13` | 2 -> 2 |
| `ro.build.version.security_patch` | `2025-12-01` | `2025-09-05` | 10 -> 10 |

Bytes are patched where they lie: same length, same inode, same block
allocation, no `metadata_csum` recomputation. Consequently `image_size`,
`tree_offset`, `tree_size` and the vbmeta blob length are all unchanged — the
hashtree is recomputed in place, the new root digest poked into the existing
descriptor, and the whole thing re-signed. **Every other descriptor (chain
partitions, properties, kernel cmdline) is preserved verbatim.**

> That preservation is exactly why Axion kept its `os_version=16` prop (§5.3).
> Correct behaviour, but worth knowing it is a design choice with consequences.

### 8.2 The silent-corruption bug (pre-existing, fixed)

`writeFileInPlace` **discarded data written into sparse holes** — 7,514
non-zero bytes lost on a real init swap, the file reading back with zeros.
Caught by comparing the extracted init against the donor. Guard added:

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

Plus an unconditional read-back assertion after every init write:

```kotlin
val readBack = fs.readFile(ino)
require(readBack.size == padded.size && readBack.contentEquals(padded)) {
    "init did not read back as written -- refusing to continue; the image is not safe to use"
}
```

That assertion immediately caught a **second** bug — my own allocator write-loop
passed `chunk` with offset 0, so every block got block 0's bytes.

### 8.3 Block allocation (`Ext4Alloc.kt`)

Needed because donor inits differ in size. It **relocates** rather than filling
holes — keeping a single inline extent and avoiding writes into deduplicated
blocks. Old blocks are **abandoned, not freed** (dedup safety).

> **Known consequence:** `e2fsck` reports a block-bitmap difference on
> relocated images. Expected, not corruption.

GSIs ship with `shared_blocks` (`s_feature_ro_compat` bit `0x4000`) — confirmed
set on Circle, Infinity and Axion **[PC]**. Since a length-preserving edit never
needs to allocate, the patcher never unshares, and images stay their original
size.

### 8.4 Donor input handling

`resolveDonor()` accepts either a bare `init` binary **or** a whole raw GSI,
deciding by ELF magic, and validates in the background at pick time. Donors
smaller than the destination are zero-padded — safe for ELF, and it preserves
the length-preserving invariant.

---

## 9. Native LineageOS build

Reached 99% via `mka bacon` with a full image set produced. **Never
flash-tested.** Tree at `/opt/lineage-td` is currently **staged but unsynced**
(22 MB — manifests only); run `~/sync-lineage-td.sh`. Needs the GCP VM
(`lineage-builder`, us-west1-b, currently **stopped**) for the 96 GB AOSP tree.

---

## 10. Artefact inventory

```
1934463099  axion28-avb13.img.gz            f3de0e35...  os_version=13 test — FAILED [HW]
1847253659  axion28-osver13.img.gz                       version patch only — reverts [HW]
2018735710  inf312-initswap-osver13.img.gz               BOOTS [HW]
2442415940  lunaris310-osver13.img.gz                    BOOTS [HW]
1273862876  circle.img.xz                                donor source, boots unpatched-ish
1403772476  inf312.img.xz                                pristine Infinity
```

Init binaries and hashes: §4.3. All under `~/gsi-test/`.

> **Provenance note [PC]:** `inf312-initswap` and `lunaris310` were built by the
> **shell script** (`Release String: avbtool 1.2.0`, `shared_blocks` cleared by
> `unshare_blocks`), while `axion28-osver13` came from the **Kotlin patcher**
> (`avbtool 1.3.0` preserved, `shared_blocks` still set). This is a difference
> in tooling provenance, **not** proof about Axion — crDroid 11 was patched by
> the app alone and boots.

---

## 11. Open errors and candidate fixes

Ordered by value per unit of risk.

| # | issue | state | next action | risk |
|---|---|---|---|---|
| 1 | TWRP `/data` not decryptable | flags staged, uncommitted | `RELEASE_PLATFORM_VERSION := 13`, build in CI | **none** |
| 2 | Axion instant revert | 6 hypotheses dead | run the **stock unpatched** control (§5.5) | none |
| 3 | init mechanism unknown | timing dead, root-of-trust untested | `getprop` on a hung boot (§4.7) before any patch | none |
| 4 | `vendor_kmsel.img` never flashed | verified offline | flash **after** trying #1 | **risks /data** |
| 5 | Native LOS build unverified | 99%, images exist | `repo sync` `/opt/lineage-td`, then flash-test | high |
| 6 | `BoardConfig.mk` uncommitted | parallel session's work | decide whether to commit | none |
| 7 | Axion init anomaly | newly recorded (§4.4) | do **not** use it to argue the correlation | — |

### Errors to guard against — the pattern behind past failures

1. **String presence ≠ code path.** Cost two flash cycles (§4.6).
2. **Verify against the artefact, not the build log.** Read the file back out of
   the image and hash it.
3. **Function starts come from `bl` targets**, not from walking back to `ret`.
4. **Downstream noise dominates logs.** 147k PQ lines were three layers from the
   fault. Find the *first* error, not the loudest.
5. **Premature conclusions, self-corrected:** Axion was assumed to ship EROFS
   (it does not); "UNOFFICIAL" in a title was assumed to predict
   `ro.build.tags` (Axion 2.8 is `release-keys`).
6. **logcat does not survive a reboot.** Capture during install, or use pstore.

---

## 12. Diagnostic recipes

**DSU install phase** (no root, no reboot — the most informative capture):

```sh
adb logcat -b all -c && adb logcat -b all | grep -iE 'gsid|DynamicSystem|dm-verity|device-mapper|avb'
```

**The failed boot itself** (needs root; wiped by cold power-off, survives a
normal reboot):

```sh
adb shell su -c 'cat /sys/fs/pstore/console-ramoops' | grep -iE 'dsu|gsid|verity|dm-|init'
```

**Did DSU even try?**

```sh
adb shell getprop | grep -iE 'dynamic_system|dsu|gsid'
```

If `ro.boot.dynamic_system` is absent or `0`, first-stage init never attempted
the DSU mount — pointing at the host/bootloader side, meaning **no amount of
image patching helps**.

**Root-of-trust observation** (§4.7):

```sh
adb shell getprop | grep -E "vbmeta|secureboot|is_ever_orange|verifiedboot"
```

**Offline image checks:**

```sh
e2fsck -fn image.img                                  # integrity
debugfs -R "cat /system/build.prop" image.img         # props without mounting or root
python3 external/avb/avbtool.py info_image  --image image.img
python3 external/avb/avbtool.py verify_image --image system.img   # MUST be named system.img
```

> `verify_image` needs the file **named after the partition** or it throws a
> misleading error. Symlink rather than copy.

---

## 13. Environment

| | |
|---|---|
| Work hub | WSL2 on DESKTOP-NEBA, `192.168.0.4`, mirrored networking |
| AOSP/TWRP tree | `/home/neba` **is** the tree root |
| Device repo | `~/device/umidigi/g7tabpro` -> `trinineba-oss/device_umidigi_g7tabpro` |
| LineageOS 21 | `/opt/lineage-td` (staged, unsynced) |
| GSI working files | `~/gsi-test/` |
| Toolchain | JDK 17, Gradle `~/opt/gradle-8.7`, SDK `~/android-sdk`, `~/local-bin/fec` |
| GCP VM | `lineage-builder`, us-west1-b — **stopped**; only for the 96 GB AOSP tree |
| Remote access | Tailscale installed (adapter visible in WSL as `eth4`, MTU 1280; **not yet signed in**) |

### Traps that have each cost real time

- **`/home/neba` is itself a repo tree root.** `repo init` from a subdirectory
  searches upward and **hijacks the existing tree** — it once re-pointed the
  TWRP tree to lineage-21.0. That is why the lineage tree lives at `/opt`.
- **Soong does not follow directory symlinks.** The device repo must be a real
  directory in `device/`, with the symlink pointing outward.
- **Passwordless sudo is narrow:** `apt-get`, `apt`, `mount`, `umount`,
  `losetup` only.
- **Mounted GSI files are root-owned** — use `debugfs` to read them instead of
  fighting permissions.
- **No UART.** Every boot test is blind; design them single-variable.

