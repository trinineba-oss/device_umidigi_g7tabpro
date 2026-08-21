# Vendor property redirect — why it bootlooped, and the fix

Status: **cause confirmed by policy analysis; corrected image built and verified
offline; not yet tested on hardware.**

This is goal 2 of the project: make *any* unmodified GSI boot, with no per-image
patching. The mechanism is a vendor-side redirect of the OS-version properties
that the TrustKernel KeyMint HAL reports to the TA. See
[KEYMINT_OS_VERSION_FIX.md](KEYMINT_OS_VERSION_FIX.md) for why the version has to
read as 13 in the first place.

## The approach

`/vendor/lib64/libkeymint.so` is binary-patched so that instead of reading the
*system's* version properties it reads two vendor-owned ones:

| offset | original (len)                     | replacement (len)        |
|--------|------------------------------------|--------------------------|
| 34768  | `ro.build.version.release` (24)    | `ro.vendor.kmosver` (17) |
| 34793  | `ro.build.version.security_patch` (31) | `ro.vendor.kmospatch` (19) |

Both are plain NUL-terminated strings and both replacements are shorter, so they
are written in place and NUL-padded — no relocation, no length changes.

`/vendor/build.prop` then defines them (lines 643–644):

```
ro.vendor.kmosver=13
ro.vendor.kmospatch=2025-09-05
```

## Why it bootlooped

The properties were **defined but never labeled.**

A property with no matching entry in any `*_property_contexts` file falls back to
the `default_prop` label. Property *reads* are not a free operation on Android:
since Oreo the property area is split into one file per SELinux context under
`/dev/__properties__/`, and the reading domain needs `read`/`map` on that file.

The HAL's domain is `hal_keymint_default` (`vendor_file_contexts` labels the
binary `hal_keymint_default_exec`, and the policy carries
`type_transition init hal_keymint_default_exec:process hal_keymint_default`).

Querying the shipped `/vendor/etc/selinux/precompiled_sepolicy`:

```
$ sesearch --allow -s hal_keymint_default -t default_prop -c file sepolicy
              (no rules)

$ sesearch --allow -s hal_keymint_default -t build_prop -c file sepolicy
allow domain build_prop:file { getattr map open read };
```

So the *unpatched* HAL could read `ro.build.version.release` — `build_prop` is
readable by every domain — while the patched one reads a `default_prop` property
it has no access to. `property_get` returns empty, the HAL hands the TA an empty
OS version, and KeyMint never configures.

The failure is worse than the original hang because the service is
`class early_hal`:

```
service vendor.keymint-trustkernel /vendor/bin/hw/android.hardware.security.keymint-service.trustkernel
    class early_hal
    user system
    group system
```

Boot stalls before the later classes ever start and the MediaTek watchdog reboots
the device — which is why this presented as a bootloop rather than the familiar
static hang at the splash.

## The fix

Two lines appended to `/vendor/etc/selinux/vendor_property_contexts`:

```
ro.vendor.kmosver     u:object_r:vendor_mtk_default_prop:s0
ro.vendor.kmospatch   u:object_r:vendor_mtk_default_prop:s0
```

`vendor_mtk_default_prop` is the right type, verified against the shipped policy:

```
type vendor_mtk_default_prop, property_type, vendor_property_type,
     vendor_restricted_property_type, mtk_core_property_type;
```

1. `property_type` means `allow init property_type:property_service set;`
   applies, so init may set it from `/vendor/build.prop`.
2. `mtk_core_property_type` means
   `allow domain mtk_core_property_type:file { getattr map open read };`
   applies — readable by **every** domain, so `hal_keymint_default` can read it.
3. It is already declared in the precompiled policy, so **no sepolicy rebuild is
   required** — this is a two-line text change inside the vendor image.

Do not invent a new type. A new type would have to be declared in policy, which
means rebuilding and reflashing sepolicy; there is no reason to when an
already-granted type exists.

### Why not `vendor_default_prop`

`vendor_default_prop` also works *for the HAL* —
`allow hal_keymint_default vendor_default_prop:file { getattr map open read };`
is an explicit grant. It was the first choice and is the more narrowly-scoped
type. It was rejected because it is `vendor_internal_property_type` and the
`shell` domain has **no read access** to it:

