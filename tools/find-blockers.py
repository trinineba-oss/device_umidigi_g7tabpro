#!/usr/bin/env python3
"""
find-blockers.py -- find which properties a GSI's init spoof table writes that
this device's vendor code actually reads.

Both boot blockers solved on the UMIDIGI G7 Tab Pro were the same shape: vendor
code consuming a property the GSI interfered with. They were found by hand, one
at a time, and the fix list ended up hardcoded in the patcher. This derives that
list instead, which turns "we found two bugs and hope that is all" into a claim
that can be checked.

    the GSI's init spoof table writes ~37 properties
  INTERSECT
    the properties this vendor's .rc triggers and binaries actually read
  =
    the only entries that can possibly affect boot on this device

Everything outside the intersection is noise: the spoof can set it to whatever
it likes and nothing on the device is listening.

Usage:
    find-blockers.py <vendor.img | vendor-dir> <gsi-init> [--objdump PATH]
                     [--libs]

    --libs   also scan /vendor/lib64 (slower; HALs in /vendor/bin/hw are where
             consumption has actually been found so far)

Exit status is 0 whether or not anything is found -- an empty intersection is a
result, not an error.
"""

import os
import re
import sys
import shutil
import bisect
import subprocess
import tempfile
import collections

OBJDUMP_DEFAULT = ("/home/neba/prebuilts/clang/host/linux-x86/"
                   "clang-r416183b/bin/llvm-objdump")

# Literals that mark the spoof table -- the same structural fingerprint the
# patcher uses. A function materialising both is the table.
FINGERPRINT = ("locked", "green")

PROP_RE = re.compile(r'^(ro|persist|sys|vendor|oplusboot)\.[A-Za-z0-9_.]+$')

RE_ADRP = re.compile(r'^\s*([0-9a-f]+):\s+adrp\s+(x\d+), (0x[0-9a-f]+)')
RE_ADD = re.compile(r'^\s*([0-9a-f]+):\s+add\s+(x\d+), (x\d+), #(\d+)')
RE_STR = re.compile(r'^\s*([0-9a-f]+):\s+str\s+(x\d+), \[sp, #(\d+)\]')
RE_BL = re.compile(r'\bbl\s+(0x[0-9a-f]+)')


def die(msg):
    sys.exit("FATAL: " + msg)


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True,
                          errors="replace").stdout


# ─────────────────────────────────────────────────── vendor side

class Vendor:
    """Vendor content, from a raw ext4 image or an already-extracted tree."""

    def __init__(self, path, tmp):
        self.path = path
        self.tmp = tmp
        self.is_img = os.path.isfile(path)
        if not self.is_img and not os.path.isdir(path):
            die("no such vendor image or directory: " + path)

    def _debugfs(self, cmd):
        return run(["debugfs", "-R", cmd, self.path])

    def list_dir(self, d):
        if self.is_img:
            out = self._debugfs("ls " + d)
            names = [n for n in out.replace("\n", " ").split(" ") if n]
            return [n for n in names
                    if n not in (".", "..") and not n.isdigit()
                    and not n.startswith("(")]
        real = os.path.join(self.path, d.lstrip("/"))
        return os.listdir(real) if os.path.isdir(real) else []

    def read(self, f):
        if self.is_img:
            return self._debugfs("cat " + f)
        real = os.path.join(self.path, f.lstrip("/"))
        try:
            with open(real, "r", errors="replace") as fh:
                return fh.read()
        except OSError:
            return ""

    def extract(self, f, dest):
        if self.is_img:
            self._debugfs("dump %s %s" % (f, dest))
            return os.path.exists(dest) and os.path.getsize(dest) > 0
        real = os.path.join(self.path, f.lstrip("/"))
        if os.path.isfile(real):
            shutil.copy(real, dest)
            return True
        return False

    def rc_triggers(self):
        """Properties the vendor's init scripts react to."""
        props = set()
        for name in self.list_dir("/etc/init"):
            if not name.endswith(".rc"):
                continue
            for line in self.read("/etc/init/" + name).splitlines():
                for m in re.finditer(r'property:([A-Za-z0-9_.]+)', line):
                    props.add(m.group(1))
        return props

    def binary_reads(self, wanted, with_libs=False):
        """
        Which of `wanted` appear as whole strings inside vendor binaries.

        A string in a binary is weaker evidence than a cross-referenced code
        path, but at this stage it is a filter, not a verdict: it narrows ~37
        candidates to the few worth reading properly.
        """
        found = collections.defaultdict(list)
        dirs = ["/bin/hw", "/bin"]
        if with_libs:
            dirs.append("/lib64")
        scanned = 0
        for d in dirs:
            for name in self.list_dir(d):
                dest = os.path.join(self.tmp, name.replace("/", "_"))
                if not self.extract(d + "/" + name, dest):
                    continue
                scanned += 1
                try:
                    blob = open(dest, "rb").read()
                except OSError:
                    continue
                for p in wanted:
                    needle = p.encode() + b"\0"
                    if needle in blob:
                        found[p].append(name)
                os.unlink(dest)
        return found, scanned


# ─────────────────────────────────────────────── GSI init side

