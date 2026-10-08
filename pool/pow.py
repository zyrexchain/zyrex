"""Memory-light Autolykos v2 verification, matching the node's CC0 reference.

This verifies shares on the CPU; GPU miners generate their own dataset.
"""
import hashlib
import struct

MAX_TARGET = (1 << 256) - 1
DIFF1 = 0xFFFF << 208  # Bitcoin/Miningcore Stratum difficulty convention
M = b"".join(struct.pack(">Q", i) for i in range(1024))
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def digest(data):
    return hashlib.blake2b(data, digest_size=32).digest()


def calc_n(height):
    height = min(height, 4_198_400)
    n = 1 << 26
    if height >= 614_400:
        for _ in range((height - 614_400) // 51_200 + 1):
            n = n // 100 * 105
    return n


def hit(msg, nonce, height):
    if len(msg) != 32 or len(nonce) != 8 or height < 1:
        raise ValueError("Invalid Autolykos work")
    n = calc_n(height)
    h = struct.pack(">I", height)
    index = int.from_bytes(digest(msg + nonce)[-8:], "big") % n
    e = digest(struct.pack(">I", index) + h + M)[1:]
    seed = digest(e + msg + nonce)
    extended = seed + seed[:3]
    total = sum(int.from_bytes(digest(
        struct.pack(">I", int.from_bytes(extended[i:i + 4], "big") % n) + h + M
    )[1:], "big") for i in range(32))
    return int.from_bytes(digest(total.to_bytes(32, "big")), "big")


def address_bytes(address):
    if not isinstance(address, str) or not 10 <= len(address) <= 2048:
        raise ValueError("Invalid address length")
    branded = address.startswith("ZRX")
    if branded:
        address = address[3:]
    value = 0
    for char in address:
        value = value * 58 + ALPHABET.index(char)
    raw = b"\0" * (len(address) - len(address.lstrip("1")))
    raw += value.to_bytes((value.bit_length() + 7) // 8, "big")
    if len(raw) < 6 or digest(raw[:-4])[:4] != raw[-4:]:
        raise ValueError("Invalid address checksum")
    if branded != ((raw[0] & 0xf0) in (48, 64)):
        raise ValueError("Invalid Zyrex address branding")
    return raw[:-4]


def validate_miner_address(address, prefix=80):
    if prefix not in (48, 64, 80):
        raise ValueError("Unknown Zyrex network prefix")
    raw = address_bytes(address)
    if len(raw) != 34 or raw[0] != prefix + 1 or raw[1] not in (2, 3):
        raise ValueError("Use a P2PK wallet address for this Zyrex network")
    prime = (1 << 256) - (1 << 32) - 977
    x = int.from_bytes(raw[2:], "big")
    y2 = (pow(x, 3, prime) + 7) % prime
    if x >= prime or pow(y2, (prime - 1) // 2, prime) != 1:
        raise ValueError("Invalid secp256k1 public key")
    return address
