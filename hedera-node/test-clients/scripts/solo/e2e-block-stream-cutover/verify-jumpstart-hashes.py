#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify offline jumpstart hashes against a CN's WrappedRecordFileBlockHashesLog.

Uses only the standard library so the Solo runner needs no protobuf Python package.
The fields are defined in hapi/hapi/src/main/proto/blocks/wrapped_record_hashes.proto.
"""

import struct
import sys
from pathlib import Path


class PendingHashes(ValueError):
    """The async CN writer may finish these entries on a later snapshot."""


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise PendingHashes("truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("oversized protobuf varint")


def fields(data):
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("invalid protobuf field number")
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire == 2:
            size, offset = varint(data, offset)
            end = offset + size
            if end > len(data):
                raise PendingHashes("truncated protobuf field")
            value, offset = data[offset:end], end
        else:
            raise ValueError(f"unexpected protobuf wire type {wire}")
        yield number, value


def verify(jumpstart, log):
    if len(jumpstart) < 164:
        raise ValueError("truncated jumpstart header")
    block = struct.unpack_from(">q", jumpstart)[0]
    leaf_count, hash_count = struct.unpack_from(">qi", jumpstart, 152)
    if block < 0 or leaf_count <= 0 or hash_count < 0 or len(jumpstart) != 164 + 48 * hash_count:
        raise ValueError("invalid jumpstart header or subtree hashes")
    entries = []
    for number, value in fields(log):
        if number != 1 or not isinstance(value, bytes):
            raise ValueError("unexpected wrapped record hashes log field")
        entry = dict(fields(value))
        # Protobuf omits block_number for block 0.
        entry.setdefault(1, 0)
        if not isinstance(entry[1], int) or any(len(entry.get(k, b"")) != 48 for k in (2, 3)):
            raise ValueError("invalid wrapped record hash entry")
        entries.append(entry)
    if not entries:
        raise PendingHashes("empty wrapped record hashes log")
    numbers = [e[1] for e in entries]
    if any(b != a + 1 for a, b in zip(numbers, numbers[1:])):
        raise ValueError("wrapped record hash entries have gaps or duplicates")
    match = next((e for e in entries if e[1] == block), None)
    if match is None:
        if block > numbers[-1]:
            raise PendingHashes(f"waiting for wrapped hashes for jumpstart block {block}")
        raise ValueError(f"no wrapped hashes for jumpstart block {block}")
    for name, expected, actual in (
        ("consensus timestamp", jumpstart[56:104], match[2]),
        ("output-items root", jumpstart[104:152], match[3]),
    ):
        if actual != expected:
            raise ValueError(
                f"jumpstart hashes do not match consensus node at block {block}: {name}\n"
                f"  jumpstart: {expected.hex()}\n  CN:        {actual.hex()}\n"
                "  Check that Block Node tools use a compatible HAPI protobuf schema."
            )
    if numbers[-1] <= block:
        raise PendingHashes("migration needs newer wrapped hashes after the jumpstart block")
    return block


def main():
    if len(sys.argv) != 3:
        sys.exit("Usage: verify-jumpstart-hashes.py jumpstart.bin wrapped-record-hashes.pb")
    try:
        block = verify(Path(sys.argv[1]).read_bytes(), Path(sys.argv[2]).read_bytes())
    except PendingHashes as error:
        print(f"WAIT: {error}", file=sys.stderr)
        sys.exit(2)
    except (OSError, ValueError, TypeError) as error:
        sys.exit(f"FAIL: {error}")
    print(f"PASS: {sys.argv[2]} matches jumpstart block {block} and contains newer hashes")


if __name__ == "__main__":
    main()