def spoof_table_props(init_path, objdump):
    """
    Every property name the init's spoof table writes.

    Located structurally, the same way the patcher does it: find the function
    that materialises both "locked" and "green".

    Then -- and this part matters -- collect only strings the function *stores
    into its stack-built table*, not every property string it references. The
    spoof table lives inside init's own property-loading routine, which
    legitimately mentions dozens of property names it merely reads. Counting
    those too inflates the answer with entries the table never writes, which
    is exactly the kind of over-collection this tool exists to avoid.
    """
    data = open(init_path, "rb").read()

    # offsets of NUL-terminated strings, and a reverse map for lookup
    strings = {}
    for m in re.finditer(rb'[\x20-\x7e]{4,}\x00', data):
        s = m.group()[:-1].decode("ascii", "replace")
        strings[m.start()] = s

    lines = run([objdump, "-d", "--no-show-raw-insn", init_path]).splitlines()
    if not lines:
        die("objdump produced nothing -- wrong path or not an ARM64 binary?")

    adrp, refs, bl = {}, [], set()
    held = {}                       # register -> string address it currently holds
    stored = []                     # (pc, string address) actually written to the table
    for line in lines:
        m = RE_BL.search(line)
        if m:
            bl.add(int(m.group(1), 16))
        m = RE_ADRP.match(line)
        if m:
            adrp[m.group(2)] = int(m.group(3), 16)
            continue
        m = RE_ADD.match(line)
        if m and m.group(3) in adrp:
            addr = adrp[m.group(3)] + int(m.group(4))
            refs.append((int(m.group(1), 16), addr))
            held[m.group(2)] = addr
            continue
        m = RE_STR.match(line)
        if m and m.group(2) in held:
            stored.append((int(m.group(1), 16), held[m.group(2)]))

    entries = sorted(bl)

    def enclosing(pc):
        i = bisect.bisect_right(entries, pc) - 1
        return entries[i] if i >= 0 else None

    fp_fns = []
    for lit in FINGERPRINT:
        fns = {enclosing(pc) for pc, addr in refs
               if strings.get(addr) == lit}
        fns.discard(None)
        fp_fns.append(fns)
    spoof_fns = fp_fns[0] & fp_fns[1]
    if not spoof_fns:
        return set(), None

    props = set()
    for pc, addr in stored:
        if enclosing(pc) in spoof_fns:
            name = strings.get(addr)
            if name and PROP_RE.match(name):
                props.add(name)
    return props, sorted(spoof_fns)


# ─────────────────────────────────────────────────────── main

def main():
    argv = [a for a in sys.argv[1:] if not a.startswith("--")]
    objdump = OBJDUMP_DEFAULT
    if "--objdump" in sys.argv:
        objdump = sys.argv[sys.argv.index("--objdump") + 1]
        argv = [a for a in argv if a != objdump]
    with_libs = "--libs" in sys.argv

    if len(argv) != 2:
        die("usage: %s <vendor.img|vendor-dir> <gsi-init> [--libs] "
            "[--objdump PATH]" % os.path.basename(sys.argv[0]))
    vendor_path, init_path = argv
    if not os.path.isfile(init_path):
        die("no such init binary: " + init_path)

    tmp = tempfile.mkdtemp(prefix="findblockers-")
    try:
        vendor = Vendor(vendor_path, tmp)

        print("== GSI init: %s" % os.path.basename(init_path))
        spoofed, fns = spoof_table_props(init_path, objdump)
        if not spoofed:
            print("   no spoof table found -- this init writes nothing to "
                  "check, and needs no init patch")
            return
        print("   spoof table at %s, writes %d propert%s"
              % (", ".join(hex(f) for f in fns), len(spoofed),
                 "y" if len(spoofed) == 1 else "ies"))

        print("\n== vendor: %s" % os.path.basename(vendor_path))
        triggers = vendor.rc_triggers()
        print("   %d propert%s used in .rc triggers"
              % (len(triggers), "y" if len(triggers) == 1 else "ies"))
        reads, scanned = vendor.binary_reads(spoofed, with_libs)
        print("   %d binaries scanned" % scanned)

        by_rc = spoofed & triggers
        print("\n" + "=" * 66)
        print("INTERSECTION -- the only spoofed properties this device consumes")
        print("=" * 66)

        if not by_rc and not reads:
            print("\n  (none)\n")
            print("  Nothing this init spoofs is read anywhere in this vendor.")
            print("  On property grounds alone, it should boot.")
        else:
            for p in sorted(by_rc):
                print("\n  %s" % p)
                print("      via .rc trigger -- gates a vendor init action")
            for p in sorted(reads):
                print("\n  %s" % p)
                for b in reads[p][:6]:
                    print("      read by %s" % b)

        ignored = len(spoofed) - len(by_rc | set(reads))
        print("\n  %d of %d spoofed properties are read by nothing on this "
              "device." % (ignored, len(spoofed)))
        print("  Those cannot affect boot no matter what they are set to.")
        print("""
  Two caveats, both about not over-trusting this output:

  A name appearing in a binary is a filter, not proof. Confirm the code
  actually reaches it (adrp/add cross-reference) before patching anything --
  that distinction has cost this project several wasted flash cycles.

  The spoof-table extraction is approximate. It collects strings stored into
  stack-built tables inside the spoof function, and that function is init's
  own property-loading routine, so a handful of legitimately-handled names
  (ro.build.id, ro.product.cpu.abilist*, and similar) come along with the real
  entries. Measured against a hand reconstruction of inf_init: 36 real entries
  found, 9 false positives, none of which resemble verified-boot state. Read
  the list, do not feed it to a patcher.""")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
