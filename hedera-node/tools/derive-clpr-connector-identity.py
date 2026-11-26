#!/usr/bin/env python3
"""
Derive a reusable connector identity bundle for `yahcli clpr create-connector`.
Supply a JSON object with signatureScheme (ED25519 or ECDSA_SECP256K1) and,
optionally, a 32-byte hex salt. A missing salt is generated randomly. The ID
is keccak256(publicKey || salt), using the raw Ed25519 point or uncompressed
secp256k1 X||Y coordinates. Supplied publicKey and connectorId are checked.
The payer and identity owner sign the HAPI transaction at registration time.

The private key is supplied ONLY via --key-file <path> (a file holding the 32-byte hex key,
mode 0600). On POSIX a key file that is a symlink or non-regular file is always refused, and one
readable by group/others or owned by someone else is refused unless --allow-loose-key-file is given.
A bundle containing an inline privateKey field is rejected, and nothing is written.

Dependencies:
  pip install pycryptodome              # required
  pip install coincurve                 # required only for ECDSA_SECP256K1

Existing files are never destroyed: if the --out target already exists it is
first moved to <name>.<epochMillis>.bak (mode 0600), so a legacy bundle is never lost.
"""

from __future__ import annotations  # PEP 604/585 annotations on Python 3.9

import argparse
import importlib.util
import json
import os
import re
import sys
from pathlib import Path


try:
    from Crypto.Hash import keccak
    from Crypto.PublicKey import ECC
except ImportError as e:  # pragma: no cover — install hint
    print(
        "ERROR: missing pycryptodome ({}). Install it with:\n  pip install pycryptodome".format(e),
        file=sys.stderr,
    )
    sys.exit(2)


# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

# Shared key-file helpers live next to this script (same loading pattern as build-besu-qbft-config-payload.py).
_spec = importlib.util.spec_from_file_location(
    "clpr_identity_files", Path(__file__).with_name("clpr_identity_files.py"))
_files = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_files)
_check_key_file = _files._check_key_file
_write_secure = _files._write_secure


CONNECTOR_ID_LEN = 32
PRIVATE_KEY_LEN = 32
SALT_LEN = 32
ED25519 = "ED25519"
ECDSA = "ECDSA_SECP256K1"


def parse_hex(value: str, field: str, expected_len: int | None = None) -> bytes:
    if value is None:
        raise ValueError("{} is missing".format(field))
    s = value.strip()
    if s.lower().startswith("0x"):
        s = s[2:]
    if not re.fullmatch(r"[0-9a-fA-F]*", s):
        raise ValueError("{} is not valid hex".format(field))
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
# ED25519: derive pubkey via pycryptodome
# -----------------------------------------------------------------------------

def ed25519_pubkey(seed: bytes) -> bytes:
    key = ECC.construct(curve="Ed25519", seed=seed)
    return key.public_key().export_key(format="raw")


# -----------------------------------------------------------------------------
# ECDSA secp256k1: derive the public key.
# -----------------------------------------------------------------------------

def _coincurve():
    """Import coincurve lazily so ED25519 users don't need it installed."""
    try:
        import coincurve
    except ImportError as e:  # pragma: no cover — install hint
        print(
            "ERROR: ECDSA_SECP256K1 requires coincurve ({}). Install it with:\n  pip install coincurve".format(e),
            file=sys.stderr,
        )
        sys.exit(2)
    return coincurve


def secp256k1_pubkey(scalar: bytes) -> bytes:
    """Returns the 64-byte uncompressed public key (X||Y, no 0x04 prefix)."""
    return _coincurve().PrivateKey(scalar).public_key.format(compressed=False)[1:]


# -----------------------------------------------------------------------------
# Glue
# -----------------------------------------------------------------------------

