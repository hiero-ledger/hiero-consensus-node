#!/usr/bin/env python3
"""
Compute the ownership commitment and reveal signature for a CLPR channel
identity bundle (the JSON file produced by `yahcli clpr generate-channel-identity`
or written by hand).

Input file (e.g. channel.json) must contain at least:
  - channelId   (32 bytes, hex, 0x prefix optional)
  - privateKey     (32 bytes, hex; an Ed25519 seed or a secp256k1 scalar)
  - signatureScheme  one of: "ED25519", "ECDSA" (alias), "ECDSA_SECP256K1"

The script derives the matching public key, then computes:
  publicKey            = derived from privateKey per scheme
                         • ED25519:          32 bytes (raw point)
                         • ECDSA_SECP256K1:  64 bytes uncompressed X||Y (no 0x04 header)
  ownershipCommitment  = keccak256(channelId || publicKey)
  signature            = sign(keccak256(channelId)) under the private key
                         • ED25519:          64-byte raw signature
                         • ECDSA_SECP256K1:  64-byte r||s (low-s canonical, no v byte)

These match the on-wire format expected by ClprCompleteChannelHandler — see
SignatureType.ECDSA_SECP256K1 ("NONEwithECDSA") in the platform crypto module and
the ECDSA_UNCOMPRESSED_KEY_LENGTH check in the handler.

Output is the input JSON augmented with the three computed fields. By default
the result is printed to stdout; pass --in-place to overwrite the input file or
--out <path> to write somewhere else.

Dependencies:
  pip install pycryptodome      # required: Ed25519 + Keccak-256
  cast (Foundry)                 # required only for ECDSA_SECP256K1; install via foundryup
"""

from __future__ import annotations  # PEP 604/585 annotations on Python 3.9

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from Crypto.Hash import keccak
    from Crypto.PublicKey import ECC
    from Crypto.Signature import eddsa
except ImportError as e:  # pragma: no cover — install hint
    print(
        "ERROR: missing pycryptodome ({}). Install it with:\n  pip install pycryptodome".format(e),
        file=sys.stderr,
    )
    sys.exit(2)


# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

CHANNEL_ID_LEN = 32
PRIVATE_KEY_LEN = 32

ED25519 = "ED25519"
ECDSA = "ECDSA_SECP256K1"


def parse_hex(value: str, field: str, expected_len: int | None = None) -> bytes:
    if value is None:
        raise ValueError("{} is missing".format(field))
    s = value.strip()
    if s.lower().startswith("0x"):
        s = s[2:]
    if not re.fullmatch(r"[0-9a-fA-F]*", s):
        raise ValueError("{} is not valid hex: {!r}".format(field, value))
    try:
        out = bytes.fromhex(s)
    except ValueError as exc:
        raise ValueError("{} is not valid hex: {}".format(field, exc)) from exc
    if expected_len is not None and len(out) != expected_len:
        raise ValueError(
            "{} must be exactly {} bytes ({} hex chars); got {} bytes".format(
                field, expected_len, expected_len * 2, len(out)
            )
        )
    return out


def normalize_scheme(raw: str) -> str:
    if not raw or not raw.strip():
        raise ValueError("signatureScheme is required")
    upper = raw.strip().upper()
    if upper == "ECDSA":  # accept legacy alias
        return ECDSA
    if upper in (ED25519, ECDSA):
        return upper
    raise ValueError(
        "Unsupported signatureScheme {!r}. Expected ED25519 or ECDSA_SECP256K1.".format(raw)
    )


def keccak256(data: bytes) -> bytes:
    h = keccak.new(digest_bits=256)
    h.update(data)
    return h.digest()


# -----------------------------------------------------------------------------
# ED25519: derive pubkey + sign via pycryptodome
# -----------------------------------------------------------------------------

def ed25519_pubkey(seed: bytes) -> bytes:
    key = ECC.construct(curve="Ed25519", seed=seed)
    return key.public_key().export_key(format="raw")


def ed25519_sign(seed: bytes, message: bytes) -> bytes:
    key = ECC.construct(curve="Ed25519", seed=seed)
    return eddsa.new(key, mode="rfc8032").sign(message)


# -----------------------------------------------------------------------------
# ECDSA secp256k1: shell out to Foundry's `cast` (no native Python secp256k1
# without a third-party dep). `cast wallet sign --no-hash` signs a raw 32-byte
# hash using deterministic RFC 6979, producing a canonical low-s signature.
# -----------------------------------------------------------------------------

def _require_cast() -> str:
    cast = shutil.which("cast")
    if not cast:
        raise RuntimeError(
            "ECDSA_SECP256K1 requires Foundry's `cast` on PATH. Install foundry "
            "(https://book.getfoundry.sh/getting-started/installation) and retry."
        )
    return cast


