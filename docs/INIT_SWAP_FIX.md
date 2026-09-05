# Some GSIs hang at their own splash — and the cause is `/system/bin/init`

**TL;DR** — If a GSI hangs forever at *its own* boot splash on a device with a
legacy (KeyMint V1 / Keymaster-era) vendor TEE, while other GSIs of the same
Android version boot fine, try replacing `/system/bin/init` with the one from a
GSI that *does* boot on that device. Confirmed on a **UMIDIGI G7 Tab Pro**
(MT6789, TrustKernel TEE, Android 12 / API 31 vendor) across **four ROMs from
three separate lineages** — Infinity-X 3.12, crDroid 10, crDroid 11 and
Lunaris-AOSP 3.12.
Each was tested both ways: correctly version-patched alone it does not boot;
with Project CiRCLE's `init` swapped in as well, the same image boots.

This is *not* the version-property fix documented in
[KEYMINT_OS_VERSION_FIX.md](KEYMINT_OS_VERSION_FIX.md). That fix is still
required and still correct — this is a **second, independent** blocker that
affects some images even after the version patch is provably applied.

---

## Symptom

- The GSI is correctly patched: every `*.build.version.release` reads the value
  the TEE expects, verified inside the finished image. Pre-flight reports zero
  blockers. `avbtool verify_image` passes vbmeta **and** hashtree.
- It still hangs at the GSI's own splash (not the OEM splash — boot reaches
  userspace).
- `generateKey` returns **`-64 KEYMINT_NOT_CONFIGURED`**, `/data` never mounts,
  zygote crash-loops, and the display stack spams whatever the vendor's
  picture-quality HAL is (147k+ lines here) as pure downstream noise.
- **Reliably, not intermittently** — 2/2 and 3/3 attempts on the affected image.
  Other GSIs with the *identical* `ro.build.id` and sdk boot fine.

## What is actually going wrong

Established earlier by capturing a known-good boot and diffing it against the
hang (see [the project memory / SESSION_HANDOFF](SESSION_HANDOFF.md)):

```
vendor KeyMint HAL fails to register with servicemanager in time
  -> keystore2 cannot find IKeyMintDevice/default
  -> falls back to the legacy android.security.compat path (reports version 100)
  -> keystore2 CACHES that choice and never re-checks
  -> the real TrustKernel trustlet is never loaded at all
     (34 TEE kernel lines on a good boot; ZERO on a bad one)
  -> KEYMINT_NOT_CONFIGURED is the honest answer from an emulated device
     with nothing behind it
  -> vold cannot create the FBE key -> /data never mounts -> splash hang
```

On the working image the HAL registers ~54 ms after start and keystore2 finds
it. On the failing image keystore2 gives up and installs the emulation wrapper
**2 ms** after starting. It is a boot-time race, and the margin is tiny — which
is why *anything* that adds early-boot work can decide the outcome.

## The discovery

Doze-off's own release notes carry this disclaimer (verbatim, on Infinity-X,
Lunaris, Project CiRCLE and Axion):

> *for users if not boot in DSU user note #edit\_gsi and put this init file in
> system/bin. The reason for this is that some of my patches broke the DSU
> function on some old devices, and since I don't have a user to keep testing
> and testing until I figure it out, it's better if you push the working file.*

`/system/bin/init` had **never been compared** in this project's otherwise
exhaustive static diffing — that covered `keystore2`, `libkm_compat*`, `vold`,
SELinux policy, `keystore2.rc`, `hw/init.rc` and more, all of which came back
identical or irrelevant. init itself was the gap.

Comparing Project CiRCLE (**boots**) against Infinity-X 3.12 (**hangs**):

| | Circle | Infinity-X |
|---|---|---|
| `/system/bin/init` size | 2,708,416 | 2,724,744 (+16,328) |
| bytes differing | — | 2,055,573 of 2,724,744 |
| sha256 | `7ac91703…` | `8ea305a2…` |

For contrast, `/system/bin/vold` differs between the same two images by **16
bytes** and one string. This is a different magnitude entirely — a different
build of init, not a small patch.

`strings` present in Infinity-X's init and **entirely absent** from Circle's:

```
GetVbmetaSize: Attempting to open
/dev/block/by-name/vbmeta
GetVbmetaSize: ioctl(BLKGETSIZE64) failed for
GetVbmetaSize: lseek failed for
GetVbmetaSize: ro.boot.slot_suffix is empty
GetVbmetaDigest: Failed to set property 'ro.boot.vbmeta.digest' …
ro.boot.vbmeta.{avb_version,device_state,digest,hash_alg,invalidate_on_error,size}
oplusboot.verifiedbootstate
```

That is code which **opens a raw block device and runs `ioctl`/`lseek` on it,
inside `init`**, to synthesise `ro.boot.vbmeta.*` properties when the bootloader
did not provide them. `oplusboot.*` is an Oplus (OPPO/OnePlus/Realme) bootloader
property name, so this is near-certainly a fix for Oplus-brand devices that got
baked into a shared GSI init and now runs on every device using that base.

## The fix, and what it confirms

Replacing `/system/bin/init` with Circle's copy — changing **nothing else** —
makes the same Infinity-X image boot. **Confirmed on hardware via DSU**, and
since reproduced on crDroid 10, crDroid 11 and Lunaris-AOSP 3.12 — the crDroid
11 case done entirely in the app on the tablet, on a fresh non-booting image.

That is a clean single-variable result: identical image, identical version
patch, identical everything else, one file swapped, opposite outcome.

### What this proves, and what it does not

**Proven:** `/system/bin/init` is the discriminator between booting and
non-booting images in this family. Swapping a known-good one is an effective
fix.

**Narrowed, but still not proven:** *which part* of init is responsible.

Comparing the init from four ROMs with known outcomes on this device — all four
from the same maintainer, so build pipeline and toolchain are controlled —
isolates it to one function family:

| init | boots? | `/dev/block/by-name/vbmeta`, `oplusboot.verifiedbootstate` | `GetVbmetaSize` / `GetVbmetaDigest` |
|---|---|---|---|
| Project CiRCLE 1.2 | yes | absent | absent |
| AviumUI 16.2.1 | yes | **present** | **absent** |
| Infinity-X 3.12 | no | present | **present (10 strings)** |
| Lunaris-AOSP | no | present | **present (10 strings)** |

AviumUI is what makes this informative. It carries the vbmeta device path and
the `oplusboot` property name yet still boots, so those constants alone are
harmless — while the `GetVbmeta*` probe-and-log routines appear in exactly the
two failing images and neither booting one.

