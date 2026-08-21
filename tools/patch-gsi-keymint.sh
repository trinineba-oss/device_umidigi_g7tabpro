#!/bin/bash
#
# patch-gsi-keymint.sh — make an Android 14+ GSI bootable on a device whose
# TrustKernel TEE was provisioned under an older Android release.
#
# Rewrites the GSI's reported OS version in /system/build.prop so the vendor
# KeyMint HAL configures the TEE with a version it accepts, then rebuilds the
# AVB hashtree footer (mandatory — editing the image invalidates dm-verity).
#
# Background: device_umidigi_g7tabpro/docs/KEYMINT_OS_VERSION_FIX.md
#
# Usage:
#   sudo ./patch-gsi-keymint.sh <gsi.img|gsi.img.gz> [output.img.gz]
#
# Environment overrides:
#   TARGET_RELEASE   OS version to report      (default 13)
#   TARGET_PATCH     security patch to report  (default 2025-09-05)
#   AVBTOOL          path to avbtool.py
#   FEC_BIN_DIR      dir containing the 'fec' binary (AOSP out/host/linux-x86/bin)
#   KEEP_RAW         set to 1 to keep the uncompressed .img as well
#
# Requires: avbtool (+ fec on PATH), e2fsprogs, sudo (loop mount), gzip.

set -u
set -o pipefail

TARGET_RELEASE="${TARGET_RELEASE:-13}"
TARGET_PATCH="${TARGET_PATCH:-2025-09-05}"

die() { echo "FATAL: $*" >&2; exit 1; }
info() { echo "==> $*"; }

[ $# -ge 1 ] || die "usage: $0 <gsi.img|gsi.img.gz> [output.img.gz]"
SRC="$1"
[ -f "$SRC" ] || die "no such file: $SRC"

# ---------------------------------------------------------------- tool checks
AVBTOOL="${AVBTOOL:-}"
if [ -z "$AVBTOOL" ]; then
    for c in ./avbtool.py "$HOME/lineage-td/external/avb/avbtool.py" \
             ./external/avb/avbtool.py "$(command -v avbtool 2>/dev/null || true)"; do
        [ -n "$c" ] && [ -f "$c" ] && { AVBTOOL="$c"; break; }
    done
fi
[ -n "$AVBTOOL" ] || die "avbtool not found — set AVBTOOL=/path/to/avbtool.py"
case "$AVBTOOL" in *.py) AVB="python3 $AVBTOOL";; *) AVB="$AVBTOOL";; esac

# avbtool shells out to 'fec' when rebuilding a footer with FEC. It is NOT on
# the default PATH; it lives in an AOSP tree at out/host/linux-x86/bin/fec.
if ! command -v fec >/dev/null 2>&1; then
    for d in "${FEC_BIN_DIR:-}" "$HOME/lineage-td/out/host/linux-x86/bin" \
             ./out/host/linux-x86/bin; do
        [ -n "$d" ] && [ -x "$d/fec" ] && { export PATH="$d:$PATH"; break; }
    done
fi
command -v fec >/dev/null 2>&1 || \
    die "'fec' binary not found — set FEC_BIN_DIR=/path/to/out/host/linux-x86/bin"

for t in e2fsck resize2fs debugfs; do
    command -v "$t" >/dev/null 2>&1 || die "missing $t (install e2fsprogs)"
done
[ "$(id -u)" -eq 0 ] || die "must run as root (loop mount) — use sudo"

WORKDIR="$(mktemp -d)"
MNT="$WORKDIR/mnt"
cleanup() {
    mountpoint -q "$MNT" 2>/dev/null && umount "$MNT"
    rm -rf "$WORKDIR"
}
trap cleanup EXIT

# ------------------------------------------------------------------ decompress
case "$SRC" in
    *.gz) info "decompressing $SRC"
          IMG="$WORKDIR/system.img"
          gunzip -c "$SRC" > "$IMG" || die "gunzip failed" ;;
    *)    IMG="$WORKDIR/system.img"
          info "copying $SRC"
          cp "$SRC" "$IMG" || die "copy failed" ;;
esac

