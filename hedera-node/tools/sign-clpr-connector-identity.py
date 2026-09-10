#!/usr/bin/env python3
"""
Compute the connectorId, commitment, and reveal signature for a CLPR connector
identity bundle (the JSON file produced by `yahcli clpr generate-connector-identity`
or written by hand).

Required input fields:
  - channelId   (32 bytes, hex, 0x prefix optional) — the channel this connector serves
  - privateKey     (32 bytes, hex; an Ed25519 seed or a secp256k1 scalar)
  - signatureScheme  one of: "ED25519", "ECDSA" (alias), "ECDSA_SECP256K1"

Optional input fields (used when present, derived otherwise):
  - salt           (32 bytes, hex). If absent or empty, a fresh random 32-byte salt is generated.
  - publicKey      (hex). If supplied, must match what the privateKey derives — script errors
                   on mismatch. If absent, derived from privateKey.
  - connectorId    (32 bytes, hex). If supplied, must match keccak256(channelId || publicKey
                   || salt). If absent, derived.
  - serviceAddress (20 bytes, hex). Defaults to 0x000000000000000000000000000000000000016e
                   (the CLPR system contract — matches the on-ledger handler's hard-coded value).

The script derives:
  publicKey     = derived from privateKey per scheme
                  • ED25519:         32 bytes (raw point)
                  • ECDSA_SECP256K1: 64 bytes uncompressed X||Y (no 0x04 header)
  connectorId   = keccak256(channelId || publicKey || salt)
  commitment    = keccak256(connectorId  || publicKey)
  signature     = sign(keccak256(connectorId || serviceAddress)) under the private key
                  • ED25519:         64-byte raw signature
                  • ECDSA_SECP256K1: 64-byte r||s (low-s canonical, no v byte)

These match the on-wire format expected by ClprCompleteConnectorHandler.

Output is the input JSON augmented with any newly derived fields. By default the result
is printed to stdout; pass --in-place to overwrite the input file or --out <path> to
write somewhere else.

Dependencies:
  pip install pycryptodome      # required: Ed25519 + Keccak-256
  cast (Foundry)                 # required only for ECDSA_SECP256K1; install via foundryup
"""

from __future__ import annotations  # PEP 604/585 annotations on Python 3.9

import argparse
import json
import os
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
SALT_LEN = 32
SERVICE_ADDRESS_LEN = 20

# Mirrors ClprCompleteConnectorHandler.CLPR_SERVICE_ADDRESS (the 20-byte form of 0x...16e).
DEFAULT_SERVICE_ADDRESS = bytes(
    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x6e]
)

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
# ECDSA secp256k1: shell out to Foundry's `cast`.
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
    return sig[:64]


# -----------------------------------------------------------------------------
# Glue
# -----------------------------------------------------------------------------

def derive(
    *,
    channel_id: bytes,
    private_key: bytes,
    scheme: str,
    salt: bytes,
    service_address: bytes,
    supplied_public_key: bytes | None = None,
    supplied_connector_id: bytes | None = None,
) -> dict[str, bytes]:
    if scheme == ED25519:
        public_key = ed25519_pubkey(private_key)
        signer = lambda msg_hash: ed25519_sign(private_key, msg_hash)
    elif scheme == ECDSA:
        public_key = secp256k1_pubkey(private_key)
        signer = lambda msg_hash: secp256k1_sign(private_key, msg_hash)
    else:
        raise AssertionError("unreachable: " + scheme)

    if supplied_public_key is not None and supplied_public_key != public_key:
        raise ValueError(
            "publicKey in input does not match what privateKey derives.\n"
            "  supplied: 0x{}\n"
            "  derived:  0x{}".format(supplied_public_key.hex(), public_key.hex())
        )

    connector_id = keccak256(channel_id + public_key + salt)
    if supplied_connector_id is not None and supplied_connector_id != connector_id:
        raise ValueError(
            "connectorId in input does not match keccak256(channelId || publicKey || salt).\n"
            "  supplied: 0x{}\n"
            "  derived:  0x{}".format(supplied_connector_id.hex(), connector_id.hex())
        )

    commitment = keccak256(connector_id + public_key)
    sig_msg_hash = keccak256(connector_id + service_address)
    signature = signer(sig_msg_hash)

    return {
        "publicKey": public_key,
        "connectorId": connector_id,
        "commitment": commitment,
        "signature": signature,
    }


def render_json(
    bundle: dict,
    derived: dict[str, bytes],
    *,
    scheme: str,
    salt: bytes,
    service_address: bytes,
) -> str:
    """Emit the connector-identity JSON in the same field order yahcli uses."""
    ordered: dict[str, str] = {
        "channelId": _ensure_0x(bundle["channelId"]),
        "connectorId": "0x" + derived["connectorId"].hex(),
        "publicKey": "0x" + derived["publicKey"].hex(),
        "privateKey": _ensure_0x(bundle["privateKey"]),
        "salt": "0x" + salt.hex(),
        "signatureScheme": scheme,
        "serviceAddress": "0x" + service_address.hex(),
        "commitment": "0x" + derived["commitment"].hex(),
        "signature": "0x" + derived["signature"].hex(),
    }
    # Preserve any extra keys the caller had (e.g. notes, label) without re-ordering them in front.
    for k, v in bundle.items():
        if k not in ordered:
            ordered[k] = v
    return json.dumps(ordered, indent=2) + "\n"


def _ensure_0x(value: str) -> str:
    s = value.strip()
    return s if s.lower().startswith("0x") else "0x" + s


def _optional_hex(raw, field: str, expected_len: int | None = None) -> bytes | None:
    """Parse a hex field that may be absent. Returns None for missing/empty values."""
    if raw is None:
        return None
    s = str(raw).strip()
    if not s or s in ("0x", "0X"):
        return None
    return parse_hex(s, field, expected_len)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Compute connectorId, commitment, and signature for a CLPR connector identity JSON.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("input", help="Path to the connector identity JSON.")
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

        salt_raw = bundle.get("salt")
        if salt_raw is None or not str(salt_raw).strip() or str(salt_raw).strip() in ("0x", "0X"):
            salt = os.urandom(SALT_LEN)
        else:
            salt = parse_hex(salt_raw, "salt", SALT_LEN)

        service_raw = bundle.get("serviceAddress")
        if service_raw is None or not str(service_raw).strip() or str(service_raw).strip() in ("0x", "0X"):
            service_address = DEFAULT_SERVICE_ADDRESS
        else:
            service_address = parse_hex(service_raw, "serviceAddress", SERVICE_ADDRESS_LEN)

        supplied_public_key = _optional_hex(bundle.get("publicKey"), "publicKey")
        supplied_connector_id = _optional_hex(
            bundle.get("connectorId"), "connectorId", expected_len=CHANNEL_ID_LEN
        )
    except ValueError as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 2

    try:
        derived = derive(
            channel_id=channel_id,
            private_key=private_key,
            scheme=scheme,
            salt=salt,
            service_address=service_address,
            supplied_public_key=supplied_public_key,
            supplied_connector_id=supplied_connector_id,
        )
    except (RuntimeError, ValueError) as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 1

    rendered = render_json(
        bundle, derived, scheme=scheme, salt=salt, service_address=service_address
    )

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
