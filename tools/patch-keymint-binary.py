#!/usr/bin/env python3
"""
Redirect the OS-version and root-of-trust property *names* that the vendor
KeyMint stack reads, by rewriting the NUL-terminated C strings in place.

Why this exists
---------------
A GSI hangs at boot on this device for two independent reasons, both of which
come down to the TrustKernel KeyMint stack reading a property whose value the
GSI got wrong:

  1. libkeymint.so reads ro.build.version.release / .security_patch and hands
     them to the TA. A GSI reporting Android 14+ against a TA provisioned under
     13 is rejected -> KEYMINT_NOT_CONFIGURED -> /data never mounts.
     (docs/KEYMINT_OS_VERSION_FIX.md)

  2. The keymint service binary reads ro.boot.verifiedbootstate /
     vbmeta.device_state / vbmeta.digest for its root of trust. A failing GSI's
     init fabricates locked/green/<synthesised> on a device whose bootloader
     actually publishes unlocked/orange/absent, so the TA is handed a root of
     trust the device never had and rejects it. (docs/INIT_SWAP_FIX.md)

Point the reads at vendor-owned property names instead, define those in
/vendor/build.prop with the values the device *actually* has, and every
unmodified GSI boots with no per-image patching. The redirected root-of-trust
values are the honest ones (unlocked/orange) -- this makes the boot succeed, it
does not change what the device attests.

How the rewrite is safe
-----------------------
Each replacement name is shorter than the original and is written at the same
offset, NUL-terminated, with the remaining bytes of the original NUL-padded.
Nothing moves: no relocation, no section resize, ELF stays valid. The tool
refuses to run unless, for every mapping:
  * the original occurs exactly once (unambiguous),
  * it is a complete C string (the following byte is NUL, so we are not
    editing a prefix of a longer string),
  * the replacement fits, and
  * the replacement does not already occur (so the post-check is meaningful).
After writing it re-reads the file and asserts the original is gone and the
replacement occurs exactly once, at the original offset. Same-size output.

Usage
-----
  patch-keymint-binary.py <in> <out> --map old=new [old=new ...]
  patch-keymint-binary.py <in> <out> --preset libkeymint
  patch-keymint-binary.py <in> <out> --preset keymint-service

Presets encode the five strings found on the UMIDIGI G7 Tab Pro (MT6789,
vendor API 31). Verify the offsets against your own binary first with --dry-run.
"""
import argparse
import sys

PRESETS = {
    # libkeymint.so -- OS version (blocker 1)
    "libkeymint": {
        "ro.build.version.release": "ro.vendor.kmosver",
        "ro.build.version.security_patch": "ro.vendor.kmospatch",
    },
    # android.hardware.security.keymint-service.trustkernel -- root of trust (blocker 2)
    "keymint-service": {
        "ro.boot.verifiedbootstate": "ro.vendor.kmvbstate",
        "ro.boot.vbmeta.digest": "ro.vendor.kmvbdig",
        "ro.boot.vbmeta.device_state": "ro.vendor.kmvbdevst",
    },
}


def parse_map(pairs):
    out = {}
    for p in pairs:
        if "=" not in p:
            sys.exit(f"FATAL: --map entry {p!r} is not old=new")
        old, new = p.split("=", 1)
        out[old] = new
    return out


def check(data, mapping):
    """Validate every invariant before touching a byte. Returns [(off,old,new)]."""
    plan = []
    for old, new in mapping.items():
        ob, nb = old.encode(), new.encode()
        occ = data.count(ob)
        if occ != 1:
            sys.exit(f"FATAL: {old!r} occurs {occ} times (need exactly 1)")
        off = data.find(ob)
        if data[off + len(ob)] != 0:
            sys.exit(f"FATAL: {old!r} at {off} is not NUL-terminated "
                     f"(would be editing a prefix of a longer string)")
        if len(nb) > len(ob):
            sys.exit(f"FATAL: {new!r} ({len(nb)}) longer than {old!r} ({len(ob)}); "
                     f"in-place rewrite needs it to be <=")
        if data.count(nb) != 0:
            sys.exit(f"FATAL: replacement {new!r} already present; post-check "
                     f"would be ambiguous")
        plan.append((off, ob, nb))
    return plan


def apply(data, plan):
    buf = bytearray(data)
    for off, ob, nb in plan:
        # new name, its own NUL, then NUL-pad the tail of the old string region
        buf[off:off + len(ob)] = nb + b"\x00" * (len(ob) - len(nb))
    return bytes(buf)


def verify(orig, patched, plan):
    if len(orig) != len(patched):
        sys.exit(f"FATAL: size changed {len(orig)} -> {len(patched)}")
    for off, ob, nb in plan:
        if patched.count(ob):
            sys.exit(f"FATAL: original {ob!r} still present after patch")
        if patched.count(nb) != 1 or patched.find(nb) != off:
            sys.exit(f"FATAL: replacement {nb!r} not at expected offset {off}")
    # every byte outside the edited regions must be untouched
    edited = bytearray(orig)
    for off, ob, nb in plan:
        edited[off:off + len(ob)] = nb + b"\x00" * (len(ob) - len(nb))
    if bytes(edited) != patched:
        sys.exit("FATAL: bytes changed outside the planned regions")


def main():
    ap = argparse.ArgumentParser(description="Redirect KeyMint property names in place.")
    ap.add_argument("infile")
    ap.add_argument("outfile")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--map", nargs="+", metavar="old=new")
    g.add_argument("--preset", choices=sorted(PRESETS))
    ap.add_argument("--dry-run", action="store_true",
                    help="validate and print the plan; write nothing")
    args = ap.parse_args()

    mapping = PRESETS[args.preset] if args.preset else parse_map(args.map)
    data = open(args.infile, "rb").read()
    plan = check(data, mapping)

    print(f"{args.infile}: {len(data)} bytes, {len(plan)} redirect(s):")
    for off, ob, nb in plan:
        print(f"  @{off:<8} {ob.decode():32} -> {nb.decode():20} "
              f"(pad {len(ob) - len(nb)})")

    if args.dry_run:
        print("dry run: nothing written")
        return

    patched = apply(data, plan)
    verify(data, patched, plan)
    with open(args.outfile, "wb") as f:
        f.write(patched)
    print(f"wrote {args.outfile}: verified, same size, "
          f"{len(plan)} string(s) redirected")


if __name__ == "__main__":
    main()