```
$ sesearch --allow -s shell -t vendor_default_prop -c file sepolicy
              (no rules)
```

That means `adb shell getprop ro.vendor.kmosver` prints **empty even when the
fix is working perfectly** — a successful boot and a failed one look identical
from the shell, which is a very expensive thing to get wrong when each test
costs a flash cycle. `vendor_mtk_default_prop` is world-readable, so `getprop`
is a real check.

The two values are an Android version and a patch date, both already public in
`build.prop`, so world-readable leaks nothing.

## Building the image

`tools/patch-vendor-keymint-selinux.sh` does it. Notes that matter:

- The vendor image has **no `shared_blocks`** and ~725 free blocks, so it mounts
  read-write directly and the file can be **appended in place**. That is
  deliberate: appending preserves the file's inode, mode, and its
  `security.selinux` xattr (`u:object_r:vendor_configs_file:s0`). Doing this as
  `debugfs rm` + `write` would silently drop the xattr.
- `avbtool erase_footer` first, edit, then `add_hashtree_footer` — never grow the
  file with `truncate`, which strands the footer.
- `add_hashtree_footer` shells out to a `fec` binary that is not on the default
  PATH; it lives in `out/host/linux-x86/bin`.
- Rebuild with the **stock salt** (`15aa12b7…`) and stock partition size
  (1006141440) so the only intended difference is the root digest.
- `avbtool verify_image` requires the file be named after the partition
  (`vendor.img`) or it throws a misleading `FileNotFoundError`.

Verified in the finished artifact, not just at the mount point:

```
Partition Name: vendor      Salt: 15aa12b7…
Root Digest:    b9faed28a4b35930eac82c8aca4b170558acb3709be5879e7265e7cbe9aa1928
vbmeta: Successfully verified footer and NONE vbmeta struct in vendor.img
vendor: Successfully verified sha256 hashtree of vendor.img for image of 990183424 bytes
```

## Completeness check

The HAL binary and `libkeymint.so` between them reference 12 property names:

```
ro.boot.vbmeta.device_state   ro.product.brand         ro.product.name
ro.boot.vbmeta.digest         ro.product.device        ro.vendor.build.security_patch
ro.boot.verifiedbootstate     ro.product.manufacturer  ro.vendor.kmospatch
ro.product.board              ro.product.model         ro.vendor.kmosver
```

Only the last two are new. The other ten are stock AOSP names that the
*unpatched* HAL already read successfully on firmware that boots, so none of
them can be a second instance of this bug.

## Flashing (untested — read this first)

The rebuilt hashtree has a **new root digest** (`b9faed28…`, stock is
`505d92a5…`). Vendor's hashtree descriptor lives in the chained `vbmeta_vendor`
partition, so dm-verity must be switched off or verification fails:

```
fastboot --disable-verity flash vbmeta vbmeta.img
adb reboot fastboot        # vendor is a logical partition -> needs fastbootd
fastboot flash vendor vendor_kmsel.img
```

**Use `--disable-verity` only. Never add `--disable-verification`** — it changes
the verified-boot state that the FBE keys are bound to, and existing `/data`
becomes unreadable ("data is corrupt"). That has already cost this project two
recovery cycles.

Recovering from a bad vendor flash needs **both** stock `vbmeta` *and* stock
`vendor` restored; SP Flash Tool in plain Download mode is the escape hatch.
Never "Format All" — that is what damaged this unit's keybox originally.

## Expected result

If the theory is right: KeyMint configures normally, `generateKey` returns the
benign `-67` instead of `-64`, `/data` mounts, and an **unmodified** GSI boots —
no per-image `build.prop` patching, no `tools/patch-gsi-keymint.sh` step.

Confirm with `getprop ro.vendor.kmosver` (should print `13`, not empty) and by
watching for `-64 KEYMINT_NOT_CONFIGURED` disappearing from logcat.

If `getprop` shows `13` from the shell but KeyMint still fails, the label is not
the remaining problem — the shell's domain differs from the HAL's, so re-check
with `dmesg | grep avc` for a denial naming `hal_keymint_default`.
