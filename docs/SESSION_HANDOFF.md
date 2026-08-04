# UMIDIGI G7 Tab Pro — Session Handoff (v4)
## TWRP complete · A14 GSI investigation exhausted

**Repo:** https://github.com/trinineba-oss/device_umidigi_g7tabpro
**Device:** UMIDIGI G7 Tab Pro — MediaTek Helio G99 (MT6789), Android 13 vendor,
8GB/256GB, 11" 1200x1920 IPS
**Daily driver:** LineageOS 20 TD arm64_bgN (Android 13) — stable, fully working

---

## 0. STATUS SUMMARY

### DONE — TWRP (from-scratch device tree, none existed before)
- Builds, boots, **touch works** (incl. 180° rotation fix, verified on device)
- **microSD mounts** — PC-free ROM flashing achieved (the original project goal)
- **adb stable at the TWRP menu** (fixed via `TW_EXCLUDE_MTP := true`)
- `/data` not decryptable in recovery (accepted trade-off, see §5)

### DONE — GSI daily driver
LineageOS 20 TD arm64_bgN boots and runs perfectly on **stock vbmeta with
verification ENABLED**.

### EXHAUSTED — Android 14 GSI (LineageOS 21)
Eight+ flash cycles. Two walls, both diagnosed to root cause, neither solved.
See §2 and §3. **Recommend: stop, or pivot to a native build (§8).**

---

## 1. THE BIG MISDIRECTIONS (do not repeat)

### `vendor.mediatek.hardware.pq@2.14` — RED HERRING
The original "GSIs won't boot" theory. **Completely disproven.**
- `lshal` on stock shows pq@2.14 **registered and healthy** (PID 1297); every
  version 2.0–2.15 `.so` exists in `/vendor/lib64`.
- The requester is `hwcomposer.mtk_common.so`, which calls `tryGetService` in a
  retry loop with explicit "cannot find PQ service!" handling — it **degrades
  gracefully**, it does not block boot.
- Decisive proof: LOS20 boots fine on the same vendor with the same pq stack.
- To silence it anyway: `ro.vendor.mtk_pq_support=0` in `/vendor/build.prop`.
  Cosmetic only.

### TrickyStore spoofs verified-boot state — COST MULTIPLE SESSIONS
Android props reported `ro.boot.flash.locked=1`,
`ro.boot.vbmeta.device_state=locked`, `ro.boot.verifiedbootstate=green`.
**All spoofed by the TrickyStore Magisk module.** Hardware truth:
```
fastboot getvar unlocked   →  yes     (genuinely UNLOCKED)
```
**RULE: trust only `fastboot getvar` for boot/verity state. Never `getprop`.**

### Other dead ends from this session
- `--disable-verity --disable-verification` on vbmeta → "Try again" screen
- `fastboot erase vbmeta` → bootloops even LOS20 (recovered by reflashing stock
  vbmetas **without** disable flags)
- Permissiver / selinux_permissive zips → won't flash, and conceptually conflict
  with this device's AVB enforcement
- DSU as a compatibility oracle → misleading, see §3
- MTK BPF patcher (`R0rt1z2/mtk-bpf-patcher`) → **not applicable**: it fixes
  *networking* on A14+, not booting, and targets 4.14/4.19 kernels (ours is 5.10)

---

## 2. WALL 1 — bootloader/AVB rejects flashed A14 GSI

### Key discovery: the AVB root key is the PUBLIC AOSP TESTKEY
```
vbmeta.img top-level pubkey sha1 = cdbb77177f731920bbe0a0f94f84d9038ae0617d
                                 = AOSP external/avb/test/data/testkey_rsa2048.pem
```
So the whole AVB chain **can** be rebuilt and re-signed by anyone. Toolkit built
for exactly this: `github.com/alltechdev/haha-you-used-testkeys`
(`check_testkey.sh` reports: "VULNERABLE — Uses AOSP testkey!").

### Stock AVB chain (parsed with avbtool)
```
vbmeta.img  (signed cdbb77… = testkey)
├── CHAIN boot           loc 3   key 9d808b0995768d0677fccb1efcddb7cf9e153d99
├── CHAIN vbmeta_system  loc 2   key fa41159a5d696abdef93176a07d0b0d001263f01
├── CHAIN vbmeta_vendor  loc 4   key 9577bc6c0772975ecce93c4d8a178662c728dadf
├── HASH  dtbo
├── HASH  vendor_boot
├── HASHTREE odm_dlkm
└── HASHTREE vendor_dlkm

vbmeta_system.img → hashtrees for system, product
vbmeta_vendor.img → hashtree for vendor
```
The three chained keys are **private OEM keys** (not testkey, not any AOSP key).
You cannot re-sign a chained partition and have the *stock* top vbmeta accept it
— the top vbmeta must be replaced too (possible, since it is testkey-signed).

