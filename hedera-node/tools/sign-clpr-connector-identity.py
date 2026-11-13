#!/usr/bin/env python3
"""
Compute the connectorId, commitment, and reveal signature for a CLPR connector
identity bundle (the JSON file produced by `yahcli clpr generate-connector-identity`
or written by hand).

Required input fields:
  - channelId   (32 bytes, hex, 0x prefix optional) — the channel this connector serves
  - signatureScheme  one of: "ED25519", "ECDSA" (alias), "ECDSA_SECP256K1"

Optional input fields (used when present, derived otherwise):
  - salt           (32 bytes, hex). If absent or empty, a fresh random 32-byte salt is generated.
  - publicKey      (hex). If supplied, must match what the private key derives — script errors
                   on mismatch. If absent, derived from the private key.
  - connectorId    (32 bytes, hex). If supplied, must match keccak256(channelId || publicKey
                   || salt). If absent, derived.
  - serviceAddress (20 bytes, hex). Defaults to 0x000000000000000000000000000000000000016e
                   (the CLPR system contract — matches the on-ledger handler's hard-coded value).

The script derives:
  publicKey     = derived from the private key per scheme
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

The private key is supplied ONLY via --key-file <path> (a file holding the 32-byte hex key,
mode 0600). On POSIX a key file that is a symlink or non-regular file is always refused, and one
readable by group/others or owned by someone else is refused unless --allow-loose-key-file is given.
A bundle containing an inline privateKey field is rejected, and nothing is written.

Dependencies:
  pip install pycryptodome              # required
  pip install coincurve                 # required only for ECDSA_SECP256K1

Existing files are never destroyed: if the --out / --in-place target already exists it is
first moved to <name>.<epochMillis>.bak (mode 0600), so a legacy bundle is never lost.
"""

from __future__ import annotations  # PEP 604/585 annotations on Python 3.9

import argparse
import json
import os
import re
import stat
import sys
import time
from pathlib import Path


def _check_key_file(path: Path, allow_loose: bool) -> None:
    """Refuse a key file that is not a regular, non-symlink file readable only by its owner (POSIX)."""
    if os.name != "posix":
        return
    try:
        st = os.lstat(str(path))
    except OSError as e:
        raise ValueError("cannot stat key file {}: {}".format(path, e)) from e
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        raise ValueError("key file must be a regular file, not a symlink or special file: {}".format(path))
    if allow_loose:
        return
    if st.st_uid != os.geteuid():
        raise ValueError(
            "key file {} is not owned by you; pass --allow-loose-key-file to override".format(path))
    if st.st_mode & 0o077:
        raise ValueError(
            "key file {} has mode {:o}; it must not be accessible by group/others. "
            "Run: chmod 600 {}  (or pass --allow-loose-key-file to override)".format(
                path, stat.S_IMODE(st.st_mode), path))


def _backup_existing(path: Path) -> Path | None:
    """Move an existing regular file to <name>.<epochMillis>.bak (0600); never destroys data."""
    if path.is_symlink():
        raise OSError("refusing to write through symlink: {}".format(path))
    if not path.exists():
        return None
    if not path.is_file():
        raise OSError("refusing to replace non-regular file: {}".format(path))
    epoch = int(time.time() * 1000)
    backup = path.with_name("{}.{}.bak".format(path.name, epoch))
    while backup.exists():
        epoch += 1
        backup = path.with_name("{}.{}.bak".format(path.name, epoch))
    os.rename(str(path), str(backup))
    os.chmod(str(backup), 0o600)
    return backup


def _write_secure(path: Path, content: str) -> None:
    """Write content to a new file at path with mode 0o600, first backing up any existing file."""
    backup = _backup_existing(path)
    if backup is not None:
        print("existing {} preserved as {}".format(path, backup), file=sys.stderr)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(str(path), flags, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(content.encode())
    os.chmod(str(path), 0o600)  # enforce 0600 even if the umask were somehow permissive

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
# ECDSA secp256k1: generate the private key.
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


def secp256k1_sign(scalar: bytes, message_hash: bytes) -> bytes:
    """Signs the raw 32-byte hash and returns 64-byte r||s (drops the recovery byte)."""
    if len(message_hash) != 32:
        raise ValueError("ECDSA message hash must be 32 bytes; got {}".format(len(message_hash)))
    return _coincurve().PrivateKey(scalar).sign_recoverable(message_hash, hasher=None)[:64]


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
            "publicKey in input does not match what the private key derives.\n"
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
    """Emit the connector-identity JSON in the same field order yahcli uses.

    The private key never appears in the bundle — it lives in the companion .key file.
    """
    ordered: dict[str, str] = {
        "channelId": _ensure_0x(bundle["channelId"]),
        "connectorId": "0x" + derived["connectorId"].hex(),
        "publicKey": "0x" + derived["publicKey"].hex(),
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

    if args.out or args.in_place:
        target = Path(args.out) if args.out else in_path
        try:
            _write_secure(target, rendered)
        except OSError as e:
            print("ERROR: cannot write {}: {}".format(target, e), file=sys.stderr)
            return 1
        print("{} {}".format("wrote" if args.out else "updated", target), file=sys.stderr)
    else:
        sys.stdout.write(rendered)

    return 0


if __name__ == "__main__":
    sys.exit(main())
