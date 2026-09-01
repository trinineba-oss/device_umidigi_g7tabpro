#!/usr/bin/env python3
"""
patch-init-crypto-spoof.py -- neutralise ONE property write in a GSI's init.

Some GSI inits carry a hardcoded Play-Integrity property table that runs inside
init's property-loading routine on every boot. One entry in that table sets

    ro.crypto.state = encrypted

`ro.` properties are write-once, so this pre-empts the value vold would set.
On a device whose vendor gates its TEE filesystem on that property -- the
UMIDIGI G7 Tab Pro's /vendor/etc/init/trustkernel.rc does exactly this:

    on property:ro.crypto.state=unencrypted
        setprop vendor.trustkernel.fs.mode 1
        setprop vendor.trustkernel.fs.state prepare

    on property:ro.crypto.type=file && property:ro.crypto.state=encrypted
        setprop vendor.trustkernel.fs.mode 3
        setprop vendor.trustkernel.fs.state prepare

-- a wrong value means neither trigger fires and the TEE never prepares.

Swapping the whole init also fixes this, but throws away the rest of the ROM's
spoofing. This changes ONE instruction instead: the spoof entry's name pointer
is nudged three bytes into its own string, so it writes to the inert name
"crypto.state" while every legitimate reference to "ro.crypto.state" is left
exactly as it was.

The spoof reference is told apart from the legitimate ones structurally, not by
guessing: it is the one whose enclosing function ALSO references the literals
"locked" and "green" -- the fingerprint of the spoof table. Booting inits have
no such function, so this script refuses to touch them.

Usage:  patch-init-crypto-spoof.py <init-in> <init-out> [--objdump PATH]
"""

import re, sys, bisect, subprocess, collections

OBJDUMP_DEFAULT = ("/home/neba/prebuilts/clang/host/linux-x86/"
                   "clang-r416183b/bin/llvm-objdump")


def die(msg):
    sys.exit("FATAL: " + msg)


def string_offsets(path, wanted):
    """Exact file offsets of NUL-terminated strings equal to `wanted`."""
    data = open(path, 'rb').read()
    out = collections.defaultdict(list)
    for w in wanted:
        needle = w.encode() + b'\0'
        i = data.find(needle)
        while i != -1:
            # a real string starts after a NUL (or at 0)
            if i == 0 or data[i - 1] == 0:
                out[w].append(i)
            i = data.find(needle, i + 1)
    return out, data


def disassemble(path, objdump):
    try:
        r = subprocess.run([objdump, '-d', '--no-show-raw-insn', path],
                           capture_output=True, text=True, check=True)
    except FileNotFoundError:
        die("llvm-objdump not found -- pass --objdump PATH")
    except subprocess.CalledProcessError as e:
        die("objdump failed: " + (e.stderr or '')[:200])
    return r.stdout.splitlines()


RE_ADRP = re.compile(r'^\s*([0-9a-f]+):\s+adrp\s+(x\d+), (0x[0-9a-f]+)')
RE_ADD = re.compile(r'^\s*([0-9a-f]+):\s+add\s+(x\d+), (x\d+), #(\d+)')
RE_BL = re.compile(r'\bbl\s+(0x[0-9a-f]+)')


def analyse(lines, targets):
    """-> {target_addr: [(site_pc, imm, rd, rn)]}, sorted function entries."""
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
                                   m.group(2), m.group(3)))
    return hits, sorted(bl)


def enclosing(bl, pc):
    i = bisect.bisect_right(bl, pc) - 1
    return bl[i] if i >= 0 else None


def encode_add(imm, rn, rd):
    if not 0 <= imm < 4096:
        die("immediate %d out of range for a single add" % imm)
    return 0x91000000 | (imm << 10) | (rn << 5) | rd


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    objdump = OBJDUMP_DEFAULT
    if '--objdump' in sys.argv:
        objdump = sys.argv[sys.argv.index('--objdump') + 1]
    if len(args) != 2:
        die("usage: %s <init-in> <init-out> [--objdump PATH]" % sys.argv[0])
    src, dst = args

    offs, data = string_offsets(src, ['ro.crypto.state', 'locked', 'green'])
    if not offs['ro.crypto.state']:
        die("no 'ro.crypto.state' string -- is this an init binary?")
    if not offs['locked'] or not offs['green']:
        die("no 'locked'/'green' literals -- this init has no spoof table, so "
            "there is nothing to patch (and it probably already boots)")

    lines = disassemble(src, objdump)
    targets = set(offs['ro.crypto.state'] + offs['locked'] + offs['green'])
    hits, bl = analyse(lines, targets)

    # Functions that materialise BOTH 'locked' and 'green' are spoof tables.
    def fns(addrs):
        return {enclosing(bl, pc) for a in addrs for pc, *_ in hits.get(a, [])}

    spoof_fns = fns(offs['locked']) & fns(offs['green'])
    spoof_fns.discard(None)
    if not spoof_fns:
        die("found the literals but no function references both -- refusing "
            "to guess which reference is the spoof")

    victims = [(pc, imm, rd, rn)
               for a in offs['ro.crypto.state']
               for pc, imm, rd, rn in hits.get(a, [])
               if enclosing(bl, pc) in spoof_fns]
    legit = [(pc, imm) for a in offs['ro.crypto.state']
             for pc, imm, *_ in hits.get(a, [])
             if enclosing(bl, pc) not in spoof_fns]

    print("spoof table function(s): "
          + ", ".join(hex(f) for f in sorted(spoof_fns)))
    print("legitimate ro.crypto.state refs (left alone): "
          + ", ".join(hex(pc) for pc, _ in sorted(legit)))
    if not victims:
        die("the spoof table does not reference ro.crypto.state -- nothing to "
            "do, and this init's failure (if any) has another cause")
    if len(victims) > 1:
        die("expected exactly one spoof reference, found %d -- refusing to "
            "patch blind" % len(victims))

    pc, imm, rd, rn = victims[0]
    rd_n, rn_n = int(rd[1:]), int(rn[1:])
    old = encode_add(imm, rn_n, rd_n)
    have = int.from_bytes(data[pc:pc + 4], 'little')
    if have != old:
        die("instruction at %s reads 0x%08x, expected 0x%08x -- refusing"
            % (hex(pc), have, old))

    new = encode_add(imm + 3, rn_n, rd_n)   # skip "ro." -> "crypto.state"
    out = bytearray(data)
    out[pc:pc + 4] = new.to_bytes(4, 'little')

    if len(out) != len(data):
        die("length changed -- impossible, refusing")
    changed = [i for i in range(len(data)) if data[i] != out[i]]
    open(dst, 'wb').write(bytes(out))

    print("patched at %s: add x%d, x%d, #%d -> #%d"
          % (hex(pc), rd_n, rn_n, imm, imm + 3))
    print("bytes changed: %d (%s)" % (len(changed), ", ".join(map(hex, changed))))
    print("size unchanged: %d -> %d" % (len(data), len(out)))
    print("wrote " + dst)


if __name__ == '__main__':
    main()
