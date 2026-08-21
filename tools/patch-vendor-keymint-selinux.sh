#!/bin/bash
# Adds the SELinux property_contexts entries that let hal_keymint_default read
# the redirected OS-version props. Without them the props fall back to
# default_prop, which that domain cannot read -> empty version -> early_hal
# fails -> watchdog bootloop. See docs/VENDOR_SELINUX_PROP_FIX.md.
set -euo pipefail

export PATH="$PATH:$HOME/lineage-td/out/host/linux-x86/bin"
command -v fec >/dev/null || { echo "FATAL: fec not on PATH"; exit 1; }

SRC=/mnt/bulk/vendor_kmver.img
OUT=/mnt/bulk/vendor_kmsel.img          # new image, original untouched
MNT=/tmp/vmnt
PART_SIZE=1006141440
SALT=15aa12b75c439cc3ab3d450adbb788d61019549360fc0a22d185058636100e96
VPC=etc/selinux/vendor_property_contexts

echo "### free space on /mnt/bulk"; df -h /mnt/bulk | tail -1

echo "### copy (verify byte count, do not trust cp)"
rm -f "$OUT"; cp "$SRC" "$OUT"
[ "$(stat -c%s "$SRC")" = "$(stat -c%s "$OUT")" ] || { echo "FATAL: copy size mismatch"; exit 1; }
echo "copied $(stat -c%s "$OUT") bytes"

echo "### erase AVB footer (never truncate-to-grow)"
avbtool erase_footer --image "$OUT"
echo "size after erase: $(stat -c%s "$OUT")"

echo "### mount rw and append the two context lines"
sudo mkdir -p "$MNT"
sudo mount -o loop,rw "$OUT" "$MNT"
trap 'sudo umount "$MNT" 2>/dev/null || true' EXIT

BEFORE=$(sudo stat -c%s "$MNT/$VPC")
if sudo grep -q "kmosver" "$MNT/$VPC"; then
  echo "entries already present, skipping append"
else
  sudo tee -a "$MNT/$VPC" >/dev/null <<'EOF'

# KeyMint OS-version redirect (see docs/KEYMINT_OS_VERSION_FIX.md).
# libkeymint.so is patched to read these instead of ro.build.version.*.
# vendor_default_prop is used because sepolicy grants hal_keymint_default
# read on it, while unlabeled props fall back to default_prop, which it
# is NOT allowed to read -> property_get returns empty -> early_hal fails.
ro.vendor.kmosver     u:object_r:vendor_default_prop:s0
ro.vendor.kmospatch   u:object_r:vendor_default_prop:s0
EOF
fi
AFTER=$(sudo stat -c%s "$MNT/$VPC")
echo "vendor_property_contexts: $BEFORE -> $AFTER bytes"

echo "### verify in-place edit preserved label/mode/owner"
sudo ls -lZ "$MNT/$VPC" 2>/dev/null || sudo stat -c '%A %u:%g %C' "$MNT/$VPC" 2>/dev/null || true
sudo getfattr -n security.selinux --only-values "$MNT/$VPC" 2>/dev/null; echo
sudo tail -4 "$MNT/$VPC"

sync
sudo umount "$MNT"; trap - EXIT

echo "### rebuild hashtree footer with stock params"
avbtool add_hashtree_footer \
  --image "$OUT" \
  --partition_name vendor \
  --partition_size "$PART_SIZE" \
  --hash_algorithm sha256 \
  --salt "$SALT"

echo "### verify the FINISHED artifact (trust nothing that 'succeeded')"
cd /tmp && rm -f vendor.img && cp "$OUT" vendor.img
avbtool info_image --image vendor.img | grep -E "Partition Name|Image Size|Salt|Root Digest|FEC size|Algorithm"
avbtool verify_image --image vendor.img && echo "AVB VERIFY: PASS"
echo "--- read the context lines back out of the finished image ---"
debugfs -R "cat /$VPC" vendor.img 2>/dev/null | tail -3
echo "--- confirm patched lib + props survived ---"
debugfs -R "cat /build.prop" vendor.img 2>/dev/null | grep kmos
rm -f /tmp/vendor.img
ls -la "$OUT"