That is a clean 2-vs-2 correlation on a specific, plausible mechanism: opening
a raw block device and running `ioctl`/`lseek` inside `init`, in a race decided
by ~2 ms.

### Disassembly: the code is real, and it runs on the property-load path

A string proves code is *compiled in*, not that it *runs*. Disassembling
Infinity's init (llvm-objdump, ARM64) settles that part:

- The `GetVbmetaSize` strings are referenced by actual instructions at
  `0xfdbc4` and `0xfe0fc` — `adrp`/`add` pairs materialising their addresses.
  **Not dead strings.**
- They sit inside the function entered at `0xfa4ec`, which is reached through a
  5-deep call chain: `0xfa4ec ← 0x100414 ← 0xd9fa8 ← 0x98034 ← 0x95dc0 ←
  0x75d4c`. The three innermost links each have exactly **one** call site, so
  that part of the chain is unambiguous.
- What that function *is* comes from the other strings it references:
  `(Loading properties from`, `' in property file '`, `while loading .prop
  files`, `/build.prop`, `/default.prop`, `.build.version.sdk`. That is init's
  **property-loading** routine.

So the vbmeta probe is not late, rare or dead code — it executes while init is
loading boot properties, **early in second stage, on every boot, before
services start**. That is exactly the window the registration race lives in.

### Tested on hardware: the timing theory is WRONG

Infinity's own init was rebuilt with the vbmeta device path repointed at a
non-existent node (6 bytes changed, everything else byte-identical), so the
`open()` fails instantly and the `ioctl`/`lseek` never run. **It still hung.**

So the cost of the probe is not the mechanism, and by extension the "extra
early-boot work loses a timing race" explanation does not survive its first
real test. Worth stating plainly because that theory had been the working
assumption for days.

### The better hypothesis: fabricated root-of-trust values

Re-reading the strings after that failure shows why the test was inadequate —
when the device open fails, the code **still sets the property**:

```
GetVbmetaDigest: Property 'ro.boot.vbmeta.digest' set successfully to dynamic fallback value '
```

So the patch only changed *which* value got written, not *whether* one was.

And the probe is not an isolated oddity. Comparing strings across five inits —
three that boot (Project CiRCLE, AviumUI, and the maintainer's own distributed
"working init") against the two that fail — the entire functional difference is
**17 strings**, and every meaningful one is verified-boot related:

```
ro.boot.vbmeta.            (prefix, used to build names dynamically)
ro.is_ever_orange
ro.secureboot.devicelock
ro.secureboot.lockstate
```

The *full* names (`ro.boot.vbmeta.digest` and friends) appear in the working
inits too — that is ordinary kernel-cmdline handling. Only the **prefix** is
unique to the failing ones, i.e. only they contain code that *synthesises*
these names and sets them.

This matters because this device's bootloader does **not** publish those
properties, and the vendor KeyMint HAL reads exactly
`ro.boot.vbmeta.device_state`, `ro.boot.vbmeta.digest` and
`ro.boot.verifiedbootstate` to compute its root of trust. An init that
fabricates them hands the TA a root of trust the device never actually had.

It also fits an observation the timing theory never explained: the failures are
**perfectly deterministic** (2/2, 3/3 on repeat attempts). A race should be
flaky; a wrong constant should not be.

### The maintainer's own "working init", analysed

A user supplied the file Doze-off's release notes tell people to install —
verified byte-identical to the standalone `init` asset published on the
CiRCLE, Infinity-X and Axion releases (sha256 `f52af0f7…`, 2,725,064 bytes).
Analysing it directly is worthwhile, because it is an **independent** known-good
sample: not one of the ROM inits this investigation started from.

Two findings.

**1. It confirms the correlation, now 3-vs-2 and complete.**

| init | boots | `GetVbmeta*` | `ro.secureboot.*` | `ro.is_ever_orange` | `ro.boot.vbmeta.` prefix |
|---|---|---|---|---|---|
| Project CiRCLE | yes | 0 | 0 | 0 | 0 |
| AviumUI | yes | 0 | 0 | 0 | 0 |
| **Doze-off's fix init** | yes | **0** | **0** | **0** | **0** |
| Infinity-X 3.12 | no | 10 | 2 | 1 | 1 |
| Lunaris-AOSP | no | 10 | 2 | 1 | 1 |

Every init that boots carries **none** of the verified-boot synthesis; both
that fail carry **all** of it. No exceptions in either direction.

**2. It is a debug-featured init — and that is *not* the mechanism.**

It uniquely contains `/first_stage.sh`, `/data/local.prop`,
`androidboot.first_stage_console`, `/system/bin/lsof`, and
`Permissive SELinux boot, forcing sys.init.perf_lsm_hooks to 1`. That is a
development/rescue build, which fits its purpose as a file handed to users whose
device will not boot.

It would be easy to conclude the permissive/debug behaviour is what rescues the
boot. It is not: **Circle and AviumUI contain none of those strings and boot
perfectly.** The debug features are incidental to why it works.

Practical consequence: prefer **Circle's** init as a donor. It is the leaner
file, has no first-stage shell hook, no `/data/local.prop` reading and no
permissive-boot path, and it is equally effective. Doze-off's init is the right
choice only if you specifically want those rescue features.

### Two failed attempts at isolating the cause — and what they teach

Both were binary patches to Infinity's own init, tested on hardware. Both
failed, and **both failed because the patch did not do what I claimed**, not
because the hypothesis was wrong. Recorded in full because the pattern is the
lesson.

**Attempt 1 — repoint the vbmeta device path** (6 bytes). Made `open()` fail so
the `ioctl`/`lseek` never run. Still hung. But the strings say why it proved
nothing: on open failure the code *still sets the property*, from a fallback —
`Property 'ro.boot.vbmeta.digest' set successfully to dynamic fallback value`.
It changed which value was written, not whether one was.

**Attempt 2 — rename the property prefix** (7 bytes): `ro.boot.vbmeta.` ->
`ro.zzzz.vbmeta.`, plus `ro.is_ever_orange` and `ro.secureboot.*`. Still hung.
Disassembly afterwards showed the vbmeta function references the **full** names
—`ro.boot.vbmeta.device_state`, `.digest`, `.size` — and never the prefix. So
the three properties that actually feed the HAL's root of trust were still
being set. Only the two secondary ones were neutralised.

