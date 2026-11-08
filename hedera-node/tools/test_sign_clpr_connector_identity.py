#!/usr/bin/env python3
"""Tests for sign-clpr-connector-identity.py security fixes.

Run with:  python -m pytest test_sign_clpr_connector_identity.py -v
Requires:  pip install pycryptodome pytest
"""

import json
import os
import stat
import sys
import tempfile
from pathlib import Path

import importlib.util
import pytest

_script_path = Path(__file__).parent / "sign-clpr-connector-identity.py"
_spec = importlib.util.spec_from_file_location("sign_clpr_connector_identity", _script_path)
script = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(script)


# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

def _ed25519_key_bytes() -> bytes:
    from Crypto.PublicKey import ECC
    seed = os.urandom(32)
    ECC.construct(curve="Ed25519", seed=seed)
    return seed


def _make_bundle(channel_id: bytes, private_key: bytes, salt: bytes) -> dict:
    """Minimal connector-identity bundle (public fields only — no privateKey)."""
    pub = script.ed25519_pubkey(private_key)
    connector_id = script.keccak256(channel_id + pub + salt)
    commitment = script.keccak256(connector_id + pub)
    sig_hash = script.keccak256(connector_id + script.DEFAULT_SERVICE_ADDRESS)
    sig = script.ed25519_sign(private_key, sig_hash)
    return {
        "channelId": "0x" + channel_id.hex(),
        "connectorId": "0x" + connector_id.hex(),
        "publicKey": "0x" + pub.hex(),
        "salt": "0x" + salt.hex(),
        "signatureScheme": "ED25519",
        "serviceAddress": "0x" + script.DEFAULT_SERVICE_ADDRESS.hex(),
        "commitment": "0x" + commitment.hex(),
        "signature": "0x" + sig.hex(),
    }


# ---------------------------------------------------------------------------
# Tests: output omits privateKey
# ---------------------------------------------------------------------------

class TestOutputOmitsPrivateKey:
    def test_out_file_has_no_private_key_field(self, tmp_path):
        """Output JSON written to --out must not contain a privateKey field."""
        channel_id = os.urandom(32)
        salt = os.urandom(32)
        priv = _ed25519_key_bytes()
        bundle = _make_bundle(channel_id, priv, salt)

        in_file = tmp_path / "connector.json"
        in_file.write_text(json.dumps(bundle))

        key_file = tmp_path / "connector.json.key"
        key_file.write_text("0x" + priv.hex())

        out_file = tmp_path / "connector-out.json"
        rc = script.main([str(in_file), "--key-file", str(key_file), "--out", str(out_file)])
        assert rc == 0

        result = json.loads(out_file.read_text())
        assert "privateKey" not in result

    def test_in_place_has_no_private_key_field(self, tmp_path):
        """--in-place update must not write privateKey back into the file."""
        channel_id = os.urandom(32)
        salt = os.urandom(32)
        priv = _ed25519_key_bytes()
        bundle = _make_bundle(channel_id, priv, salt)

        in_file = tmp_path / "connector.json"
        in_file.write_text(json.dumps(bundle))

        key_file = tmp_path / "connector.json.key"
        key_file.write_text("0x" + priv.hex())

        rc = script.main([str(in_file), "--key-file", str(key_file), "--in-place"])
        assert rc == 0

        result = json.loads(in_file.read_text())
        assert "privateKey" not in result


# ---------------------------------------------------------------------------
# Tests: --key-file flag
# ---------------------------------------------------------------------------

class TestKeyFileFlag:
    def test_key_file_flag_is_accepted(self, tmp_path):
        """Script must accept --key-file and use it for the private key."""
        channel_id = os.urandom(32)
        salt = os.urandom(32)
        priv = _ed25519_key_bytes()
        bundle = _make_bundle(channel_id, priv, salt)

        in_file = tmp_path / "connector.json"
        in_file.write_text(json.dumps(bundle))

        key_file = tmp_path / "connector.json.key"
        key_file.write_text("0x" + priv.hex())

        out_file = tmp_path / "out.json"
        rc = script.main([str(in_file), "--key-file", str(key_file), "--out", str(out_file)])
        assert rc == 0

    def test_errors_when_no_key_source(self, tmp_path):
        """Script must error when bundle has no privateKey and --key-file is absent."""
        channel_id = os.urandom(32)
        salt = os.urandom(32)
        priv = _ed25519_key_bytes()
        bundle = _make_bundle(channel_id, priv, salt)
        # No privateKey in bundle, no --key-file.

        in_file = tmp_path / "connector.json"
        in_file.write_text(json.dumps(bundle))

        rc = script.main([str(in_file)])
        assert rc != 0


# ---------------------------------------------------------------------------
# Tests: output file permissions
# ---------------------------------------------------------------------------

class TestOutputFilePermissions:
    def test_out_file_is_owner_only(self, tmp_path):
        """--out file must be created with mode 0o600 regardless of umask."""
        channel_id = os.urandom(32)
        salt = os.urandom(32)
        priv = _ed25519_key_bytes()
        bundle = _make_bundle(channel_id, priv, salt)

        in_file = tmp_path / "connector.json"
        in_file.write_text(json.dumps(bundle))

        key_file = tmp_path / "connector.json.key"
        key_file.write_text("0x" + priv.hex())

        out_file = tmp_path / "out.json"

        old_umask = os.umask(0o022)
        try:
            rc = script.main([str(in_file), "--key-file", str(key_file), "--out", str(out_file)])
        finally:
            os.umask(old_umask)

        assert rc == 0
        mode = stat.S_IMODE(out_file.stat().st_mode)
        assert mode == 0o600, f"expected 0o600, got 0o{mode:o}"

    def test_in_place_file_is_owner_only(self, tmp_path):
        """--in-place must enforce 0o600 on the updated file."""
        channel_id = os.urandom(32)
        salt = os.urandom(32)
        priv = _ed25519_key_bytes()
        bundle = _make_bundle(channel_id, priv, salt)

        in_file = tmp_path / "connector.json"
        in_file.write_text(json.dumps(bundle))
        in_file.chmod(0o644)

        key_file = tmp_path / "connector.json.key"
        key_file.write_text("0x" + priv.hex())

        old_umask = os.umask(0o022)
        try:
            rc = script.main([str(in_file), "--key-file", str(key_file), "--in-place"])
        finally:
            os.umask(old_umask)

        assert rc == 0
        mode = stat.S_IMODE(in_file.stat().st_mode)
        assert mode == 0o600, f"expected 0o600, got 0o{mode:o}"
