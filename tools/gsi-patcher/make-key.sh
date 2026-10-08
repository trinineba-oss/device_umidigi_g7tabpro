#!/bin/bash
# Produce PKCS#8 DER forms of the AOSP AVB test keys GSIs are signed with.
#
# GSIs ship signed with either a 2048- or a 4096-bit key, and a re-signed
# vbmeta must keep the same size (the patcher edits it in place), so the app
# carries one key per size: 2048, 4096 and 8192.
#
# Java's KeyFactory only accepts PKCS#8; the upstream files are PKCS#1
# ("BEGIN RSA PRIVATE KEY"), hence the conversion.
#
# The keys are not secret -- they are published in AOSP under
# external/avb/test/data/ -- but they are private-key material, so they are
# generated here rather than committed.
set -euo pipefail

DIR="${1:-}"
if [ -z "$DIR" ]; then
    for d in "$HOME/lineage-td/external/avb/test/data" "$HOME/external/avb/test/data"; do
        [ -f "$d/testkey_rsa2048.pem" ] && { DIR="$d"; break; }
    done
fi
[ -n "$DIR" ] && [ -f "$DIR/testkey_rsa2048.pem" ] || {
    echo "usage: $0 [dir containing testkey_rsa{2048,4096,8192}.pem]" >&2
    echo "  upstream: https://android.googlesource.com/platform/external/avb/+/refs/heads/main/test/data/" >&2
    exit 1
}

HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/app/src/main/res/raw"
for bits in 2048 4096 8192; do
    src="$DIR/testkey_rsa$bits.pem"
    [ -f "$src" ] || { echo "FATAL: missing $src" >&2; exit 1; }
    openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER \
        -in "$src" -out "$HERE/app/src/main/res/raw/testkey_rsa$bits.der"
    cp "$HERE/app/src/main/res/raw/testkey_rsa$bits.der" "$HERE/testkey_rsa$bits.pkcs8.der"
    echo "wrote testkey_rsa$bits (app/src/main/res/raw/ and ./testkey_rsa$bits.pkcs8.der)"
done