**The lesson, stated once so it is not repeated a third time:** confirm which
string the *code references* before patching it. String presence in the binary
says nothing about which copy a given call site uses, and a patch that misses
costs a full flash-and-boot cycle to discover.

**So the root-of-trust hypothesis is NOT disproven** — it has not yet been
tested. Only the timing theory has actually been falsified.

### Before patching again: just look

The hypothesis predicts something directly observable, with no binary surgery
at all. On the hung boot, with adb (Infinity ships `/adb_keys`, so it authorises
without `/data`):

```sh
adb shell getprop | grep -E "vbmeta|secureboot|is_ever_orange|verifiedboot"
```

- If `ro.boot.vbmeta.digest` / `.device_state` hold **synthesised values** on the
  hanging boot and are **absent or bootloader-supplied** on a working one
  (Project CiRCLE), the hypothesis is confirmed outright.
- If they look the same on both, it is dead, and the difference is elsewhere in
  init.

Either way it costs one boot and no guessing. A corrected patch
(`inf_init_norot2`, 19 bytes, the full names redirected) is published for
afterwards, but the observation should come first.

### Next test

Same shape, better aim: Infinity's own init with only the *synthesised*
property names redirected to inert ones (`ro.boot.vbmeta.` -> `ro.zzzz.vbmeta.`
etc., 7 bytes, length-preserving), leaving legitimate cmdline handling intact.
If that boots, fabricated root-of-trust values are the cause. Published as
`inf_init_norot` on the `gsi-patcher-v5.1` release.

Until then: **strongest candidate, consistent with every image tested and
confirmed to execute on the relevant path** — but not the proven cause.

## How to apply it

`/system/bin/init` is root-owned and SELinux-labelled `u:object_r:init_exec:s0`;
the label **must** survive or init won't run. Recipe that works:

```bash
# 0. capture the original AVB parameters FIRST — you must restore them exactly
avbtool info_image --image gsi.img   # note salt, rollback index, algorithm, size

# 1. extract a known-good init from a GSI that boots on your device
debugfs -R "cat /system/bin/init" good-gsi.img > good_init

# 2. make the target mountable read-write
avbtool erase_footer --image gsi.img
truncate -s +2G gsi.img
e2fsck -fy gsi.img && resize2fs gsi.img
e2fsck -E unshare_blocks -fy gsi.img && e2fsck -fy gsi.img

# 3. temporarily loosen the mode so an unprivileged cp can overwrite it
debugfs -w -R "sif /system/bin/init mode 0100777" gsi.img

# 4. swap — plain cp, NOT --remove-destination (see gotchas)
sudo mount -o loop,rw gsi.img /mnt/x
cp good_init /mnt/x/system/bin/init
sudo umount /mnt/x

# 5. restore the original mode, then verify the label survived
debugfs -w -R "sif /system/bin/init mode 0100755" gsi.img
debugfs -R  "ea_list /system/bin/init" gsi.img   # must show init_exec

# 6. shrink and rebuild the footer with the step-0 parameters
e2fsck -fy gsi.img && resize2fs -M gsi.img && e2fsck -fy gsi.img
avbtool add_hashtree_footer --image gsi.img --partition_name system \
  --partition_size <see note> --algorithm SHA256_RSA2048 \
  --key external/avb/test/data/testkey_rsa2048.pem \
  --salt <original> --hash_algorithm sha256 --rollback_index <original>
avbtool verify_image --image gsi.img --key …/testkey_rsa2048.pem
```

Then apply the normal version patch
([KEYMINT_OS_VERSION_FIX.md](KEYMINT_OS_VERSION_FIX.md) or `tools/gsi-patcher`)
to the result — order doesn't matter, but the last tool to touch the image must
be the one that re-signs it.

### Gotchas that will cost you time

- **A `sudo mount -o loop,rw` does not let a normal user overwrite a root-owned
  file.** The loop mount still enforces in-filesystem Unix permissions. Hence
  the `debugfs sif mode` dance above; `sudo cp` is the obvious alternative but
  needs root to hold the SELinux xattr correctly.
- **Use plain `cp`, never `cp --remove-destination` or `rm` + `cp`.** A plain
  `cp` truncates and rewrites the *existing inode*, so the `security.selinux`
  xattr rides along untouched. Unlinking and recreating drops the label, and an
  unlabelled init is an unbootable image. Verify with
  `debugfs -R "ea_list /system/bin/init"` before and after — do not assume.
- **`unshare_blocks` permanently inflates the image.** GSIs ship with
  deduplicated (shared) blocks; undoing that to allow writes is one-way. Here
  the shrunk minimum came out **4,270,088,192** bytes against an original
  partition size of 4,164,259,840 — *larger than the partition it came from*.
  `--partition_size` must exceed the content plus hashtree/footer headroom
  (~65–70 MB at this scale) and be a multiple of 4096. For **DSU** that is fine
  (it sizes `system_gsi` dynamically); for a **physical flash** you would need
  the logical partition resized to match.
- Re-verify with `avbtool verify_image` after *every* footer rebuild, and
  re-check the swapped file's sha256 inside the finished image — a later
  patching pass can silently undo work if a tool rewrites rather than edits.

## Scope

Expect this wherever a device has a **legacy KeyMint/Keymaster vendor HAL** that
races against keystore2 at boot, and a GSI whose `init` does more early work
than the baseline. The version-property fix and this fix are independent: a
given image may need either, both, or neither.

Known outcomes on the reference device (all A16, all `BP4A.251205.006`, all the
same `tdgsi_arm64_ab` base, all from the same maintainer):

| GSI | version patch | init swap | boots |
|---|---|---|---|
| Project CiRCLE 1.2 | required | not needed | yes |
| AviumUI 16.2.1 | required | not needed | yes |
| LineageOS 23.2 (different maintainer) | required | not needed | yes |
| Infinity-X 3.12 | applied, correct | **required** | **yes, with swap** |
| crDroid 10 | not enough on its own | **required** | **yes, with swap** |
| crDroid 11 | not enough on its own | **required** | **yes, with swap** |
| Lunaris-AOSP 3.12 | not enough on its own | **required** | **yes, with swap** |

All three of those were patched **on the device itself**, with the app, using
Project CiRCLE's init as the donor — no PC involved. Infinity-X's own init is a
sparse file, so that case also exercised the on-device ext4 block allocation
and relocation path end to end.

### Where this fix does NOT apply

**Axion 2.8 reverts instantly under DSU, with or without the init swap.** That
is a different failure entirely and worth naming so the fix is not mistaken for
a universal one.

