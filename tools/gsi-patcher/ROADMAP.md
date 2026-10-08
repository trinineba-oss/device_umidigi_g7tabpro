# Roadmap — known gaps, planned work

Recorded 2026-10-08. Ordered by priority. Nothing here is started.

## 1. EROFS GSIs

Today any EROFS image is refused right after extraction ("this image uses
EROFS, and the patcher only handles ext4 GSIs"). Recent Google builds (e.g.
`aosp_arm64-exp-CP3A.260905.010`) and many ROMs ship EROFS.

**Chosen approach: edit EROFS in place.** Every patch this tool makes is
length-preserving — release `16`→`13`, the patch date keeps its format, the init
fix is 3 bytes, the sepolicy fix turns one `(` into `;` — so no rebuild is needed:
locate the file, decompress only the affected cluster, edit, recompress into the
same physical space, then rehash + re-sign as for ext4. Keeps the app's
properties: on-device, no root, same-size output, fast.

Stages:
1. **Reader** — superblock, compact/extended inodes, directories, inline and
   tail-packed data, compressed indexes; list/extract files. Test against real
   `mkfs.erofs` output. Alone, this lets "Check" work on EROFS GSIs.
2. **Edit uncompressed + LZ4 files** — needs an LZ4 block compressor whose output
   EROFS's decompressor accepts. Refuse cleanly if a recompressed cluster no
   longer fits its old space (expected rare: edits are a few bytes).
3. **MicroLZMA** images, if real GSIs need them (`org.tukaani:xz` has an LZMA
   encoder).

Features to detect and refuse precisely until supported: fragments (packed
inode), dedupe, big pclusters, interlaced, zstd/deflate.

Before starting: confirm which features the CP3A image actually uses
(`dump.erofs` / superblock feature flags) — that decides whether stage 2 covers it.

Rejected alternatives:
- *Convert EROFS → ext4 in the app*: needs a full ext4 builder preserving every
  SELinux label, mode and capability, and the image roughly doubles (may not fit
  the system partition for a full flash; DSU would be fine).
- *PC-only script* (`erofs-utils` + `e2fsdroid`): quick, but not in the app;
  neither tool is installed on the workstation.

## 2. Hardware checks still owed

- v5.11 landscape layout (log beside controls) — not rotated on the tablet yet.
- v5.12 Google image zip + sparse expansion — tested in unit tests only.
- v5.10 4096-bit re-signing — tested against `avbtool`, not on a real 4096-bit GSI.
- v5.9 removing an installed-and-armed DSU.
- Crash-log (`expdb`) button.
- Vendor kit (`tools/build-vendor-kmfix.py` output) — flash + DSU an unpatched
  Infinity-X / crDroid / Lunaris.
