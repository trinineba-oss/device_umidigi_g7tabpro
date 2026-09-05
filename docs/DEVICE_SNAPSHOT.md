# Live device snapshot — 2026-09-05

Captured over wireless adb from the running daily driver, with root. Recorded
because most of it is unobtainable without a rooted, booted device, and several
entries settle questions this project had been reasoning about from the
outside.

**System at capture:** LineageOS 22.2 (`22.2-20260105-GAPPS-EXT4-GSI`), sdk 35,
flashed to `system_a`, Magisk 30.7.

---

## 1. Verified boot: the bootloader DOES publish state

This corrects a premise stated repeatedly in INIT_SWAP_FIX.md.

**What the bootloader actually passes** — via `/proc/bootconfig`, not
`/proc/cmdline` (which carries none of it, and is why earlier checks came up
empty):

```
androidboot.vbmeta.device       = "PARTUUID=00000006-39c2-4488-9bb0-00cb43c9ccd4"
androidboot.vbmeta.avb_version  = "1.2"
androidboot.vbmeta.device_state = "unlocked"
androidboot.verifiedbootstate   = "orange"
```

**What the running system reports:**

```
ro.boot.vbmeta.device_state = locked      <-- overwritten
ro.boot.verifiedbootstate   = green       <-- overwritten
ro.boot.flash.locked        = 1
ro.boot.vbmeta.digest       = 03338cac85b2ba2e7a048f462f304815c04fba05df793447f52f39fa34592be4

ro.keymaster.xxx.vbmeta_state      = unlocked   <-- honest
ro.keymaster.xxx.verifiedbootstate = orange     <-- honest
ro.keymaster.xxx.release           = 13
```

Two contradictory sets. The `ro.boot.*` pair has been rewritten in userspace;
the `ro.keymaster.xxx.*` pair retains what the TEE actually knows.

**The agent is Magisk**, not the bootloader — `/data/adb/modules` contains
`playintegrityfix`, `tricky_store` and `rezygisk`, which exist precisely to
report a device as locked and verified.

### Why this revises the init finding

INIT_SWAP_FIX.md says the failing init "fabricates properties the device never
had" because "this bootloader publishes none of them." **The second half is
false.** The bootloader publishes them, honestly, as `unlocked` / `orange`.

The corrected mechanism is stronger, not weaker:

```
bootloader (bootconfig)  -> unlocked / orange          [truth, from the TEE's own view]
failing init, EARLY      -> overwrites with locked/green BEFORE KeyMint reads
KeyMint service          -> reads locked/green, passes to the TA
TA                       -> its own view says unlocked/orange -> CONTRADICTION -> reject
```

So it is not a vacuum being filled with invented values; it is a **direct
contradiction** between what the TA independently knows and what it is told.

**And this device is the control that proves timing is decisive.** Magisk sets
exactly the same values — `locked` / `green` — on this very system, and it
boots perfectly, because it writes them at `post-fs-data`, long after KeyMint
has already read the honest ones and configured successfully.

Same values. Same properties. Opposite outcome, decided purely by *when*.

That is the "ordering, not content" hypothesis raised in the external review
(section 2.1 of the 2026-09-01 audit), and it is now supported by a live
system rather than inference.

---

## 2. TrustKernel TEE state

```
vendor.trustkernel.ready              = true
vendor.trustkernel.fs.state           = ready
vendor.trustkernel.fs.mode            = 3
vendor.trustkernel.keybox.deployed    = false
vendor.trustkernel.widevine_keybox.deployed = false
vendor.trustkernel.rkp.uploaded       = false
ro.vendor.trustkernel.keystore        = persist
ro.hardware.gatekeeper                = trustkernel
```

`fs.mode = 3` is the FBE branch of `/vendor/etc/init/trustkernel.rc`:

```
on property:ro.crypto.type=file && property:ro.crypto.state=encrypted
    setprop vendor.trustkernel.fs.mode 3
```

So that trigger chain does fire in normal operation, confirming the reading of
`trustkernel.rc` made offline — even though patching `ro.crypto.state` turned
out not to be the boot blocker.

`widevine_keybox.deployed = false` is live confirmation of the Widevine L3
finding: no keybox, and no `liboemcrypto.so` anywhere in vendor. L1 is not
reachable. `keybox.deployed = false` and `rkp.uploaded = false` likewise
explain why hardware-backed attestation cannot pass STRONG integrity.

---

## 3. KeyMint HAL version — resolved

`DeviceProbe.readKeymintVersion()` returns null on an unrooted app because
`/vendor/etc/vintf` is `vendor_configs_file`. With root:

`/vendor/etc/vintf/manifest/android.hardware.security.keymint-service.trustkernel.xml`

```xml
<manifest version="1.0" type="device">
    <hal format="aidl">
        <name>android.hardware.security.keymint</name>
        <fqname>IKeyMintDevice/default</fqname>
    </hal>
    <hal format="aidl">
        <name>android.hardware.security.keymint</name>
        <fqname>IRemotelyProvisionedComponent/default</fqname>
    </hal>
</manifest>
```

No `<version>` element, and an AIDL HAL without one is **version 1**. So
KeyMint V1 is confirmed, and `DeviceProbe`'s `?: 1` fallback is correct.

---

## 4. Storage layout

`lpdump`, metadata version 10.2, `super` = 9,663,676,416 bytes:

```
group main_a : system_a  vendor_a  product_a  vendor_dlkm_a  odm_dlkm_a
group main_b : system_b  vendor_b  product_b  vendor_dlkm_b  odm_dlkm_b
```

Active slot `_a`. Metadata slot count 3.

---

## 5. Kernel

```
Linux 5.10.185-android12-9-00001-gc475c0851364-ab10989012
#1 SMP PREEMPT Mon Oct 23 06:24:46 UTC 2023 aarch64
```

GKI `android12-5.10`, matching the base the device tree targets.

---

## 6. WiFi regulatory domain — closes a proposed fix

```
Wifi Country Code = TT
```

A real regulatory domain, not the world domain `00`. The country-code fix
proposed during the WiFi review would gain nothing; it is already correct.

---

## 7. Capabilities now available

- **pstore is populated**: `/sys/fs/pstore/console-ramoops-0`, 262,132 bytes.
  The previous boot's kernel log is readable, which is the capture that could
  still resolve the Axion instant-revert question.
- **Partition dumps** via `dd` on `/dev/block/by-name/*` — used to pull the
  live `vendor_boot_a` for the hybrid recovery image.
- **`/vendor/etc` is readable**, so VINTF and vendor configs can be inspected
  on the real device instead of from an extracted image.

## 8. Reproducing this snapshot

```sh
adb mdns services                      # discovers IP and port, no typing
adb pair <ip>:<pairing-port> <code>
adb connect <ip>:<connect-port>
adb shell 'su -c "cat /proc/bootconfig"'
adb shell 'getprop | grep -iE "vbmeta|verifiedboot|trustkernel|keymaster"'
adb shell 'su -c lpdump'
```
