#!/bin/bash
#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Run this from the root of an LineageOS/AOSP source tree after syncing,
# with the tablet connected via adb (rooted) OR pointed at a directory
# containing the unpacked stock system/vendor images.
#
# Usage:
#   ./device/umidigi/g7tabpro/extract-files.sh          (pull from adb device)
#   ./device/umidigi/g7tabpro/extract-files.sh -i /path  (pull from firmware dump dir)

set -e

DEVICE=g7tabpro
VENDOR=umidigi

export DEVICE
export VENDOR

# Load extract-utils (LineageOS)
LINEAGE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../../.. && pwd)"
HELPER="${LINEAGE_ROOT}/tools/extract-utils/extract_utils.sh"
if [ ! -f "${HELPER}" ]; then
    echo "extract-utils not found at ${HELPER} — sync it into your LineageOS tree first."
    exit 1
fi
source "${HELPER}"

# This reads proprietary-files.txt in this same directory. That file is
# intentionally left mostly empty in this skeleton — see README Step 5.
# extract_utils will populate proprietary-files.txt for you when run
# against a connected/rooted device with the -n flag, or you can hand-curate
# it after inspecting /vendor and /system/vendor on the stock dump.

extract "${MY_DIR}/proprietary-files.txt" "${SRC:-adb}" "$@"

write_headers
write_makefiles "${MY_DIR}/proprietary-files.txt" true
finalize_all
