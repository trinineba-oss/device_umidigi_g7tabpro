#!/usr/bin/env python3
"""
Build the KeyMint-redirect vendor image AND its matching vbmeta_vendor, without
mounting anything and without root. Output: <outdir>/vendor.img and
<outdir>/vbmeta_vendor.img, ready to flash together.

What it changes (see docs/VENDOR_SELINUX_PROP_FIX.md):
  lib64/libkeymint.so                      2 OS-version property names redirected
  bin/hw/...keymint-service.trustkernel    3 root-of-trust property names redirected
  build.prop                               4 vendor-owned props defined (honest values)
  etc/selinux/vendor_property_contexts     those 4 labelled vendor_mtk_default_prop

Why vbmeta_vendor has to be rebuilt too
---------------------------------------
On this device fstab mounts /vendor with plain `avb` (no avb_keys), so dm-verity
for vendor is set up from the hashtree descriptor inside **vbmeta_vendor**, not
from the footer inside the vendor image. Rebuilding only the vendor footer would
leave vbmeta_vendor holding the stock root digest; with veritymode=enforcing the
first read of a changed block fails and the device bootloops. So the new
vbmeta_vendor carries the new descriptor. It is signed with the AOSP test key --
the OEM key is not available -- which vbmeta_a's chain descriptor will reject;
an unlocked bootloader tolerates that (the same way it already tolerates the
Magisk-patched boot, which is also a chained partition).

Why no mount
------------
  * Both binaries keep their exact size, so the patched bytes are written straight
    into the file's existing data blocks (block map from `debugfs blocks`).
  * Both text files have unused, zeroed space in their last allocated block; the
    additions are written there and only i_size is updated (`debugfs sif`).
Nothing is allocated, no inode is replaced, so owner/mode/SELinux label/times
are untouched. It refuses if an addition would not fit in the existing slack.
`e2fsck -fn` must pass before the footer is rebuilt.
"""
import argparse
import hashlib
import importlib.util
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

LIB = "/lib64/libkeymint.so"
SVC = "/bin/hw/android.hardware.security.keymint-service.trustkernel"
BP = "/build.prop"
VPC = "/etc/selinux/vendor_property_contexts"
MARKER = b"ro.vendor.kmosver"


