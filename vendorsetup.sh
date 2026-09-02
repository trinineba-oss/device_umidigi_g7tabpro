#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Lunch choices are declared via COMMON_LUNCH_CHOICES in AndroidProducts.mk
# instead of add_lunch_combo here — the latter is obsolete as of this
# manifest version and throws a (non-fatal) warning during envsetup.

# Make this recovery report Android 13, matching what the on-device TEE was
# provisioned under. See BoardConfig.mk (search REVIEW / CORRECTION,
# 2026-08-20 / 2026-09-02) for the full derivation and the GSI finding this
# generalises from (docs/KEYMINT_OS_VERSION_FIX.md): Keymaster/KeyMint embeds
# OS_VERSION in a key's authorization list at creation and validates it on
# use, so a recovery reporting 12 cannot unwrap FBE keys created under 13 —
# which matches the symptom exactly ("hung trying to decrypt /data, stuck on
# the splash, no crash").
#
# Must be exported HERE, not set in BoardConfig.mk: build/make/core/envsetup.mk
# includes version_defaults.mk (line 68) before board_config.mk (line 323), so
# version_defaults.mk's `ifndef PLATFORM_VERSION_LAST_STABLE` guard has
# already fired by the time BoardConfig.mk would be read. vendorsetup.sh runs
# earlier still — sourced by envsetup.sh before lunch/mka — so the export is
# already "defined" when the ifndef check runs.
#
# This is PLATFORM_VERSION_LAST_STABLE specifically, not PLATFORM_VERSION,
# because build/make/tools/buildinfo.sh reads the former for
# ro.build.version.release (the value the TEE actually compares) and the
# latter only for ro.build.version.release_or_codename — but for a REL
# codename build (this one; PLATFORM_VERSION_CODENAME.SP2A=REL) the ifndef
# block derives PLATFORM_VERSION from PLATFORM_VERSION_LAST_STABLE too, so one
# export fixes both. PLATFORM_SDK_VERSION (32) is deliberately left alone —
# the framework should keep behaving as its real API level, exactly as with
# the GSI version patch.
export PLATFORM_VERSION_LAST_STABLE=13