An instant revert means the image is rejected at or before load — it never
reaches a GSI splash, so `init` never runs and replacing it cannot possibly
help. Confirmed: 2.8 still reverts with Project CiRCLE's init swapped in. It
matches Axion 2.7's older behaviour, so this is longstanding rather than a
regression.

The failure shape is the diagnostic:

| shape | meaning | does the init swap help? |
|---|---|---|
| hangs at the **GSI's own splash** | boot reached userspace; KeyMint/`/data` failure | **yes** — this is the case this document is about |
| **instant revert** to the previous ROM | image rejected at/before load | no — init never runs |
| hangs at the **OEM splash** | fails earlier still, before the GSI is entered | no |

For an instant revert, look at the DSU side — `gsid` and logcat during install
and first boot — rather than at version properties or init.

Curiously, Axion's own init is an outlier in the comparison below: it carries
the `GetVbmeta*` routines (9 strings) but **none** of the `ro.secureboot.*` or
`ro.is_ever_orange` properties, and it is ~34 KB larger than any other init
sampled. That is a different build variant again — but since swapping its init
out changes nothing, it is a curiosity here, not the cause.

### All three are controlled results

Each of the three was tested **both ways on the same device**, and in every
case the version patch alone was not enough:

| image | version patch only | + donor init |
|---|---|---|
| Infinity-X 3.12 | hangs at splash | boots |
| crDroid | does not boot | boots |
| Lunaris-AOSP 3.12 | does not boot | boots |

For Infinity-X the version patch was additionally verified correct *at runtime*
— live `getprop` on the hung boot showed `ro.keymaster.*.release` = 13 ==
`ro.build.version.release` = 13 — so it hung despite reporting exactly what the
TEE expected.

The no-donor runs used an earlier app build than the with-donor runs, so it is
worth stating why that is still a controlled comparison: `BuildProp.kt`,
`Avb.kt` and `HashTree.kt` are **byte-identical** across the two versions, and
the only changes to the orchestrator were the donor plumbing itself. The
version-patching, AVB re-signing and hashtree output are therefore the same in
both; the donor is the variable.

This also retires an older, weaker crDroid data point. It once failed with an
*instant DSU revert*, which was attributed to a signing-key bug fixed in v2.
The no-donor test above was run well after that fix and still failed, so the
signing bug does not explain crDroid.

**Three ROMs, three separate lineages, same result** — so this is not specific
to one maintainer's build pipeline.

---

## THE MECHANISM, FOUND — a hardcoded verified-boot spoof table (2026-09-01)

Everything above this line is correlation. This section is the code.

Cross-referencing `adrp`/`add` pairs in Infinity's init resolves every suspect
string to exactly one live code site, clustered in ~2.4 KB:

```
locked                        0xfce08      ro.boot.vbmeta.device_state   0xfcc98
green                         0xfce74      ro.boot.vbmeta.digest         0xfd60c
/dev/block/by-name/vbmeta     0xfd324      ro.boot.vbmeta.size           0xfd474
ro.is_ever_orange             0xfcedc      ro.secureboot.lockstate       0xfcee8
ro.secureboot.devicelock      0xfce14      oplusboot.verifiedbootstate   0xfce2c
```

Reconstructing the stack table those instructions build — pairing each resolved
string with the `str [sp, #N]` slot it is written to — gives an explicit
name -> value map:

```
ro.boot.vbmeta.device_state      = locked
ro.boot.verifiedbootstate        = green
ro.boot.veritymode               = enforcing
ro.secureboot.lockstate          = locked
vendor.boot.vbmeta.device_state  = locked
vendor.boot.verifiedbootstate    = green
oplusboot.verifiedbootstate      = green
ro.crypto.state                  = encrypted
ro.build.tags / ro.build.keys    = release-keys
ro.system.build.tags             = release-keys
ro.build.type and ro.{bootimage,product,system,system_ext,odm,vendor,
                      vendor_dlkm,system_dlkm}.build.type = user
ro.boot.vbmeta.hash_alg          = sha256
plus ro.boot.flash.locked, ro.is_ever_orange, ro.secureboot.devicelock,
     ro.debuggable, ro.force.debuggable, ro.adb.secure, ro.secure,
     ro.warranty_bit, ro.vendor.warranty_bit, sys.oem_unlock_allowed,
     ro.oem_unlock_supported
```

**This is a Play Integrity / SafetyNet property spoof** — the block a maintainer
adds so an unlocked device reports as locked, verified, `user`-built and
`release-keys`-signed.

`orange` and `yellow` appear in **no** init examined. The code has no path for
the honest values.

All four probed sites resolve to the same enclosing function entry `0xfa4ec` —
independently identified as init's **property-loading** routine, reached via a
5-deep call chain from startup. It runs early in second stage, on every boot,
before services start.

### Why this explains the failure

This device's bootloader is **unlocked** and publishes none of these properties.
The vendor KeyMint HAL computes its root of trust from exactly them, so the TA
is handed a root of trust the device never had, rejects it, and returns
`KEYMINT_NOT_CONFIGURED` — from which the chain is identical to the version
blocker: vold cannot create the FBE key, `/data` never mounts, splash hang.

It explains what the timing theory could not: **determinism** (2/2, 3/3 — a
wrong constant, not a race), the **shared error code**, and **why AviumUI
boots** despite carrying the vbmeta property *names* — it never sets them to
fabricated values.

### Status: [PC] strongly evidenced, NOT yet [HW]

Proven: the table exists, its values are `locked`/`green`/`enforcing`/
`release-keys`, it is referenced by live instructions, in a function that runs
every boot.

**Not** proven: that these properties are the cause. The step from "init sets
them" to "the TA rejects" is still inference. Three specific gaps:

1. Nobody has checked whether `libkeymint.so` reads these properties on this
   device — and `~/gsi-test/libkeymint.so` is extracted and available.
2. `ro.` properties are **write-once**. If something set them earlier, init's
   write is *rejected* and the hypothesis collapses. Unverified.
3. The branch conditions around the table were not examined; it may be guarded.

**The `getprop` observation in the previous section settles it for one boot and
no binary surgery. Do that first.**

### Side effect nobody has measured

Swapping in Circle's init **removes this spoofing**. Play Integrity, banking
apps and Wallet may behave differently on an init-swapped ROM. Untested.

Also: any earlier reasoning that used `ro.build.tags` as evidence about a ROM's
provenance should be re-examined — on these images it is spoofed.