def load_patcher():
    spec = importlib.util.spec_from_file_location(
        "patch_keymint_binary", os.path.join(HERE, "patch-keymint-binary.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run(cmd, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"FATAL: {' '.join(cmd)}\n{r.stdout}{r.stderr}")
    return r


def die(msg):
    sys.exit(f"FATAL: {msg}")


def sha(b):
    return hashlib.sha256(b).hexdigest()


def debugfs(img, req, write=False):
    cmd = ["debugfs"] + (["-w"] if write else []) + ["-R", req, img]
    r = run(cmd)
    return r.stdout


def dump(img, path, tmp):
    run(["debugfs", "-R", f"dump {path} {tmp}", img])
    with open(tmp, "rb") as f:
        data = f.read()
    os.remove(tmp)
    return data


def block_map(img, path):
    return [int(x) for x in debugfs(img, f"blocks {path}").split()]


def file_size(img, path):
    m = re.search(r"Size:\s+(\d+)", debugfs(img, f"stat {path}"))
    if not m:
        die(f"cannot stat {path}")
    return int(m.group(1))


def write_at(img, blocks, bs, file_off, data):
    """Write data at a file offset through the file's own block map."""
    with open(img, "r+b") as f:
        pos = 0
        while pos < len(data):
            bi, bo = divmod(file_off + pos, bs)
            if bi >= len(blocks):
                die("write would run past the file's allocated blocks")
            n = min(bs - bo, len(data) - pos)
            f.seek(blocks[bi] * bs + bo)
            f.write(data[pos:pos + n])
            pos += n


def diff_runs(a, b):
    runs, i = [], 0
    while i < len(a):
        if a[i] != b[i]:
            j = i
            while j < len(a) and a[j] != b[j]:
                j += 1
            runs.append((i, j))
            i = j
        else:
            i += 1
    return runs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock", required=True, help="stock vendor image WITH its AVB footer")
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--avbtool", default="avbtool")
    ap.add_argument("--key", required=True, help="PEM key to sign vbmeta_vendor")
    ap.add_argument("--fec-dir", help="directory containing the fec binary")
    ap.add_argument("--kmosver", default="13")
    ap.add_argument("--kmospatch", default="2025-09-05")
    ap.add_argument("--kmvbstate", default="orange")
    ap.add_argument("--kmvbdevst", default="unlocked")
    a = ap.parse_args()

    if a.fec_dir:
        os.environ["PATH"] = a.fec_dir + os.pathsep + os.environ["PATH"]
    avb = ([sys.executable, a.avbtool] if a.avbtool.endswith(".py") else [a.avbtool])
    for t in ("debugfs", "e2fsck", "dumpe2fs"):
        if not shutil.which(t):
            die(f"{t} not on PATH")
    if not shutil.which("fec"):
        die("fec not on PATH (use --fec-dir); stock vendor carries FEC")
    patcher = load_patcher()

    os.makedirs(a.outdir, exist_ok=True)
    img = os.path.join(a.outdir, "vendor.img")
    tmp = os.path.join(a.outdir, ".dump.tmp")

    # --- stock footer parameters -------------------------------------------
    info = run(avb + ["info_image", "--image", a.stock]).stdout
    part_size = int(re.search(r"Image size:\s+(\d+)", info).group(1))
    salt = re.search(r"Salt:\s+([0-9a-f]+)", info).group(1)
    stock_digest = re.search(r"Root Digest:\s+([0-9a-f]+)", info).group(1)
    pname = re.search(r"Partition Name:\s+(\S+)", info).group(1)
    if pname != "vendor":
        die(f"stock image footer is for {pname!r}, not vendor")
    print(f"stock: partition_size={part_size} salt={salt[:16]}... root={stock_digest[:16]}...")

    shutil.copyfile(a.stock, img)
    run(avb + ["erase_footer", "--image", img])

    hdr = run(["dumpe2fs", "-h", img]).stdout
    if "shared_blocks" in hdr:
        die("filesystem has shared_blocks; libext2fs cannot write it")
    bs = int(re.search(r"Block size:\s+(\d+)", hdr).group(1))

    # --- 1+2: binaries, same size, bytes written through the block map ------
    for path, preset in ((LIB, "libkeymint"), (SVC, "keymint-service")):
        orig = dump(img, path, tmp)
        plan = patcher.check(orig, patcher.PRESETS[preset])
        new = patcher.apply(orig, plan)
        patcher.verify(orig, new, plan)
        blocks = block_map(img, path)
        for s, e in diff_runs(orig, new):
            write_at(img, blocks, bs, s, new[s:e])
        if dump(img, path, tmp) != new:
            die(f"{path}: read-back does not match the patched bytes")
        print(f"patched {path}: {len(plan)} name(s), {sum(e - s for s, e in diff_runs(orig, new))} bytes")

    # --- 3+4: text appends into existing slack ------------------------------
    bp_add = (
        "\n# KeyMint property redirect -- values the device ACTUALLY reports, so an\n"
        "# unmodified GSI boots. See docs/VENDOR_SELINUX_PROP_FIX.md.\n"
        f"ro.vendor.kmosver={a.kmosver}\n"
        f"ro.vendor.kmospatch={a.kmospatch}\n"
        f"ro.vendor.kmvbstate={a.kmvbstate}\n"
        f"ro.vendor.kmvbdevst={a.kmvbdevst}\n"
        "# ro.vendor.kmvbdig intentionally undefined: the bootloader publishes no\n"
        "# vbmeta digest; an undefined prop reads empty, which is the honest value.\n"
    ).encode()
    vpc_add = (
        "\n# KeyMint property redirect (docs/VENDOR_SELINUX_PROP_FIX.md).\n"
        "ro.vendor.kmosver     u:object_r:vendor_mtk_default_prop:s0\n"
        "ro.vendor.kmospatch   u:object_r:vendor_mtk_default_prop:s0\n"
        "ro.vendor.kmvbstate   u:object_r:vendor_mtk_default_prop:s0\n"
        "ro.vendor.kmvbdevst   u:object_r:vendor_mtk_default_prop:s0\n"
    ).encode()
    for path, add in ((BP, bp_add), (VPC, vpc_add)):
        orig = dump(img, path, tmp)
        if MARKER in orig:
            print(f"{path}: already carries the redirect, skipping")
            continue
        if not orig.endswith(b"\n"):
            add = b"\n" + add
        blocks = block_map(img, path)
        size = file_size(img, path)
        if size != len(orig):
            die(f"{path}: size mismatch {size} vs dump {len(orig)}")
        cap = len(blocks) * bs
        if size + len(add) > cap:
            die(f"{path}: addition ({len(add)}) exceeds slack ({cap - size})")
        write_at(img, blocks, bs, size, add)
        debugfs(img, f"sif {path} size {size + len(add)}", write=True)
        if dump(img, path, tmp) != orig + add:
            die(f"{path}: read-back does not match")
        print(f"appended {path}: {size} -> {size + len(add)} bytes (slack was {cap - size})")

    r = run(["e2fsck", "-fn", img], check=False)
    if r.returncode != 0:
        die(f"e2fsck reports problems:\n{r.stdout}{r.stderr}")
    print("e2fsck -fn: clean")

    # --- 5: footer + matching vbmeta_vendor --------------------------------
    run(avb + ["add_hashtree_footer", "--image", img, "--partition_name", "vendor",
               "--partition_size", str(part_size), "--hash_algorithm", "sha256",
               "--salt", salt])
    run(avb + ["verify_image", "--image", img])
    new_digest = re.search(r"Root Digest:\s+([0-9a-f]+)",
                           run(avb + ["info_image", "--image", img]).stdout).group(1)

    vbm = os.path.join(a.outdir, "vbmeta_vendor.img")
    run(avb + ["make_vbmeta_image", "--output", vbm, "--algorithm", "SHA256_RSA2048",
               "--key", a.key, "--rollback_index", "0",
               "--include_descriptors_from_image", img])
    vinfo = run(avb + ["info_image", "--image", vbm]).stdout
    vdig = re.search(r"Root Digest:\s+([0-9a-f]+)", vinfo).group(1)
    if vdig != new_digest:
        die("vbmeta_vendor descriptor does not match vendor.img footer")
    # verify_image follows the descriptor to <outdir>/vendor.img by partition name
    run(avb + ["verify_image", "--image", vbm])

    with open(os.path.join(a.outdir, "MANIFEST.txt"), "w") as m:
        for f in ("vendor.img", "vbmeta_vendor.img"):
            p = os.path.join(a.outdir, f)
            h = hashlib.sha256()
            with open(p, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            m.write(f"{h.hexdigest()}  {f}\n")
        m.write(f"# stock vendor root digest : {stock_digest}\n")
        m.write(f"# new vendor root digest   : {new_digest} (in vendor.img footer AND vbmeta_vendor)\n")
    print(f"vendor root digest {stock_digest[:16]}... -> {new_digest[:16]}... (vbmeta_vendor matches)")
    print(f"DONE: {img}\n      {vbm}")


if __name__ == "__main__":
    main()