def _cast(args: list[str]) -> str:
    cast = _require_cast()
    res = subprocess.run([cast, *args], capture_output=True, text=True, check=False)
    if res.returncode != 0:
        raise RuntimeError(
            "`cast {}` failed (exit {}):\n{}".format(" ".join(args), res.returncode, res.stderr.strip())
        )
    return res.stdout.strip()


def secp256k1_pubkey(scalar: bytes) -> bytes:
    """Returns the 64-byte uncompressed public key (X||Y, no 0x04 prefix)."""
    out = _cast(["wallet", "public-key", "--raw-private-key", "0x" + scalar.hex()])
    return parse_hex(out, "cast public-key output", expected_len=64)


def secp256k1_sign(scalar: bytes, message_hash: bytes) -> bytes:
    """Signs the raw 32-byte hash and returns 64-byte r||s (drops the recovery byte)."""
    if len(message_hash) != 32:
        raise ValueError("ECDSA message hash must be 32 bytes; got {}".format(len(message_hash)))
    out = _cast(
        [
            "wallet",
            "sign",
            "--no-hash",
            "--private-key",
            "0x" + scalar.hex(),
            "0x" + message_hash.hex(),
        ]
    )
    sig = parse_hex(out, "cast sign output")
    if len(sig) != 65:
        raise RuntimeError("expected 65-byte cast signature (r||s||v); got {} bytes".format(len(sig)))
    return sig[:64]  # drop the trailing recovery byte


# -----------------------------------------------------------------------------
# Glue
# -----------------------------------------------------------------------------

def derive(channel_id: bytes, private_key: bytes, scheme: str) -> dict[str, bytes]:
    if scheme == ED25519:
        public_key = ed25519_pubkey(private_key)
        signature = ed25519_sign(private_key, keccak256(channel_id))
    elif scheme == ECDSA:
        public_key = secp256k1_pubkey(private_key)
        signature = secp256k1_sign(private_key, keccak256(channel_id))
    else:
        raise AssertionError("unreachable: " + scheme)

    commitment = keccak256(channel_id + public_key)
    return {
        "publicKey": public_key,
        "ownershipCommitment": commitment,
        "signature": signature,
    }


def render_json(bundle: dict, derived: dict[str, bytes], scheme: str) -> str:
    """Emit the channel-identity JSON in the same field order yahcli uses."""
    ordered: dict[str, str] = {
        "channelId": _ensure_0x(bundle["channelId"]),
        "publicKey": "0x" + derived["publicKey"].hex(),
        "privateKey": _ensure_0x(bundle["privateKey"]),
        "signatureScheme": scheme,
        "ownershipCommitment": "0x" + derived["ownershipCommitment"].hex(),
        "signature": "0x" + derived["signature"].hex(),
    }
    # Preserve any extra keys the caller had (e.g. notes, label) without reordering them in front.
    for k, v in bundle.items():
        if k not in ordered:
            ordered[k] = v
    return json.dumps(ordered, indent=2) + "\n"


def _ensure_0x(value: str) -> str:
    s = value.strip()
    return s if s.lower().startswith("0x") else "0x" + s


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Compute ownershipCommitment + signature for a CLPR channel identity JSON.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("input", help="Path to the channel identity JSON (e.g. channel.json).")
    out = parser.add_mutually_exclusive_group()
    out.add_argument("--out", help="Write the resulting JSON to this path.")
    out.add_argument("--in-place", action="store_true", help="Overwrite the input file.")
    args = parser.parse_args(argv)

    in_path = Path(args.input)
    try:
        bundle = json.loads(in_path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        print("ERROR: cannot read {} as JSON: {}".format(in_path, e), file=sys.stderr)
        return 2

    try:
        scheme = normalize_scheme(bundle.get("signatureScheme", ""))
        channel_id = parse_hex(bundle.get("channelId"), "channelId", CHANNEL_ID_LEN)
        private_key = parse_hex(bundle.get("privateKey"), "privateKey", PRIVATE_KEY_LEN)
    except ValueError as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 2

    try:
        derived = derive(channel_id, private_key, scheme)
    except (RuntimeError, ValueError) as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 1

    rendered = render_json(bundle, derived, scheme)

    if args.out:
        Path(args.out).write_text(rendered)
        print("wrote {}".format(args.out), file=sys.stderr)
    elif args.in_place:
        in_path.write_text(rendered)
        print("updated {}".format(in_path), file=sys.stderr)
    else:
        sys.stdout.write(rendered)

    return 0


if __name__ == "__main__":
    sys.exit(main())