### CORRECTION (2026-09-01, same day): the causal chain above is WRONG

The claim "the vendor KeyMint HAL computes its root of trust from exactly these
properties" was inference, and it has now been checked directly against the
vendor HAL. **It is false on this device.**

`/vendor/lib64/libkeymint.so` (extracted, 122,672 bytes, `ELF aarch64, for
Android 31`) contains exactly **three** property names, all version-related:

```
   87d0 ro.build.version.release
   87e9 ro.build.version.security_patch
   8f1d ro.vendor.build.security_patch
```

That is the complete list -- `strings -a libkeymint.so | grep -cE '^ro\.[a-z_.]+$'`
returns **3**. There is no `ro.boot.vbmeta.*`, no `verifiedbootstate`, no
`secureboot`, no `device_state`, no `veritymode`.

Nor is there any root-of-trust plumbing in its symbols: a case-insensitive
search of the dynamic symbol table for `rootoftrust|setbootparam|verifiedboot|
vbmeta` returns **zero** matches. What it does export is:

```
_ZN9keymaster12GetOsVersionEPKc          keymaster::GetOsVersion(char const*)
_ZN9keymaster12GetOsVersionEv            keymaster::GetOsVersion()
_ZN9keymaster16AndroidKeymaster14EarlyBootEndedEv
```

**So the vendor KeyMint HAL cannot be reading the properties the init spoof
sets.** The root of trust reaches the TEE by another route entirely -- almost
certainly bootloader-to-TEE directly, which is normal on MediaTek/TrustKernel.

#### What survives, and what does not

**Dead:** the specific causal chain "failing init fabricates root-of-trust
properties -> vendor HAL reads them -> TA rejects". The HAL does not read them.

**Untouched:** the *fix* is still correct and still hardware-verified on four
ROMs across three lineages. Swapping init works. That was never in question.

**Untouched:** the spoof table itself is real (verified by disassembly), it runs
in init's property-loading routine on every boot, and it remains the cleanest
structural difference between inits that boot here and inits that do not.

**Reopened:** *why* it breaks the boot. Recall from the top of this document
that in the init-blocker case the `-64` does **not** come from the TrustKernel
TA at all -- the TA is never loaded (zero TEE kernel lines). It comes from the
**emulated fallback** keystore2 installs after the vendor HAL fails to register
with servicemanager in time. So the question was never "what does the TA
reject"; it is **"why does the vendor KeyMint HAL fail to register when this
init is used"**.

Candidates from the table that could plausibly affect early-boot service
startup, none yet examined:

- `ro.crypto.state=encrypted` -- `ro.` props are **write-once**, so pre-setting
  this could make vold's own set fail and change the FBE path
- `ro.build.type=user` / `ro.build.tags=release-keys` / `ro.debuggable=0` /
  `ro.secure` / `ro.adb.secure` -- these gate which services start and how
  SELinux is applied; a domain transition that does not happen would prevent
  the HAL from registering
- `ro.boot.veritymode=enforcing` -- affects fs_mgr / dm-verity setup

#### Method note

This correction cost one `strings` invocation and one symbol-table dump, on a
file that had been sitting extracted in `~/gsi-test/` the entire time. It
overturned a conclusion that had already been written into three documents and
a commit message.

**That is the rule of this project restating itself: run the cheap direct check
before building a story on top of an inference.** The disassembly work in the
section above was sound -- the table is real. The error was in assuming, rather
than checking, who consumed it.

#### Bonus: the vendor patch is independently confirmed correct

`VENDOR_SELINUX_PROP_FIX.md` documents patching `libkeymint.so` at decimal
offsets **34768** and **34793**. Those are `0x87d0` and `0x87e9` -- exactly the
string offsets found above. The vendor patch targets the right bytes, and the
HAL reading only these three properties is precisely why that approach works.

---

# THE MECHANISM, FOUND FOR REAL (2026-09-01): `ro.crypto.state`

The correction above reopened the question by killing the root-of-trust chain.
This section answers it, and unlike every previous attempt **the consumer is
identified on this device's own vendor image**, not assumed.

## The missing piece: the vendor's own rc file

`/vendor/etc/init/trustkernel.rc`, read straight out of `vendor_a.img` (build
20241121, the one running on the device):

```
# For non-encrypted case
on property:ro.crypto.state=unencrypted
	setprop vendor.trustkernel.fs.mode 1
	setprop vendor.trustkernel.fs.state prepare

# For FDE/encrypted successfully
on property:vold.decrypt=trigger_restart_framework
	setprop vendor.trustkernel.fs.mode 2
	setprop vendor.trustkernel.fs.state prepare

# For FBE/encrypted successfully
on property:ro.crypto.type=file && property:ro.crypto.state=encrypted
	setprop vendor.trustkernel.fs.mode 3
	setprop vendor.trustkernel.fs.state prepare

on property:vendor.trustkernel.fs.state=prepare
	...
	mkdir /data/vendor/t6/app        # the TA path
	setprop vendor.trustkernel.fs.state ready
```

**`ro.crypto.state` is the gate on the TrustKernel TEE's filesystem
preparation** -- the TEE that KeyMint runs on. And `ro.` properties are
**write-once**.

The spoof table sets `ro.crypto.state = encrypted` during init's property load,
long before vold determines the real value. vold's own write then silently
fails, so the value never reflects reality and the matching trigger never fires.

## The discriminator is a code path, not a string

`ro.crypto.state` appears in **every** init examined, booting or not -- AOSP's
init references it legitimately. String presence proves nothing, exactly as
rule 1 says. Cross-referencing `adrp`/`add` pairs separates them cleanly:

```
inf_init (HANGS)                          circle_init (BOOTS)
  ro.crypto.state  0x9f29c  fn 0x98034      ro.crypto.state  0x9f29c  fn 0x98034
  ro.crypto.state  0x9f2f0  fn 0x98034      ro.crypto.state  0x9f2f0  fn 0x98034
  ro.crypto.state  0xfce68  fn 0xfa4ec  <-- (absent)
  encrypted        0x9f2b0  fn 0x98034      encrypted        0x9f2b0  fn 0x98034
  encrypted        0xfced0  fn 0xfa4ec  <-- (absent)
```

The legitimate references are at **byte-identical addresses** in both binaries.
The failing init has one **extra** reference, inside `0xfa4ec` -- the spoof
table in the property-loading routine. A clean 1-vs-0 on a code path.