### Super partition
```
size 0x240000000 = 9,663,676,416 bytes (9.0 GB)
logical: system_a, vendor_a, product_a, vendor_dlkm_a, odm_dlkm_a (+_b slots)
```

### Final build (technically correct — still bootlooped)
Using `haha-you-used-testkeys` on WSL with four local patches:
1. `repack_super.sh` — add vendor_dlkm/odm_dlkm hashtree footers, their
   descriptors into vbmeta_vendor, and their partition/image entries in lpmake
   (stock toolkit handles only system/vendor/product)
2. `repack_super.sh` — DLKM size formula → `size + 10% + 1MB`
   (stock `*102/100` too tight for the 340KB odm_dlkm; hashtree failed)
3. `rebuild_vbmeta.sh` — add the **`vendor_boot`** descriptor
   (**its absence caused the second-to-last bootloop**)
4. `resign_boot.sh` — add corrected AVB props (see §3)

Result: `verify_chain.sh` → ALL CHECKS PASSED; vbmeta contained all five
descriptors (boot, vbmeta_system, vbmeta_vendor chains + dtbo, vendor_boot hashes).

**Flashed via SP Flash Tool v6 → still bootloops at the UMIDIGI splash.**
No adb, no logcat — fails before userspace.

### SPFT gotcha
Sparse `super.img` **fails at 98%**. Rebuild raw (remove `--sparse` from lpmake)
→ 9.0GB file → flashes fine.

---

## 3. WALL 2 — `/data` read-only on A14 (KEYMINT_NOT_CONFIGURED)

Diagnosed via DSU boot (DSU bypasses AVB, so it reaches userspace).

### Exact failure chain, from logcat
```
vold: Creating new key in /metadata/vold/metadata_encryption/dsu/dsu/key
vold: Generating "key storage" key
keystore2: Error::Km(r#KEYMINT_NOT_CONFIGURED)
vold: keystore2 Keystore generateKey returned service specific error: -64
vold: read_key failed in mountFstab
  → /data mounts READ-ONLY
  → post-fs-data cannot mkdir /data/unencrypted, /data/vendor,
    /data/misc/credstore …
  → credstore SIGABRTs every 5s (chdir /data/misc/credstore: No such file)
  → boot hangs at the animation forever
```
Accompanying denial:
`avc: denied { read } comm="android.hardwar" scontext=u:r:hal_keymint_default:s0 … permissive=0`

### Root cause
Device uses the **Trustkernel TEE** (`/proc/tkcore/tkcore_log`,
`ro.hardware.gatekeeper=trustkernel`, `ro.vendor.mtk_trustkernel_tee_support=1`,
`teed` running, `/data/vendor/t6/` provisioned).

PeterGSI's `MtkTkQuirk` (in `android_device_peter_gsi`) fires on this device and
sets keymaster props read **from the boot partition's AVB footer**. On LOS21 it
produced:
```
ro.keymaster.xxx.release        = 13            ← should be 14
ro.keymaster.xxx.security_patch = 2019-06-06    ← straight from stock boot.img!
```
A14 KeyMint rejects that config → `KEYMINT_NOT_CONFIGURED` → no key generation
→ no encrypted `/data`.

### Attempted fix (built and flashed, but never exercised)
`resign_boot.sh` patched to write corrected AVB props into boot.img:
```
--prop com.android.build.boot.os_version:14
--prop com.android.build.boot.security_patch:2024-10-05
--prop com.android.build.boot.fingerprint:UMIDIGI/G7_Tab_Pro/G7_Tab_Pro:14/…
```
Verified present in `output/boot.img`. **Never ran** — the device never got past
the AVB wall. Plausible but unproven.

Also needed (same PeterGSI commit, not yet applied): SELinux policy granting
`hal_keymaster` / `hal_keymint` / `hal_gatekeeper` read on
`default_prop` / `system_prop`.

### DSU is a BAD test vehicle here
DSU reuses the existing metadata-encrypted `/data` and cannot re-provision
hardware-wrapped keys (`wrappedkey_v0`). Read-only `/data` under DSU is partly
expected and does **not** predict flashed-boot behaviour.

---

## 4. CONFIRMED FACTS

| Item | Value |
|---|---|
| SoC | MT6789 (Helio G99), Mali-G57 MC2 |
| Kernel | 5.10.185-android12-9 — **GKI**, boot header v4 |
| Bootloader | `k6789v1_64`, **genuinely unlocked**, AVB **enforcing** |
| Partitions | A/B, dynamic, super 9.0GB, **no** recovery partition |
| Recovery | boot-as-recovery via `vendor_boot` |
| TEE | **Trustkernel** (teed, `/proc/tkcore`) |
| Encryption | FBE `aes-256-xts…+wrappedkey_v0`; metadata `aes-256-xts:wrappedkey_v0`; keydir `/metadata/vold/metadata_encryption` |
| Panel | `l0a9w006c_dsi_vdo` |
| Touch | Chipone TDDI, `tddi_9551.ko`, i2c0 @0x48 |
| Stock fingerprint | `UMIDIGI/G7_Tab_Pro/G7_Tab_Pro:13/TP1A.220624.014/20241121:user/release-keys` |
| boot.img AVB props | os_version **12**, security_patch **2019-06-06** (!) |

