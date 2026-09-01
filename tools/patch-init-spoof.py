#!/usr/bin/env python3
"""
patch-init-spoof.py -- neutralise selected entries in a GSI init's property
spoof table, leaving the rest of the binary (and the rest of the spoofing)
exactly as it was.

Background
----------
Some GSI inits carry a hardcoded ~37-entry property table that runs inside
init's property-loading routine on every boot. It is a Play-Integrity spoof:
it forces ro.boot.vbmeta.device_state=locked, ro.boot.verifiedbootstate=green,
ro.build.tags=release-keys, every *.build.type=user, and so on.

On the UMIDIGI G7 Tab Pro the vendor KeyMint service binary
(/vendor/bin/hw/android.hardware.security.keymint-service.trustkernel) reads
exactly three properties for its root of trust:

    ro.boot.vbmeta.device_state
    ro.boot.vbmeta.digest
    ro.boot.verifiedbootstate

This device's bootloader publishes none of them. An init that fabricates them
hands the TA a root of trust the device never had. An init that leaves them
alone -- like Project CiRCLE's, which boots here -- lets the service see them
absent, which is the configuration that works.

Method
------
Each spoofed name is materialised by an `adrp`/`add` pair. This nudges the
`add` immediate three bytes forward so the pointer skips the "ro." prefix:
the entry then writes to an inert, unread name ("boot.verifiedbootstate")
while every legitimate reference to the real name is left untouched.

One instruction per property. Size unchanged, so an in-place image patch still
works with no relocation.

Safety
------
A target is only patched when its referencing instruction lies inside the spoof
table function -- identified structurally as the function that also
materialises the literals "locked" and "green". References outside it are
legitimate (AOSP's own kernel-cmdline handling) and are reported, never
touched. If a property is referenced more than once inside the spoof function,
the script refuses rather than guess.

Usage
-----
    patch-init-spoof.py <init-in> <init-out> [--props a,b,c] [--objdump PATH]

Default props are the three the vendor KeyMint service reads. Use
--props ro.crypto.state to target the TrustKernel filesystem trigger instead
(tested on hardware 2026-09-01: not sufficient on its own).
"""

import re
import sys
import bisect
import subprocess
import collections

OBJDUMP_DEFAULT = ("/home/neba/prebuilts/clang/host/linux-x86/"
                   "clang-r416183b/bin/llvm-objdump")

# What the vendor KeyMint service binary actually reads for its root of trust.
DEFAULT_PROPS = [
    "ro.boot.vbmeta.device_state",
    "ro.boot.vbmeta.digest",
    "ro.boot.verifiedbootstate",
]

FINGERPRINT = ["locked", "green"]   # literals unique to the spoof table

RE_ADRP = re.compile(r'^\s*([0-9a-f]+):\s+adrp\s+(x\d+), (0x[0-9a-f]+)')
RE_ADD = re.compile(r'^\s*([0-9a-f]+):\s+add\s+(x\d+), (x\d+), #(\d+)')
RE_BL = re.compile(r'\bbl\s+(0x[0-9a-f]+)')


def die(msg):
    sys.exit("FATAL: " + msg)


def string_offsets(data, wanted):
    """Offsets of NUL-terminated strings exactly equal to each `wanted`."""
    out = collections.defaultdict(list)
    for w in wanted:
        needle = w.encode() + b'\0'
        i = data.find(needle)
        while i != -1:
            if i == 0 or data[i - 1] == 0:      # a real string start
                out[w].append(i)
            i = data.find(needle, i + 1)
    return out


def disassemble(path, objdump):
    try:
        r = subprocess.run([objdump, '-d', '--no-show-raw-insn', path],
                           capture_output=True, text=True, check=True)
    except FileNotFoundError:
        die("llvm-objdump not found -- pass --objdump PATH")
    except subprocess.CalledProcessError as e:
        die("objdump failed: " + (e.stderr or '')[:200])
    return r.stdout.splitlines()


def analyse(lines, targets):
    """-> {addr: [(pc, imm, rd, rn)]}, sorted bl targets (function entries)."""
    adrp, hits, bl = {}, collections.defaultdict(list), set()
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
            if addr in targets:
                hits[addr].append((int(m.group(1), 16), int(m.group(4)),
                                   int(m.group(2)[1:]), int(m.group(3)[1:])))
    return hits, sorted(bl)