## Why this fits every observation

| observation | explained |
|---|---|
| **zero TEE kernel lines** on a bad boot vs 34 on a good one | the TEE filesystem is never prepared, so the TA never loads |
| **perfectly deterministic** (2/2, 3/3) | a write-once collision is not a race |
| the HAL "fails to register in time" | it is waiting on a TEE that will never be ready |
| **swapping init fixes it** | no spoof entry, so vold's value stands and the trigger fires |
| `libkeymint.so` reads no root-of-trust props | irrelevant -- the mechanism is `ro.crypto.state`, not RoT |
| AviumUI boots | no spoof table at all |
| the version patch alone is not enough | genuinely separate blocker |

It also survives the test that killed the timing theory: repointing the vbmeta
device path changed nothing here, because `ro.crypto.state` is a different table
entry entirely.

**Scope check:** of the ~35 properties the spoof table sets, `ro.crypto.state`
is the **only one** that gates anything functional on this vendor. Grepping all
92 `/vendor/etc/init/*.rc` files for `on property:` triggers on spoofed names
returns the two TrustKernel rules above, plus VTS rules that only fire on
`ro.build.type=eng|userdebug` (this is a `user` build, so they are inert).

## The fix: one instruction, not a whole init

`tools/patch-init-crypto-spoof.py` nudges the spoof entry's name pointer three
bytes into its own string, so it writes to the inert name `crypto.state` and
leaves every legitimate `ro.crypto.state` reference untouched:

```
add x14, x14, #2702   ->   add x14, x14, #2705
0x10000 + 2702 = 0x10A8E "ro.crypto.state"
0x10000 + 2705 = 0x10A91    "crypto.state"
```

**One byte changes.** Same size, same inode, same block allocation -- so the
existing in-place patch path handles it with no relocation.

The spoof reference is told from the legitimate ones **structurally**: it is the
one whose enclosing function also materialises `locked` and `green`. The script
refuses to run on an init without that fingerprint.

Artefacts:

```
5f2d81e724a5d362cfba67a5c1b275e0f3e7ae8e401760b7268b7976c3eefa9c  inf_init_nocrypto      2724744
99c9da14f94b8a5b9b9fe1e9098f0ebcbc7058b9f39213e6329b208d03cf8257  lunaris_init_nocrypto  2724864
```

### Why this is better than the init swap

The swap works but replaces the ROM's whole init, discarding **all** its
spoofing -- Play Integrity, `ro.build.tags`, `ro.debuggable` and the rest. This
neutralises the single entry that breaks boot and **keeps everything else**,
which resolves the untested side-effect flagged earlier.

## A prediction that already checks out

Running the script across every init produces this, with no tuning:

```
inf_init      spoof fn 0xfa4ec  -> patched (1 byte)
lunaris_init  spoof fn 0xfa02c  -> patched (1 byte)
axion_init    spoof fn 0xfa5e0  -> REFUSED: spoof table does not reference ro.crypto.state
circle_init   -> REFUSED: no spoof table
avium_init    -> REFUSED: no spoof table
dozeoff_init  -> REFUSED: no spoof table
```

Three things fall out that were not designed for:

1. The three inits known to **boot** are exactly the three with no spoof table.
2. The two inits known to **splash-hang** both carry the crypto entry, at
   different offsets in different functions -- so the pattern is structural, not
   an artefact of one build.
3. **Axion has a spoof table but no crypto entry** -- and Axion is the one ROM
   whose failure is a *different shape* (instant revert, before init runs). The
   model predicts Axion should not be in the splash-hang family, and its init
   independently confirms it.

## Status: [PC], awaiting one hardware test

Proven offline: the vendor gates its TEE fs on `ro.crypto.state`; the failing
inits contain an extra write to it that booting inits do not; the patch changes
exactly one byte and only the spoof reference.

**Not** proven: that vold's write actually loses the write-once race in
practice, and that this is sufficient to fix the boot.

**The test:** patch a non-booting Infinity-X image with the app, using
`inf_init_nocrypto` as the donor init. Same workflow as always.

- **Boots** -> mechanism confirmed, and the fix keeps the ROM's spoofing.
- **Still hangs** -> `ro.crypto.state` is not sufficient; the next candidates
  are the remaining table entries, and the `getprop` observation becomes the
  priority again.

Either way it is one DSU cycle and a single-variable change against an image
whose unpatched behaviour is already known.

---

# `ro.crypto.state` FAILED ON HARDWARE — and the real consumer was in a file I had not opened

## The failed test [HW]

`inf_init_nocrypto` (one byte, the `ro.crypto.state` spoof entry redirected)
was patched into a non-booting Infinity-X image via the app. **Result: GSI
splash hang, unchanged.**

Before blaming the hypothesis, the patch was audited against rule 1 — the
failure mode that has cost this project three cycles now. It holds up:

- The table loop was decoded: `add x25, x25, #16` / `cmp x25, #592` over a base
  of `sp+608` = **37 entries of 16 bytes**, name at `+0`, value at `+8`. That
  matches the reconstructed table exactly.
- The per-entry call is `bl 0xfa43c`, whose failure path logs *"Unable to set
  property '...' to send property message"* — so it really is a property
  **set** table, not a read-time override.
- Post-patch cross-reference confirmed the spoof entry pointed at the inert
  `crypto.state` while both legitimate references were untouched.

So the patch did what it claimed. **`ro.crypto.state` is simply not the
blocker.**

## Where the analysis went wrong

The earlier correction concluded "the vendor KeyMint HAL does not read the
root-of-trust properties" from `libkeymint.so`. **That checked the wrong file.**
`libkeymint.so` is the library; the HAL is a *service binary*:

```
/vendor/bin/hw/android.hardware.security.keymint-service.trustkernel
```

Extracted from `vendor_a.img` and dumped, the properties it references are:

```
ro.boot.vbmeta.device_state
ro.boot.vbmeta.digest
ro.boot.verifiedbootstate
ro.product.board / brand / device / manufacturer / model / name
```

The first three are **exactly** what the spoof table fabricates. So the
root-of-trust hypothesis was right all along; the disproof was an artefact of
opening the library instead of the binary that loads it.

For completeness, `/vendor/bin/teed` (the TrustKernel daemon) references only
`ro.board.platform`, `ro.product.brand`, `ro.product.model` — nothing spoofed.

## The discriminator, again on code paths