**No GPL kernel source released by UMIDIGI.** Device tree uses prebuilt kernel,
dtb, dtbo and 175 real `.ko` modules extracted from stock firmware.

---

## 5. TWRP — the complete working recipe

1. **`TW_INCLUDE_RESETPROP := true`** + package `libresetprop.so` ← the original
   "won't boot" blocker (dynamic-linker failure; init restarted it every 5s,
   which looked exactly like a hang)
2. **`TARGET_RECOVERY_PIXEL_FORMAT := "RGBX_8888"`** (RGB565 → SIGSEGV on splash)
3. **Strip `fileencryption=` / `keydirectory=`** from the `/data` line in
   `rootdir/etc/fstab.mt6789` (FBE hang). Consequence: `/data` unmountable in TWRP
4. Kernel offsets; `TARGET_2ND_ARCH_VARIANT=armv8-2a`; `TARGET_COPY_OUT_*`;
   `BOARD_*IMAGE_FILE_SYSTEM_TYPE`; empty `first_stage_ramdisk`;
   2 ramdisk fragments; split `modules.load` / `modules.load.recovery`
5. **Touch DT node merged into the base DTB** — the bootloader does **not** apply
   the DTBO in recovery, so `chipone_touch@48` had to go into
   `prebuilt/dtb/g7tabpro.dts` under `/soc/i2c@11e00000`
6. **Pinctrl states** (7 × `ctp_*`) + `pinctrl-names` / `pinctrl-0..6` on the bus
7. **Firmware staged** to `recovery/root/lib/firmware/`
   (`chipone_firmware.bin`, `chipone_firmware_ld.bin`) — the driver uses
   `request_firmware()` and `/vendor` isn't mounted in recovery
8. **`debug_log=1`** via `prebuilt/modules/modules.options` — driver logging is
   compiled OFF by default; enabling it revealed the real failure
9. **THE BINARY PATCH** — `tddi_9551.ko` at offset `0x1c28`:
   `340001e8` (CBZ) → `1400000f` (unconditional B).
   The driver reads `atag,boot` and refuses to register unless bootmode==0; the
   bootloader rewrites **every** `atag,boot` in the FDT to 2 in recovery, so no
   DTS-based fix can ever work.
   Verify: `xxd -s 0x1c28 -l 4 prebuilt/modules/tddi_9551.ko` → `0f00 0014`
10. **Rotation:** `RECOVERY_TOUCHSCREEN_FLIP_X/Y := true` (both = 180°) — verified
11. **`TW_EXCLUDE_MTP := true`** — MTP is ON by default and steals the USB gadget
    at the TWRP menu, killing adb. This fixed adb-at-menu.
12. **microSD mount** — the stock fstab uses `voldmanaged=`, which TWRP ignores.
    Add to `rootdir/etc/fstab.mt6789`:
    ```
    /external_sd  vfat  /dev/block/mmcblk0p1  flags=display="MicroSD";storage;wipeingui;removable
    ```
    microSD is `mmcblk0` because internal storage is UFS (`sda`/`sdb`). Card is FAT32.

### vbmeta rules for this device
- LOS20 / stock boot fine with **stock vbmeta, verification ENABLED**
- **Never** flash disabled or erased vbmeta — bootloop or "Try again"
- Recovery from a bad state: reflash the stock vbmeta set **without**
  `--disable-verity --disable-verification`

---

## 6. DIAGNOSTICS THAT WORK

- **TWRP Terminal** (touch works) — on-device shell when adb is unavailable
- **SD-card log dump** — `dumplogs.sh` + a service in
  `recovery/root/init.recovery.mt6789.rc`; FAT32 card, boot recovery, wait 60s,
  power off, read `twrplogs/`
- **`adb wait-for-device logcat -b all > x.txt`** — start it *before* triggering
  the boot; this is what finally captured the DSU/GSI hangs
- **pstore** (`/sys/fs/pstore/console-ramoops-0`) — **MD5-CHECK IT FIRST.**
  It goes stale on this device and `rm` does not clear the RAM region. A stale
  DSU log was mistaken for a flashed-boot log during this session.
- Windows CMD has no `grep` — use `findstr /I`, or redirect to a file
- Termux has **no `/tmp`** — use `~/`

---

## 7. TOOLING NOTES