def enclosing(bl, pc):
    i = bisect.bisect_right(bl, pc) - 1
    return bl[i] if i >= 0 else None


def encode_add(imm, rn, rd):
    if not 0 <= imm < 4096:
        die("immediate %d out of range for a single add" % imm)
    return 0x91000000 | (imm << 10) | (rn << 5) | rd


def main():
    argv = sys.argv[1:]
    objdump, props = OBJDUMP_DEFAULT, DEFAULT_PROPS
    if '--objdump' in argv:
        i = argv.index('--objdump')
        objdump = argv[i + 1]
        del argv[i:i + 2]
    if '--props' in argv:
        i = argv.index('--props')
        props = [p.strip() for p in argv[i + 1].split(',') if p.strip()]
        del argv[i:i + 2]
    if len(argv) != 2:
        die("usage: %s <init-in> <init-out> [--props a,b,c] [--objdump PATH]"
            % sys.argv[0])
    src, dst = argv

    data = open(src, 'rb').read()
    offs = string_offsets(data, props + FINGERPRINT)

    for lit in FINGERPRINT:
        if not offs[lit]:
            die("no %r literal -- this init has no spoof table, so there is "
                "nothing to patch (and it probably already boots here)" % lit)

    lines = disassemble(src, objdump)
    all_targets = {a for v in offs.values() for a in v}
    hits, bl = analyse(lines, all_targets)

    def fns_for(lit):
        return {enclosing(bl, pc) for a in offs[lit] for pc, *_ in hits.get(a, [])}

    spoof_fns = fns_for(FINGERPRINT[0]) & fns_for(FINGERPRINT[1])
    spoof_fns.discard(None)
    if not spoof_fns:
        die("found the fingerprint literals but no single function references "
            "both -- refusing to guess where the spoof table is")
    print("spoof table function(s): "
          + ", ".join(hex(f) for f in sorted(spoof_fns)))

    out = bytearray(data)
    patched, skipped = [], []

    for p in props:
        if not offs[p]:
            skipped.append((p, "string not present"))
            continue
        inside, outside = [], []
        for a in offs[p]:
            for pc, imm, rd, rn in hits.get(a, []):
                (inside if enclosing(bl, pc) in spoof_fns else outside).append(
                    (pc, imm, rd, rn))
        if outside:
            print("    %-30s legitimate ref(s) left alone: %s"
                  % (p, ", ".join(hex(pc) for pc, *_ in sorted(outside))))
        if not inside:
            skipped.append((p, "not referenced inside the spoof table"))
            continue
        if len(inside) > 1:
            die("%s has %d references inside the spoof table -- refusing to "
                "patch blind" % (p, len(inside)))

        pc, imm, rd, rn = inside[0]
        have = int.from_bytes(out[pc:pc + 4], 'little')
        want = encode_add(imm, rn, rd)
        if have != want:
            die("instruction at %s reads 0x%08x, expected 0x%08x -- refusing"
                % (hex(pc), have, want))
        # Skip "ro." so the entry writes to an inert, unread name.
        new_off = offs[p][0] + 3
        end = data.find(b'\0', new_off)
        if end <= new_off:
            die("redirect target for %s is not a usable string" % p)
        out[pc:pc + 4] = encode_add(imm + 3, rn, rd).to_bytes(4, 'little')
        patched.append((p, pc, imm, data[new_off:end].decode('ascii', 'replace')))

    if not patched:
        die("nothing patched:\n  " + "\n  ".join("%s: %s" % s for s in skipped))

    if len(out) != len(data):
        die("length changed -- impossible, refusing")
    changed = [i for i in range(len(data)) if data[i] != out[i]]
    open(dst, 'wb').write(bytes(out))

    print()
    for p, pc, imm, newname in patched:
        print("  %-30s at %s: #%d -> #%d   now writes %r"
              % (p, hex(pc), imm, imm + 3, newname))
    for p, why in skipped:
        print("  %-30s SKIPPED (%s)" % (p, why))
    print("\nbytes changed: %d (%s)"
          % (len(changed), ", ".join(map(hex, changed))))
    print("size unchanged: %d -> %d" % (len(data), len(out)))
    print("wrote " + dst)


if __name__ == '__main__':
    main()
