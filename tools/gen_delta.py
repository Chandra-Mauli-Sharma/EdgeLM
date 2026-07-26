#!/usr/bin/env python3
"""
gen_delta.py — produce an EdgeLM `EDLT1` binary delta between two model files.

This is the server-side (Hub) half of delta updates (arch doc Part 10). The on-device
half is BinaryPatch.apply (Kotlin). A block-hash diff: it indexes fixed-size blocks of
the OLD file and, scanning the NEW file, emits COPY for matching blocks and ADD for the
rest. Small output when the files share most content (e.g. a fine-tune bump).

Usage:
  python tools/gen_delta.py OLD.gguf NEW.gguf OUT.delta [--block 4096]

Prints the delta size, the compression ratio, and the SHA-256 of NEW (use it as the
catalog's `deltaSha256` — the reconstructed-file hash the device verifies).

Delta format:
  magic  "EDLT1\\n"
  ops*   'C' int64 offset int64 length   (copy from OLD, big-endian)
         'A' int64 length  <bytes>       (append new bytes)
"""
import hashlib, os, struct, sys


def gen(old_path, new_path, out_path, block=4096):
    with open(old_path, "rb") as f: old = f.read()
    with open(new_path, "rb") as f: new = f.read()

    # Index old blocks: first offset wins for each distinct block.
    index = {}
    for i in range(0, len(old) - block + 1, block):
        index.setdefault(old[i:i + block], i)

    ops = []          # list of ('C', offset, length) or ('A', bytes)
    add = bytearray()

    def flush_add():
        if add:
            ops.append(("A", bytes(add)))
            add.clear()

    i = 0
    while i < len(new):
        blk = new[i:i + block]
        off = index.get(blk) if len(blk) == block else None
        if off is not None:
            flush_add()
            # Merge with a contiguous previous COPY if possible.
            if ops and ops[-1][0] == "C" and ops[-1][1] + ops[-1][2] == off:
                o = ops[-1]; ops[-1] = ("C", o[1], o[2] + block)
            else:
                ops.append(("C", off, block))
            i += block
        else:
            add.append(new[i]); i += 1
    flush_add()

    with open(out_path, "wb") as f:
        f.write(b"EDLT1\n")
        for op in ops:
            if op[0] == "C":
                f.write(b"C"); f.write(struct.pack(">q", op[1])); f.write(struct.pack(">q", op[2]))
            else:
                f.write(b"A"); f.write(struct.pack(">q", len(op[1]))); f.write(op[1])

    dsize, nsize = os.path.getsize(out_path), len(new)
    print(f"delta: {dsize:,} bytes  ({100*dsize/nsize:.1f}% of the {nsize:,}-byte full file)")
    print(f"deltaSha256 (of NEW): {hashlib.sha256(new).hexdigest()}")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print(__doc__); sys.exit(1)
    blk = 4096
    if "--block" in sys.argv:
        blk = int(sys.argv[sys.argv.index("--block") + 1])
    gen(sys.argv[1], sys.argv[2], sys.argv[3], blk)