### Termux
```
pkg install git python openssl-tool dtc
git clone --depth=1 https://android.googlesource.com/platform/external/avb
```
`avbtool` shells out to `openssl` — without it, `extract_public_key` silently
writes an **empty** file (sha1 `da39a3ee…`). Always sanity-check: the testkey
pubkey sha1 must be `cdbb77177f731920bbe0a0f94f84d9038ae0617d`.

### WSL — haha-you-used-testkeys
```
sudo apt install -y android-sdk-libsparse-utils libfuse2t64 python3
sudo sh -c 'grep -q "^user_allow_other" /etc/fuse.conf || echo user_allow_other >> /etc/fuse.conf'
```
Layout: `firmware/stock/` (scatter + vbmeta* + boot + dtbo + **vendor_boot**),
`output/super_unpacked/` (the five logical partitions), `keys/` (ships the
testkey plus generated boot/vbmeta_system/vbmeta_vendor keys).
`firmware/dtbo.img` (**not** `firmware/stock/`) is where `rebuild_vbmeta.sh` looks.

`verify_chain.sh` passing is **NOT sufficient** — it checks signatures and chain
keys only, not whether every required hash descriptor is present. Always run:
```
avbtool info_image --image output/vbmeta.img | grep -i "partition name"
# must list: boot, vbmeta_system, vbmeta_vendor, dtbo, vendor_boot
```

### SP Flash Tool
- v6 uses Download XML (`download_agent/flash.xml` → `../MT6789_Android_scatter.xml`)
- Copy the whole stock firmware folder, then overwrite only the re-signed images
- **Sparse super fails at 98%** — build raw
- Never flash preloader

---

## 8. OPTIONS FROM HERE

### Option A — stop
TWRP works, PC-free flashing works, LOS20 is a solid daily driver. The original
project goal (flash ROMs without a PC) is **met**.

### Option B — native LineageOS build (the converging path)
The device is **GKI**, so the missing GPL kernel source is *not* a blocker: use
the stock `boot.img` as a prebuilt kernel (KMI-matched by definition) plus the
existing prebuilt vendor modules. A native build generates a correct,
internally-consistent vbmeta **and** device-matched fstab/encryption handling —
addressing **both** walls by construction instead of by re-signing.
Cost: blob extraction (`extract-files.sh`, never run), device sepolicy, a real
Linux build machine, weeks of work.

### Option C — one more GSI diagnostic
Capture `console-ramoops` immediately after an A14 bootloop (MD5-verify it's
fresh). If it names a specific AVB rejection, that's actionable. If it's stale
or vague, the GSI path is finished.

### Small leftovers
- `TW_INCLUDE_EXFAT` not needed (card is FAT32)
- Remove `debug_log=1` and the `dumplogs` service once fully confident
- **Push all of this to the repo** — it currently lives only in chat history

---

## 9. FILE / KEY REFERENCE

```
Stock firmware:  UMIDIGI_G7_Tab_Pro_V1.0_20241121-user/
  MT6789_Android_scatter.txt / .xml
  download_agent/flash.xml          ← SPFT v6 entry point
  boot.img  dtbo.img  vendor_boot.img  super.img
  vbmeta.img  vbmeta_system.img  vbmeta_vendor.img

AVB keys:
  cdbb77177f731920bbe0a0f94f84d9038ae0617d  AOSP testkey (top vbmeta)  ← PUBLIC
  9d808b0995768d0677fccb1efcddb7cf9e153d99  boot chain      (OEM private)
  fa41159a5d696abdef93176a07d0b0d001263f01  vbmeta_system   (OEM private)
  9577bc6c0772975ecce93c4d8a178662c728dadf  vbmeta_vendor   (OEM private)

Toolkit-generated (haha-you-used-testkeys/keys/):
  a9cc8a379101d07cbe9f4ab76f76fcbb2ac286cc  boot.pem
  565840a78763c9a3be92604f5aef14376ee45415  vbmeta_system.pem
  f013c089b7f6e86cabc32f3ab24559f01b327bbf  vbmeta_vendor.pem

GSI tested: lineage-21.0-20260614-UNOFFICIAL-arm64_bgN-signed.img
  already testkey-signed; system hashtree root e5e86c74…; os_version 14
```

### Reference projects
- `alltechdev/haha-you-used-testkeys` — AVB re-signer for testkey devices
- `AndyCGYan/lineage_build_unified` + `lineage_patches_unified` (`lineage-20-td`)
- `PeterGSI/android_device_peter_gsi` — MtkTkQuirk (Trustkernel keymaster props)
- `TrebleDroid/treble_experimentations` #67 — Jelly Star, same MT6789, same A14 wall
- `MT6789-Rock/device_xiaomi_rock`, `twrp-device_tecno_TECNO-LI7` — TWRP references