def derive(
    *,
    private_key: bytes,
    scheme: str,
    salt: bytes,
    supplied_public_key: bytes | None = None,
    supplied_connector_id: bytes | None = None,
) -> dict[str, bytes]:
    if scheme == ED25519:
        public_key = ed25519_pubkey(private_key)
    elif scheme == ECDSA:
        public_key = secp256k1_pubkey(private_key)
    else:
        raise AssertionError("unreachable: " + scheme)

    if supplied_public_key is not None and supplied_public_key != public_key:
        raise ValueError(
            "publicKey in input does not match what the private key derives.\n"
            "  supplied: 0x{}\n"
            "  derived:  0x{}".format(supplied_public_key.hex(), public_key.hex())
        )

    connector_id = keccak256(public_key + salt)
    if supplied_connector_id is not None and supplied_connector_id != connector_id:
        raise ValueError(
            "connectorId in input does not match keccak256(publicKey || salt).\n"
            "  supplied: 0x{}\n"
            "  derived:  0x{}".format(supplied_connector_id.hex(), connector_id.hex())
        )

    return {
        "publicKey": public_key,
        "connectorId": connector_id,
    }


def render_json(
    bundle: dict,
    derived: dict[str, bytes],
    *,
    scheme: str,
    salt: bytes,
) -> str:
    """Emit the connector-identity JSON in the same field order yahcli uses.

    The private key never appears in the bundle — it lives in the companion .key file.
    """
    ordered: dict[str, str] = {
        "connectorId": "0x" + derived["connectorId"].hex(),
        "publicKey": "0x" + derived["publicKey"].hex(),
        "salt": "0x" + salt.hex(),
        "signatureScheme": scheme,
    }
    # Preserve any extra keys the caller had (e.g. notes, label) without re-ordering them in front.
    for k, v in bundle.items():
        if k not in ordered and k not in {"commitment", "signature", "channelId", "serviceAddress"}:
            ordered[k] = v
    return json.dumps(ordered, indent=2) + "\n"


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
        description="Derive the public key and connector ID for a CLPR connector identity JSON.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("input", help="Path to the connector identity JSON.")
    parser.add_argument(
        "--key-file",
        help="Path to the file containing the hex-encoded private key (required).",
    )
    parser.add_argument(
        "--allow-loose-key-file",
        action="store_true",
        help="Do not refuse a --key-file that group/others can access or that you do not own "
             "(symlinks and non-regular files are always refused).",
    )
    parser.add_argument("--out", help="Write the resulting JSON to this path.")
    args = parser.parse_args(argv)

    in_path = Path(args.input)
    try:
        bundle = json.loads(in_path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        print("ERROR: cannot read {} as JSON: {}".format(in_path, e), file=sys.stderr)
        return 2

    try:
        scheme = normalize_scheme(bundle.get("signatureScheme", ""))
        if "privateKey" in bundle:
            print(
                "ERROR: the input bundle contains an inline privateKey, which is no longer supported. "
                "Move the key into a file readable only by you (mode 0600), remove the privateKey "
                "field from the bundle, and pass --key-file <path>. Nothing was written.",
                file=sys.stderr,
            )
            return 2
        if not args.key_file:
            print("ERROR: no private key source: provide --key-file <path>", file=sys.stderr)
            return 2
        _check_key_file(Path(args.key_file), args.allow_loose_key_file)
        private_key = parse_hex(Path(args.key_file).read_text().strip(), "key-file", PRIVATE_KEY_LEN)

        salt_raw = bundle.get("salt")
        if salt_raw is None or not str(salt_raw).strip() or str(salt_raw).strip() in ("0x", "0X"):
            salt = os.urandom(SALT_LEN)
        else:
            salt = parse_hex(salt_raw, "salt", SALT_LEN)

        supplied_public_key = _optional_hex(bundle.get("publicKey"), "publicKey")
        supplied_connector_id = _optional_hex(
            bundle.get("connectorId"), "connectorId", expected_len=CONNECTOR_ID_LEN
        )
    except ValueError as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 2

    try:
        derived = derive(
            private_key=private_key,
            scheme=scheme,
            salt=salt,
            supplied_public_key=supplied_public_key,
            supplied_connector_id=supplied_connector_id,
        )
    except (RuntimeError, ValueError) as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 1

    rendered = render_json(bundle, derived, scheme=scheme, salt=salt)

    if args.out:
        target = Path(args.out)
        try:
            _write_secure(target, rendered)
        except OSError as e:
            print("ERROR: cannot write {}: {}".format(target, e), file=sys.stderr)
            return 1
        print("wrote {}".format(target), file=sys.stderr)
    else:
        sys.stdout.write(rendered)

    return 0


if __name__ == "__main__":
    sys.exit(main())
