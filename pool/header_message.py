"""Native HeaderSerializer.bytesWithoutPow and Autolykos msgByHeader encoding.

The field order follows the node's HeaderSerializer; unsigned integers use the
Scorex VLQ writer and compact difficulty is four big-endian bytes.
"""
from pow import digest


def _integer(value, maximum):
    if type(value) is not int or not 0 <= value <= maximum:
        raise ValueError("Malformed native header integer")
    return value


def _vlq(value, maximum):
    value = _integer(value, maximum)
    encoded = bytearray()
    while value >= 128:
        encoded.append((value & 127) | 128)
        value >>= 7
    encoded.append(value)
    return bytes(encoded)


def _hex(header, key, size):
    value = header.get(key)
    if not isinstance(value, str) or len(value) != size * 2:
        raise ValueError("Malformed native header " + key)
    raw = bytes.fromhex(value)
    if len(raw) != size:
        raise ValueError("Malformed native header " + key)
    return raw


def bytes_without_pow(header):
    version = _integer(header.get("version"), 127)
    if version < 2:
        raise ValueError("Pool supports native Autolykos v2 headers only")
    extra = header.get("unparsedBytes")
    if not isinstance(extra, str) or len(extra) % 2 or len(extra) > 510:
        raise ValueError("Malformed native future header fields")
    extra = bytes.fromhex(extra)
    if version <= 4 and extra:
        raise ValueError("Unexpected native fields before protocol version 5")
    return (bytes([version]) + _hex(header, "parentId", 32) + _hex(header, "adProofsRoot", 32) +
            _hex(header, "transactionsRoot", 32) + _hex(header, "stateRoot", 33) +
            _vlq(header.get("timestamp"), (1 << 63) - 1) + _hex(header, "extensionHash", 32) +
            _integer(header.get("nBits"), (1 << 32) - 1).to_bytes(4, "big") +
            _vlq(header.get("height"), (1 << 31) - 1) + _hex(header, "votes", 3) + bytes([len(extra)]) + extra)


def header_message(header):
    return digest(bytes_without_pow(header)).hex()