```
inf_init (HANGS)                                     circle_init (BOOTS)
  ro.boot.vbmeta.device_state  0xfcc98  fn 0xfa4ec     (string absent entirely)
  ro.boot.vbmeta.digest        0xfd60c  fn 0xfa4ec     (string absent entirely)
  ro.boot.verifiedbootstate    0xdbdec  fn 0xd9fa8     0xdb8cc  fn 0xd9fa8
  ro.boot.verifiedbootstate    0xfce50  fn 0xfa4ec  <-- extra
```

Circle does not even contain the `device_state` or `digest` strings. For
`verifiedbootstate` both binaries share a legitimate reference in `0xd9fa8`;
only the failing one has a second reference inside the spoof table.

## The fix

`tools/patch-init-spoof.py` redirects those three name pointers past their
`ro.` prefix, so each entry writes an inert unread name:

```
ro.boot.vbmeta.device_state  0xfcc98  #229  -> #232   'boot.vbmeta.device_state'
ro.boot.vbmeta.digest        0xfd60c  #1554 -> #1557  'boot.vbmeta.digest'
ro.boot.verifiedbootstate    0xfce50  #3568 -> #3571  'boot.verifiedbootstate'
```

**Three bytes.** Size unchanged; the in-place image path still applies. The
legitimate reference at `0xdbdec` is verified untouched after patching.

The point is not to write *better* values — it is to leave the properties
**absent**, which is precisely the state Project CiRCLE's init produces, and
Circle boots here.

```
ff99b5e8b078638f1e6e2cd07d120af10264065ecd4a4dd7f031479091caf60d  inf_init_norot3      2724744
f3efaf7e4aa5299f8bff18c9aa1f8bf46585d9637aa41cb8fddabf60a734dd9f  lunaris_init_norot3  2724864
```

This also supersedes `inf_init_norot2`, which redirected the same properties but
was built before the table structure was understood and was never validated by
post-patch cross-reference.

## Status [PC] — one DSU cycle to settle

Unlike every previous version of this hypothesis, the consumer is now
**identified in a binary on this device**, not assumed. What remains untested is
whether leaving the properties absent is sufficient.

Use `inf_init_norot3` as the donor init on a non-booting Infinity-X image.

- **Boots** — root-of-trust confirmed, and the ROM keeps all its other spoofing
  (Play Integrity, `ro.build.tags`, `ro.debuggable`).
- **Still hangs** — the root-of-trust content is not the mechanism either. At
  that point stop patching and run the `getprop` capture: with three failed
  binary patches, the observation has earned priority over further surgery.

## Method note, third time

Each of the three failed patches failed for the same reason in a different
disguise: **the thing being reasoned about was not the thing being read.**
Test 1 patched a path but not the property write. Test 2 patched a prefix the
code never used. This time the analysis was correct but pointed at the wrong
consumer, because `libkeymint.so` was opened instead of the service binary that
loads it.

The rule generalises beyond strings: **confirm you are reading the artefact
that actually runs.**

---

# CONFIRMED ON HARDWARE (2026-09-01): the root of trust, in three bytes

`inf_init_norot3` was patched into a non-booting Infinity-X image with the app
and **the image booted.**

That closes the investigation this document opened. The mechanism is no longer
inference:

```
failing init's property-load routine (fn 0xfa4ec) sets, unconditionally:
    ro.boot.vbmeta.device_state = locked
    ro.boot.verifiedbootstate   = green
    ro.boot.vbmeta.digest       = <synthesised>
  on a device whose bootloader publishes NONE of them
  -> /vendor/bin/hw/android.hardware.security.keymint-service.trustkernel
     reads exactly these three for its root of trust
  -> the TA is handed a root of trust the device never had, and rejects
  -> the TrustKernel trustlet never loads (zero TEE kernel lines)
  -> keystore2 finds no IKeyMintDevice, caches the emulated fallback
  -> generateKey returns -64 KEYMINT_NOT_CONFIGURED
  -> vold cannot create the FBE key -> /data never mounts -> splash hang
```

**Three bytes**, at `0xfcc98`, `0xfce50`, `0xfd60c` — each an `add` immediate
nudged by 3 so the entry writes an inert name and the real property is left
**absent**, which is the state Project CiRCLE's init produces.

## What this now explains, end to end

| observation | explanation |
|---|---|
| zero TEE kernel lines on a bad boot, 34 on a good one | the TA rejects the fabricated RoT and never loads |
| perfectly deterministic (2/2, 3/3) | a wrong constant, not a race |
| the HAL "loses a registration race" by 2 ms | it never had anything to register — keystore2 gave up on a TA that would never appear |
| swapping the whole init fixes it | Circle's init leaves the three properties absent |
| AviumUI boots while carrying the property *names* | it has the names but no spoof table, so it never writes them |
| the version patch alone is not enough | genuinely separate blocker (blocker 1) |
| test 1 (repoint vbmeta device path) failed | the property was still set, from a fallback |
| test 2 (rename the prefix) failed | the code uses the full names, never the prefix |
| `ro.crypto.state` patch failed | right technique, wrong property |

The **timing theory was wrong** (falsified on hardware), the **root-of-trust
theory was right**, and the two failed root-of-trust patches failed on
execution, not on the idea — exactly as recorded at the time.

## Why this beats the init swap

| | donor-init swap | `norot3` (3 bytes) |
|---|---|---|
| fixes the boot | yes | **yes** |
| needs a donor file | yes | **no** |
| keeps the ROM's Play Integrity spoofing | **no** | **yes** |
| keeps `ro.build.tags` / `ro.debuggable` / `ro.secure` | **no** | **yes** |
| bytes changed in `/system/bin/init` | 2.7 MB | **3** |
| risk of donor/ROM version mismatch | real | none |

The swap replaces the ROM's entire init and discards **all** of its spoofing.
This removes only the three entries that break the boot, and the remaining ~34
spoof entries keep working. **Worth verifying on the booted system:** Play
Integrity, Wallet and banking apps should now behave as the ROM intended,
unlike after a donor swap.

## Which of the three is decisive — unmeasured

All three were neutralised at once. Any one of them might be sufficient. This
was deliberate: the goal was a working fix, and a three-way bisect would cost
three more DSU cycles for no practical gain. Recorded so nobody later reads
"all three matter" into a result that does not say so.

## Artefacts

```
ff99b5e8b078638f1e6e2cd07d120af10264065ecd4a4dd7f031479091caf60d  inf_init_norot3      2724744  CONFIRMED [HW]
f3efaf7e4aa5299f8bff18c9aa1f8bf46585d9637aa41cb8fddabf60a734dd9f  lunaris_init_norot3  2724864  untested
```

