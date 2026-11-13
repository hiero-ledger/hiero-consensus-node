#!/usr/bin/env python3
"""
Compute the ownership commitment and reveal signature for a CLPR channel
identity bundle (the JSON file produced by `yahcli clpr generate-channel-identity`
or written by hand).

Input file (e.g. channel.json) must contain at least:
  - channelId   (32 bytes, hex, 0x prefix optional)
  - signatureScheme  one of: "ED25519", "ECDSA" (alias), "ECDSA_SECP256K1"

The script derives the matching public key, then computes:
  publicKey            = derived from the private key per scheme
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
# ECDSA secp256k1: in-process via coincurve (libsecp256k1 bindings).
# Keys are never passed on argv — no process-visible leak.
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
    """Emit the channel-identity JSON in the same field order yahcli uses.

    The private key never appears in the bundle — it lives in the companion .key file.
    """
    ordered: dict[str, str] = {
        "channelId": _ensure_0x(bundle["channelId"]),
        "publicKey": "0x" + derived["publicKey"].hex(),
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
    except ValueError as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 2

    try:
        derived = derive(channel_id, private_key, scheme)
    except (RuntimeError, ValueError) as e:
        print("ERROR: {}".format(e), file=sys.stderr)
        return 1

    rendered = render_json(bundle, derived, scheme)

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
