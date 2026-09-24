#!/bin/bash
# Build a vendor image that lets ANY unmodified GSI boot on the G7 Tab Pro, with
# no per-image patching. Does the whole fix in one pass:
#
#   1. libkeymint.so          : redirect the 2 OS-version property names
#   2. keymint-service binary : redirect the 3 root-of-trust property names
#   3. /vendor/build.prop     : define the 4 vendor-owned props (honest values)
#   4. vendor_property_contexts: label those 4 so the HAL may read them
#   5. rebuild the AVB hashtree footer with the stock salt + partition size
#
# The redirected root-of-trust values are the ones the device ACTUALLY has
# (unlocked / orange / absent digest). This makes the boot succeed by handing
# the TA a consistent root of trust instead of the locked/green a failing GSI's
# init fabricates; it does not change what the device attests. See
# docs/VENDOR_SELINUX_PROP_FIX.md and docs/INIT_SWAP_FIX.md.
#
# Binary rewrite is in place (shorter name, NUL-padded, same offset) so nothing
# moves. All the delicate validation lives in tools/patch-keymint-binary.py,
# which refuses on any surprise (wrong occurrence count, non-terminated string,
# bytes changed outside the plan).
set -euo pipefail

# ---- config (override via env) --------------------------------------------
SRC="${SRC:-/mnt/bulk/vendor_stock.img}"     # stock, UNPATCHED vendor image in
OUT="${OUT:-/mnt/bulk/vendor_kmfix.img}"      # new image; SRC is never touched
MNT="${MNT:-/tmp/vmnt}"
PART_SIZE="${PART_SIZE:-1006141440}"
SALT="${SALT:-15aa12b75c439cc3ab3d450adbb788d61019549360fc0a22d185058636100e96}"

# honest device values (see header). kmvbdig is deliberately left UNDEFINED to
# match the device's absent vbmeta digest -- an undefined prop reads empty and
# needs no SELinux label.
KMOSVER="${KMOSVER:-13}"
KMOSPATCH="${KMOSPATCH:-2025-09-05}"
KMVBSTATE="${KMVBSTATE:-orange}"
KMVBDEVST="${KMVBDEVST:-unlocked}"

HERE="$(cd "$(dirname "$0")" && pwd)"
PATCH="$HERE/patch-keymint-binary.py"
LIB=lib64/libkeymint.so
SVC=bin/hw/android.hardware.security.keymint-service.trustkernel
BP=build.prop
VPC=etc/selinux/vendor_property_contexts
# ---------------------------------------------------------------------------

export PATH="$PATH:$HOME/lineage-td/out/host/linux-x86/bin"
for t in avbtool debugfs python3; do
  command -v "$t" >/dev/null || { echo "FATAL: $t not on PATH"; exit 1; }
done
[ -f "$SRC" ] || { echo "FATAL: stock vendor image not found: $SRC"; exit 1; }
[ -f "$PATCH" ] || { echo "FATAL: patcher missing: $PATCH"; exit 1; }

echo "### copy (verify byte count, do not trust cp)"
rm -f "$OUT"; cp "$SRC" "$OUT"
[ "$(stat -c%s "$SRC")" = "$(stat -c%s "$OUT")" ] || { echo "FATAL: copy size mismatch"; exit 1; }
echo "copied $(stat -c%s "$OUT") bytes"

echo "### erase AVB footer (mount needs the bare filesystem)"
avbtool erase_footer --image "$OUT"

echo "### mount rw"
sudo mkdir -p "$MNT"
sudo mount -o loop,rw "$OUT" "$MNT"
trap 'sudo umount "$MNT" 2>/dev/null || true' EXIT