OUT="${2:-$(basename "${SRC%.img.gz}" .img)-osver${TARGET_RELEASE}.img.gz}"

# ------------------------------------------------- capture original AVB params
info "reading original AVB footer"
INFO="$($AVB info_image --image "$IMG" 2>&1)" || die "avbtool info_image failed"
grep -q "Footer version" <<<"$INFO" || die "no AVB footer found — is this a GSI system.img?"

get() { sed -n "s/^ *$1: *//p" <<<"$INFO" | head -1 | sed 's/ *$//'; }
PART_SIZE="$(get 'Image size' | sed 's/ bytes//')"
SALT="$(get 'Salt')"
ROLLBACK="$(get 'Rollback Index')"
ALGO="$(get 'Algorithm')"
PART_NAME="$(get 'Partition Name')"
FEC_ROOTS="$(get 'FEC num roots')"
[ -z "$FEC_ROOTS" ] && FEC_ROOTS=2

# preserve the descriptor props verbatim (they are metadata; the HAL does not
# read them, but keeping them makes the image indistinguishable from upstream)
mapfile -t PROPS < <(sed -n 's/^ *Prop: \(.*\) -> .\(.*\).$/--prop\n\1:\2/p' <<<"$INFO")

echo "    partition : ${PART_NAME:-system}"
echo "    size      : $PART_SIZE"
echo "    salt      : ${SALT:0:24}..."
echo "    rollback  : $ROLLBACK"
echo "    algorithm : $ALGO"
[ -n "$PART_SIZE" ] && [ -n "$SALT" ] || die "could not parse AVB parameters"

# signing key: GSIs are almost always signed with the AOSP testkey
KEY="${AVB_KEY:-}"
if [ -z "$KEY" ] && [ "$ALGO" != "NONE" ]; then
    for k in "$HOME/lineage-td/external/avb/test/data/testkey_rsa2048.pem" \
             ./external/avb/test/data/testkey_rsa2048.pem; do
        [ -f "$k" ] && { KEY="$k"; break; }
    done
    [ -n "$KEY" ] || die "signing key not found — set AVB_KEY=/path/to/testkey_rsa2048.pem"
fi

# ------------------------------------------------------------- erase old footer
# NOTE: never just truncate/extend the file. The footer lives in the final
# bytes, so growing the image strands it and avbtool reports
# "Given image does not look like a vbmeta image".
info "erasing old AVB footer"
$AVB erase_footer --image "$IMG" || die "erase_footer failed"

# ------------------------------------------------------- make it mountable RW
# GSI images ship with shared_blocks (block dedup) and almost no free space,
# so they refuse a read-write mount until the blocks are unshared. That needs
# real headroom — be generous, we shrink it back afterwards.
info "growing + unsharing blocks (needed for rw mount)"
truncate -s +2G "$IMG"
e2fsck -fy "$IMG" >/dev/null 2>&1
resize2fs "$IMG" >/dev/null 2>&1
e2fsck -E unshare_blocks -fy "$IMG" >/dev/null 2>&1
e2fsck -fy "$IMG" >/dev/null 2>&1

mkdir -p "$MNT"
mount -o loop,rw "$IMG" "$MNT" || die "loop mount failed"

BP="$MNT/system/build.prop"
[ -f "$BP" ] || { BP="$MNT/build.prop"; }
[ -f "$BP" ] || die "build.prop not found in image"

echo "--- before ---"
grep -E '^ro\.build\.version\.(release|release_or_codename|security_patch|sdk)=' "$BP" || true

# The KeyMint HAL reports ro.build.version.release to the TEE, which must match
# what the TEE was told at boot. sdk is deliberately NOT touched: the framework
# keeps behaving as its real API level.
sed -i "s/^ro\.build\.version\.release=.*/ro.build.version.release=$TARGET_RELEASE/" "$BP"
sed -i "s/^ro\.build\.version\.release_or_codename=.*/ro.build.version.release_or_codename=$TARGET_RELEASE/" "$BP"
sed -i "s/^ro\.build\.version\.security_patch=.*/ro.build.version.security_patch=$TARGET_PATCH/" "$BP"

