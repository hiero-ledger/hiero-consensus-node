#!/usr/bin/env python3
"""
Write an Ethereum Web3 Secret Storage (keystore v3) file for a private key, so tools such as
Foundry's `cast` can sign with --keystore/--password-file instead of a raw --private-key flag
(which exposes the key to every process via the command line).

The private key is read from STDIN (hex, 0x optional), never from argv. The keystore is encrypted
with the password found in --password-file (scrypt + AES-128-CTR) and written with mode 0600.

Usage:
  printf '%s' "$KEY" | write-eth-keystore.py --out ks.json --password-file pw.txt

Dependencies: pip install pycryptodome
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import uuid

try:
    from Crypto.Cipher import AES
    from Crypto.Hash import keccak
    from Crypto.Protocol.KDF import scrypt
    from Crypto.Util import Counter
except ImportError as e:  # pragma: no cover — install hint
    print("ERROR: missing pycryptodome ({}). Install it with:\n  pip install pycryptodome".format(e), file=sys.stderr)
    sys.exit(2)

SCRYPT_N = 1 << 13  # modest cost: this protects a short-lived e2e key, and cast must decrypt it often
SCRYPT_R = 8
SCRYPT_P = 1


def build_keystore(private_key: bytes, password: bytes) -> dict:
    salt = os.urandom(32)
    iv = os.urandom(16)
    dk = scrypt(password, salt, 32, N=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P)
    ctr = Counter.new(128, initial_value=int.from_bytes(iv, "big"))
    ciphertext = AES.new(dk[:16], AES.MODE_CTR, counter=ctr).encrypt(private_key)
    mac = keccak.new(digest_bits=256, data=dk[16:32] + ciphertext).digest()
    return {
        "version": 3,
        "id": str(uuid.uuid4()),
        "crypto": {
            "cipher": "aes-128-ctr",
            "cipherparams": {"iv": iv.hex()},
            "ciphertext": ciphertext.hex(),
            "kdf": "scrypt",
            "kdfparams": {"dklen": 32, "n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P, "salt": salt.hex()},
            "mac": mac.hex(),
        },
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Write a keystore v3 file for a private key read from stdin.")
    parser.add_argument("--out", required=True, help="Keystore path to create (must not already exist).")
    parser.add_argument("--password-file", required=True, help="File whose first line is the keystore password.")
    args = parser.parse_args(argv)

    raw = sys.stdin.read().strip()
    if raw.lower().startswith("0x"):
        raw = raw[2:]
    if not re.fullmatch(r"[0-9a-fA-F]{64}", raw):
        print("ERROR: stdin must hold a 32-byte hex private key", file=sys.stderr)
        return 2
    try:
        with open(args.password_file, "rb") as f:
            password = f.readline().rstrip(b"\r\n")
    except OSError as e:
        print("ERROR: cannot read password file: {}".format(e), file=sys.stderr)
        return 2
    if not password:
        print("ERROR: password file is empty", file=sys.stderr)
        return 2

    content = json.dumps(build_keystore(bytes.fromhex(raw), password))
    try:
        fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(content)
        os.chmod(args.out, 0o600)
    except OSError as e:
        print("ERROR: cannot write {}: {}".format(args.out, e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