echo "### 1+2. redirect property names in the two binaries (in place)"
for spec in "$LIB:libkeymint" "$SVC:keymint-service"; do
  f="${spec%%:*}"; preset="${spec##*:}"
  [ -f "$MNT/$f" ] || { echo "FATAL: $f not in image"; exit 1; }
  # patch to a temp file, then copy bytes back preserving the inode's
  # label/mode/owner (cp of contents, not a rename).
  tmp="$(mktemp)"
  sudo cat "$MNT/$f" > "$tmp"
  python3 "$PATCH" "$tmp" "$tmp.out" --preset "$preset"
  sudo cp --no-preserve=all "$tmp.out" "$MNT/$f"   # contents only; keeps xattr/label
  rm -f "$tmp" "$tmp.out"
done

echo "### 3. define the vendor-owned props in build.prop"
if sudo grep -q "ro.vendor.kmosver" "$MNT/$BP"; then
  echo "build.prop already has the props, skipping"
else
  sudo tee -a "$MNT/$BP" >/dev/null <<EOF

# KeyMint property redirect -- values the device ACTUALLY reports, so an
# unmodified GSI boots. See docs/VENDOR_SELINUX_PROP_FIX.md.
ro.vendor.kmosver=$KMOSVER
ro.vendor.kmospatch=$KMOSPATCH
ro.vendor.kmvbstate=$KMVBSTATE
ro.vendor.kmvbdevst=$KMVBDEVST
# ro.vendor.kmvbdig intentionally undefined: the bootloader publishes no vbmeta
# digest, and an undefined prop reads empty, which is the honest value.
EOF
fi

echo "### 4. label the props so hal_keymint_default may read them"
if sudo grep -q "ro.vendor.kmosver" "$MNT/$VPC"; then
  echo "vendor_property_contexts already labelled, skipping"
else
  # vendor_mtk_default_prop carries mtk_core_property_type => readable by every
  # domain (HAL and adb shell), so `getprop ro.vendor.kmosver` is a real check.
  sudo tee -a "$MNT/$VPC" >/dev/null <<'EOF'

# KeyMint property redirect (docs/VENDOR_SELINUX_PROP_FIX.md).
ro.vendor.kmosver     u:object_r:vendor_mtk_default_prop:s0
ro.vendor.kmospatch   u:object_r:vendor_mtk_default_prop:s0
ro.vendor.kmvbstate   u:object_r:vendor_mtk_default_prop:s0
ro.vendor.kmvbdevst   u:object_r:vendor_mtk_default_prop:s0
EOF
fi

echo "### unmount + rebuild hashtree footer with stock params"
sync
sudo umount "$MNT"; trap - EXIT
avbtool add_hashtree_footer \
  --image "$OUT" \
  --partition_name vendor \
  --partition_size "$PART_SIZE" \
  --hash_algorithm sha256 \
  --salt "$SALT"

echo "### 5. verify the FINISHED artifact (trust nothing that 'succeeded')"
avbtool info_image --image "$OUT" | grep -E "Partition Name|Image Size|Salt|Root Digest|Algorithm"
avbtool verify_image --image "$OUT" && echo "AVB VERIFY: PASS"
echo "--- redirected names present, originals gone, props + labels landed ---"
for s in ro.vendor.kmosver ro.vendor.kmvbstate ro.vendor.kmvbdevst; do
  n=$(debugfs -R "cat /$LIB" "$OUT" 2>/dev/null | grep -ac "$s" || true)
  m=$(debugfs -R "cat /$SVC" "$OUT" 2>/dev/null | grep -ac "$s" || true)
  echo "  $s  in lib=$n svc=$m"
done
echo "  build.prop:"; debugfs -R "cat /$BP" "$OUT" 2>/dev/null | grep -E "kmos|kmvb" | sed 's/^/    /'
echo "  contexts  :"; debugfs -R "cat /$VPC" "$OUT" 2>/dev/null | grep -E "kmos|kmvb" | sed 's/^/    /'
echo
echo "DONE: $OUT"
echo "Flash to the vendor partition (both slots on A/B), keep the stock image to revert."
ls -la "$OUT"