Regenerate for any affected init with:

```sh
tools/patch-init-spoof.py <init-in> <init-out>
```

It locates the spoof table structurally (the function materialising both
`locked` and `green`), refuses on inits that have none, and leaves every
legitimate reference alone.

---

# Shipped in the app: v5.2 does this on-device, no donor needed

`InitSpoof.kt` reimplements the three-byte fix in Kotlin so the app can apply it
to the image's **own** init. No donor file, no PC.

## Doing it without a disassembler

`llvm-objdump` does not exist on Android, but a full disassembly was never
needed -- only three instruction forms, decoded directly:

```
ADRP Rd, page     0x9F000000 & w == 0x90000000
ADD  Rd, Rn, #i   0xFFC00000 & w == 0x91000000   (64-bit, no shift)
BL   target       0xFC000000 & w == 0x94000000
```

The scan walks the executable PT_LOAD segment once, tracking the most recent
`ADRP` per register and resolving every `ADD` that consumes one -- the same
cross-reference the PC tool gets from objdump. `BL` targets give function
entries, which is what makes the structural spoof-table test possible.

Register tracking is deliberately naive, with no control-flow analysis. That is
safe here: a stale register can only ever resolve to an address that is not one
of the wanted strings, and is then ignored.

## Verified against the PC tool

The Kotlin implementation produces **byte-identical output** to
`tools/patch-init-spoof.py` on every init available, including the exact binary
that booted on hardware:

```
inf_init      -> ff99b5e8...  == inf_init_norot3 (the [HW]-confirmed artefact)
lunaris_init  -> f3efaf7e...  == lunaris_init_norot3
```

Full behaviour across the set:

```
inf_init       3 entries patched, 1 legitimate ref left alone (0xdbdec)
lunaris_init   3 entries patched, 2 legitimate refs left alone (0xc89a8, 0xdb8cc)
axion_init     2 entries patched (its table has no ro.boot.vbmeta.digest)
circle_init    NotApplicable -- no spoof table
avium_init     NotApplicable -- no spoof table
dozeoff_init   NotApplicable -- no spoof table
```

The three inits known to boot are exactly the three refused, with no tuning.

## End-to-end

Pristine `inf312.img.xz` through the CLI with `--fix-init`: version properties
patched in all three prop files, and the init extracted back out of the finished
image is byte-identical to `inf_init_norot3`.

## Using it

**App (v5.2):** "Fix init verified-boot spoofing" is a checkbox, **on by
default**. It is a no-op on images without a spoof table -- reported in the log,
not an error. Picking a donor turns it off automatically, since a donor replaces
the whole init and would overwrite the patch.

**CLI:** `--fix-init`, mutually exclusive with `--donor-init`/`--donor-image`.

```sh
cli image.img.xz --out patched.img --fix-init --key testkey.pkcs8.der
```

## Why this is now the default path

| | donor swap | `--fix-init` |
|---|---|---|
| needs a donor file | yes | **no** |
| keeps the ROM's Play Integrity spoofing | no | **yes** |
| bytes changed in init | 2.7 MB | **3** |
| donor/ROM version mismatch risk | real | none |
| works on an image whose ROM has no donor available | no | **yes** |

The donor path stays for images this does not apply to.

---

# CORRECTION (2026-09-05): the bootloader DOES publish verified-boot state

Everything above repeats a premise that is **false**, now that a rooted live
device has been inspected: *"this device's bootloader publishes none of them."*

`/proc/cmdline` carries no verified-boot arguments — which is presumably where
that belief came from. But this device passes them through **bootconfig**
instead, and they are honest:

```
$ cat /proc/bootconfig
androidboot.vbmeta.device_state = "unlocked"
androidboot.verifiedbootstate   = "orange"
```

## What this changes, and what it does not

**The fix is untouched.** Neutralising the three root-of-trust entries still
boots previously-hanging images, confirmed on hardware. Nothing about the
three-byte patch changes.

**The mechanism gets stronger, not weaker.** It was described as an init
fabricating values into a vacuum. It is really an init **overwriting honest,
bootloader-supplied values with contradictory ones**, before KeyMint reads
them:

```
bootloader (bootconfig)  unlocked / orange     <- what the TEE itself believes
failing init, EARLY      locked / green        <- overwrites, pre-KeyMint
KeyMint service          reads locked / green, hands them to the TA
TA                       independently knows unlocked / orange -> CONTRADICTION
                         -> refuses to configure -> never loads
```

A TA being told something that contradicts what it already knows is a much more
natural rejection than one being handed values for properties it has never seen.

## The control that was sitting in plain sight

The daily driver **sets exactly the same values** — `locked` and `green` — via
Magisk (`playintegrityfix`, `tricky_store`), and boots perfectly:

```
ro.boot.vbmeta.device_state = locked        (Magisk, post-fs-data)
ro.boot.verifiedbootstate   = green         (Magisk, post-fs-data)
ro.keymaster.xxx.vbmeta_state      = unlocked   (honest, read earlier)
ro.keymaster.xxx.verifiedbootstate = orange     (honest, read earlier)
```

Identical properties, identical values, opposite outcome. The only variable is
**when** the write happens: Magisk writes at `post-fs-data`, long after KeyMint
has read the honest values and configured successfully; the failing init writes
during early property load, before it.

**So timing is decisive, not content.** That is precisely the "ordering, not
content" alternative raised in the external review of 2026-09-01 (its section
2.1), which was noted as surviving and then never pursued. It was right.

## Consequences

1. **A late restore is not merely possible — it is already running.** The idea
   floated earlier (set the spoofed values after boot, so Play Integrity sees
   them without breaking KeyMint) is exactly what Magisk does on this device
   today. It needs no ext4 file creation and no new patcher feature; Magisk
   already solves it, which retires that proposal for good.

2. **The `-64` in the init case is a contradiction rejection**, not a
   provisioning failure. Worth keeping distinct from blocker 1, where the TA
   rejects an OS version it was never provisioned with.

3. **Check `/proc/bootconfig`, not just `/proc/cmdline`.** On this Android
   generation the boot arguments moved. An empty `cmdline` grep is not evidence
   the bootloader is silent — it is evidence you read the wrong channel. That is
   the fifth instance in this project of analysing the wrong artefact.

Full capture: [DEVICE_SNAPSHOT.md](DEVICE_SNAPSHOT.md).