echo "--- after ---"
grep -E '^ro\.build\.version\.(release|release_or_codename|security_patch|sdk)=' "$BP" || true

# hard-assert the edits actually landed; a silent sed miss would produce an
# image that looks fine and still hangs at the splash
grep -qE "^ro\.build\.version\.release=${TARGET_RELEASE}$" "$BP" \
    || die "release edit did not apply"
grep -qE "^ro\.build\.version\.security_patch=${TARGET_PATCH}$" "$BP" \
    || die "security_patch edit did not apply"

sync
umount "$MNT"

# ------------------------------------------------------------------- shrink back
info "shrinking filesystem"
e2fsck -fy "$IMG" >/dev/null 2>&1
resize2fs -M "$IMG" >/dev/null 2>&1
e2fsck -fy "$IMG" >/dev/null 2>&1

FS_SIZE="$(stat -c %s "$IMG")"
# unshare_blocks permanently inflates the image (dedup is undone), so the
# original partition size may no longer fit. Grow it if needed.
NEED=$(( FS_SIZE + 200 * 1024 * 1024 ))
if [ "$NEED" -gt "$PART_SIZE" ]; then
    PART_SIZE=$(( NEED / 4096 * 4096 ))
    info "image grew past original partition size; using $PART_SIZE"
fi

# ------------------------------------------------------------- rebuild footer
info "rebuilding AVB hashtree footer"
set -- add_hashtree_footer --image "$IMG" \
      --partition_name "${PART_NAME:-system}" \
      --partition_size "$PART_SIZE" \
      --salt "$SALT" \
      --hash_algorithm sha256 \
      --fec_num_roots "$FEC_ROOTS" \
      --rollback_index "${ROLLBACK:-0}"
[ "$ALGO" != "NONE" ] && set -- "$@" --algorithm "$ALGO" --key "$KEY"
[ ${#PROPS[@]} -gt 0 ] && set -- "$@" "${PROPS[@]}"

$AVB "$@" || die "add_hashtree_footer failed"

# ------------------------------------------------------------------- verify
info "verifying finished image"
# verify_image insists the file be named after the partition, else it throws a
# confusing FileNotFoundError while hunting for the hashtree target
VERIFY_LINK="$WORKDIR/${PART_NAME:-system}.img"
[ "$VERIFY_LINK" != "$IMG" ] && ln -sf "$IMG" "$VERIFY_LINK" || VERIFY_LINK="$IMG"
if [ "$ALGO" != "NONE" ]; then
    $AVB verify_image --image "$VERIFY_LINK" --key "$KEY" 2>&1 | sed 's/^/    /'
else
    $AVB info_image --image "$IMG" 2>&1 | head -5 | sed 's/^/    /'
fi

# Verify the *finished image*, not the build log. A step exiting 0 is not
# evidence the result is correct — read the props back out of the image.
echo "--- props inside the finished image ---"
debugfs -R "cat /system/build.prop" "$IMG" 2>/dev/null \
    | grep -E '^ro\.build\.version\.(release|security_patch|sdk)=' | sed 's/^/    /'
debugfs -R "cat /system/build.prop" "$IMG" 2>/dev/null \
    | grep -qE "^ro\.build\.version\.release=${TARGET_RELEASE}$" \
    || die "verification failed: release prop not present in finished image"

# ------------------------------------------------------------------ compress
info "compressing to $OUT"
gzip -1 -c "$IMG" > "$OUT" || die "gzip failed"
[ "${KEEP_RAW:-0}" = "1" ] && { cp "$IMG" "${OUT%.gz}"; echo "    raw: ${OUT%.gz}"; }

echo
echo "DONE: $OUT  ($(stat -c %s "$OUT") bytes)"
echo
echo "Install with DSU Sideloader, then confirm:"
echo "  adb shell getprop sys.boot_completed        # 1"
echo "  adb shell \"mount | grep ' /data '\"          # mounted"
echo "  adb shell getprop init.svc.zygote           # running"
echo "  adb logcat -d | grep 'generateKey returned' # -67 (good), not -64"
