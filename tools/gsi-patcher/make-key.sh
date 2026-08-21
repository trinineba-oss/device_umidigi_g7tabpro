#!/bin/bash
# Produce the PKCS#8 DER form of the AOSP AVB test key that GSIs are signed with.
#
# Java's KeyFactory only accepts PKCS#8; the upstream file is PKCS#1
# ("BEGIN RSA PRIVATE KEY"), hence the conversion.
#
# The key is not secret -- it is published in AOSP at
# external/avb/test/data/testkey_rsa2048.pem -- but it is still private-key
# material, so it is generated here rather than committed.
set -euo pipefail

SRC="${1:-$HOME/lineage-td/external/avb/test/data/testkey_rsa2048.pem}"
[ -f "$SRC" ] || {
    echo "usage: $0 [path/to/testkey_rsa2048.pem]" >&2
    echo "  default: \$HOME/lineage-td/external/avb/test/data/testkey_rsa2048.pem" >&2
    echo "  upstream: https://android.googlesource.com/platform/external/avb/+/refs/heads/main/test/data/testkey_rsa2048.pem" >&2
    exit 1
}

HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/app/src/main/res/raw"
openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER \
    -in "$SRC" -out "$HERE/app/src/main/res/raw/testkey_rsa2048.der"
cp "$HERE/app/src/main/res/raw/testkey_rsa2048.der" "$HERE/testkey_rsa2048.pkcs8.der"
echo "wrote app/src/main/res/raw/testkey_rsa2048.der (for the APK)"
echo "wrote testkey_rsa2048.pkcs8.der (for the CLI --key flag)"
