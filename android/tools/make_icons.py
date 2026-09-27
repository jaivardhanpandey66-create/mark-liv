#!/usr/bin/env python3
"""Generate MARK LIV launcher icons (no external deps) — graphite plate, orange reactor."""
import struct, zlib, math, os, sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res")


def png(path, w, h, px):
    raw = b"".join(b"\x00" + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    hdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", hdr)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(blob)


def draw(size, inset_ratio=0.0, square=False):
    w = h = size
    buf = bytearray(w * h * 4)
    cx = cy = (w - 1) / 2.0
    r_out = w * (0.5 - inset_ratio)
    r_ring = r_out * 0.80
    r_dash = r_out * 0.93
    r_core = r_out * 0.34
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * 4
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy)
            r, g, b, a = 0x0B, 0x0C, 0x0F, 255          # graphite
            if d <= r_out:
                # plate with a soft vertical sheen
                t = y / max(1, h - 1)
                r = int(0x14 + 0x10 * (1 - t)); g = int(0x16 + 0x0C * (1 - t)); b = int(0x1A + 0x08 * (1 - t))
                if d > r_out * 0.93:
                    r, g, b = 0x24, 0x28, 0x2E
            ang = math.atan2(dy, dx)
            # dashed outer ring
            if abs(d - r_dash) < max(1.0, w * 0.012) and (int((ang + math.pi) / (math.pi / 18)) % 2 == 0):
                r, g, b = 0xFF, 0x7A, 0x18
            # solid reactor ring
            if abs(d - r_ring) < max(1.0, w * 0.035):
                r, g, b = 0xFF, 0x8C, 0x2E
            # core
            if d <= r_core:
                k = 1 - d / max(1.0, r_core)
                r = int(0xFF - 0x30 * (1 - k)); g = int(0x9A - 0x50 * (1 - k)); b = int(0x40 - 0x40 * (1 - k))
            buf[i:i + 4] = bytes((r, g, b, a))
    return buf


def main():
    for folder, size in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        px = draw(size, inset_ratio=0.02)
        path = os.path.normpath(os.path.join(OUT, folder, "ic_launcher.png"))
        png(path, size, size, px)
        print("wrote", path)
    # round variant (same art, the launcher masks it)
    px = draw(192, inset_ratio=0.02)
    png(os.path.normpath(os.path.join(OUT, "mipmap-xxxhdpi", "ic_launcher_round.png")), 192, 192, px)
    print("icons done")


if __name__ == "__main__":
    main()
