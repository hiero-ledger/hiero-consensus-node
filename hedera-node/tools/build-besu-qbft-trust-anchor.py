#!/usr/bin/env python3
"""
Build the trust anchor and ClprLedgerConfiguration bytes that a relay needs to
register a Besu-QBFT-backed CLPR channel.

Inputs (all hex strings, 0x prefix optional):
  - validator address      (20 bytes) — the static QBFT validator on the peer
                                        ledger; authenticates committed seals
  - clpr service address   (20 bytes) — the CLPR service contract on the peer
                                        ledger; pins which account the state
                                        proof must resolve to
  - clpr service code hash (32 bytes) — expected codeHash of that contract;
                                        defends against state-trie spoofs

Outputs:
  - trust_anchor = RLP([validator20, clprService20, codeHash32])
        Consumed by BesuQBFTVerifyBundleCall on the receiving Hiero ledger.

  - clpr_ledger_configuration =
        protobuf-serialized ClprLedgerConfiguration with:
          protocol_version           = --protocol-version  (default: 1)
          chain_id                   = --chain-id          (default: "")
          service_address            = clpr service address
          initial_trust_anchor       = trust_anchor (the RLP blob above)
          initial_trust_anchor_id    = trust_anchor
        — matching what BesuQBFTVerifyConfigCall returns once the bytes
        have been "verified" (currently a pass-through parse + stamp).

The proto definition lived at:
  hapi/hedera-protobuf-java-api/src/main/proto/services/state/clpr/
      clpr_ledger_configuration.proto

By default the throttles + endpoints fields are omitted (valid proto3),
which produces a config the receiving ledger will reject — ClprCompleteChannel
requires both to be populated so the sync orchestrator has rate limits and at
least one peer to dial. Supply them via:
  --throttles-json     '{"max_messages_per_bundle":100, ...}'
  --seed-endpoints-json '[{"ip_address":"127.0.0.1","port":50211, ...}, ...]'
or
  --config-json        path/to/file.json   (single file with both top-level keys)

bridge-from-besu.sh generates the JSON automatically from Besu's on-chain
getLedgerConfiguration() return value.

This script has no third-party dependencies — RLP and protobuf wire formats
are implemented inline.
"""

import argparse
import json
import sys


# -----------------------------------------------------------------------------
# RLP (Ethereum recursive-length prefix) — minimal byte-string + list encoder
# -----------------------------------------------------------------------------

def rlp_encode_bytes(b: bytes) -> bytes:
    if len(b) == 1 and b[0] < 0x80:
        return b
    return _rlp_length_prefix(len(b), 0x80) + b


def rlp_encode_list(items: list) -> bytes:
    payload = b"".join(items)
    return _rlp_length_prefix(len(payload), 0xc0) + payload


def _rlp_length_prefix(length: int, offset: int) -> bytes:
    if length < 56:
        return bytes([offset + length])
    length_bytes = length.to_bytes((length.bit_length() + 7) // 8, "big")
    return bytes([offset + 55 + len(length_bytes)]) + length_bytes


# -----------------------------------------------------------------------------
# Protobuf wire format — varint + length-delimited fields only (no nested
# messages, no repeated fields). Sufficient for our five ClprLedgerConfiguration
# fields.
# -----------------------------------------------------------------------------

_WIRE_VARINT = 0
_WIRE_LEN = 2


def _varint(n: int) -> bytes:
    if n < 0:
        raise ValueError("varint must be non-negative")
    out = bytearray()
    while True:
        b = n & 0x7f
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _tag(field_number: int, wire_type: int) -> bytes:
    return _varint((field_number << 3) | wire_type)


def pb_uint32(field_number: int, value: int) -> bytes:
    """Encode a proto3 uint32 field. Omits the field entirely when value == 0
    (proto3 default-value semantics, which is what the PBJ runtime expects)."""
    if value == 0:
        return b""
    return _tag(field_number, _WIRE_VARINT) + _varint(value)


def pb_string(field_number: int, value: str) -> bytes:
    if not value:
        return b""
    encoded = value.encode("utf-8")
    return _tag(field_number, _WIRE_LEN) + _varint(len(encoded)) + encoded


def pb_bytes(field_number: int, value: bytes) -> bytes:
    if not value:
        return b""
    return _tag(field_number, _WIRE_LEN) + _varint(len(value)) + value


def pb_uint64(field_number: int, value: int) -> bytes:
    """uint64 wire format is identical to uint32 — both use varint."""
    if value == 0:
        return b""
    return _tag(field_number, _WIRE_VARINT) + _varint(value)


def pb_message(field_number: int, encoded: bytes) -> bytes:
    """Wrap an already-serialized nested message (or repeated entry) in a
    length-delimited field. Empty payloads are omitted to mirror proto3
    default-value semantics; pass a non-empty payload to force a present
    (but possibly all-default-fields) sub-message."""
    if not encoded:
        return b""
    return _tag(field_number, _WIRE_LEN) + _varint(len(encoded)) + encoded


# -----------------------------------------------------------------------------
# ClprLedgerConfiguration field numbers (from
# services/state/clpr/clpr_ledger_configuration.proto)
# -----------------------------------------------------------------------------

CLPR_FIELD_PROTOCOL_VERSION = 1
CLPR_FIELD_CHAIN_ID = 2
CLPR_FIELD_SERVICE_ADDRESS = 3
# field 4 = timestamp           (nested message — omitted)
CLPR_FIELD_THROTTLES = 5
CLPR_FIELD_SEED_ENDPOINTS = 6
CLPR_FIELD_INITIAL_TRUST_ANCHOR = 7
CLPR_FIELD_INITIAL_TRUST_ANCHOR_ID = 8

# ClprThrottles fields (all varint; uint32 except gas/sync_bytes which are uint64).
THR_MAX_MESSAGES_PER_BUNDLE = 1
THR_MAX_MESSAGE_PAYLOAD_BYTES = 2
THR_MAX_GAS_PER_MESSAGE = 3
THR_MAX_QUEUE_DEPTH = 4
THR_MAX_SYNC_BYTES = 5
THR_MAX_LOCAL_ENDPOINTS = 6
THR_MAX_PEER_ENDPOINTS = 7

# ClprEndpoint fields. ecdsa_signing_key (formerly field 3) was removed from the
# proto; account_id was renumbered from 4 to 3 to match ClprProtobuf._decodeEndpoint.
EP_SERVICE_ENDPOINT = 1
EP_TLS_CERTIFICATE = 2
EP_ACCOUNT_ID = 3

# ClprServiceEndpoint fields.
SE_IP_ADDRESS = 1
SE_PORT = 2


# -----------------------------------------------------------------------------
# Glue
# -----------------------------------------------------------------------------

def parse_hex_fixed(s: str, expected_len: int, name: str) -> bytes:
    if s.startswith("0x") or s.startswith("0X"):
        s = s[2:]
    if len(s) != expected_len * 2:
        raise ValueError(
            f"{name} must be exactly {expected_len} bytes ({expected_len * 2} hex chars); "
            f"got {len(s)} hex chars")
    try:
        return bytes.fromhex(s)
    except ValueError as e:
        raise ValueError(f"{name} is not valid hex: {e}") from e


def build_trust_anchor(validator20: bytes, service20: bytes, code_hash32: bytes) -> bytes:
    return rlp_encode_list([
        rlp_encode_bytes(validator20),
        rlp_encode_bytes(service20),
        rlp_encode_bytes(code_hash32),
    ])


def _parse_hex_blob(s, name):
    """Allow either "" / None (→ empty bytes) or "0x..." / "..."."""
    if s is None or s == "":
        return b""
    if isinstance(s, (bytes, bytearray)):
        return bytes(s)
    if s.startswith("0x") or s.startswith("0X"):
        s = s[2:]
    try:
        return bytes.fromhex(s)
    except ValueError as e:
        raise ValueError(f"{name} is not valid hex: {e}") from e


def encode_throttles(t: dict) -> bytes:
    """Serialize a ClprThrottles message. Missing keys default to 0 (proto3
    default-value semantics — the field is omitted from the wire format).
    But: presence of the throttles sub-message is required by the handler
    (ClprCompleteChannel rejects configs where throttles is null), so we
    always emit at least the wrapper, even if all inner values are 0."""
    payload = b"".join([
        pb_uint32(THR_MAX_MESSAGES_PER_BUNDLE,    int(t.get("max_messages_per_bundle", 0))),
        pb_uint32(THR_MAX_MESSAGE_PAYLOAD_BYTES,  int(t.get("max_message_payload_bytes", 0))),
        pb_uint64(THR_MAX_GAS_PER_MESSAGE,        int(t.get("max_gas_per_message", 0))),
        pb_uint32(THR_MAX_QUEUE_DEPTH,            int(t.get("max_queue_depth", 0))),
        pb_uint64(THR_MAX_SYNC_BYTES,             int(t.get("max_sync_bytes", 0))),
        pb_uint32(THR_MAX_LOCAL_ENDPOINTS,        int(t.get("max_local_endpoints", 0))),
        pb_uint32(THR_MAX_PEER_ENDPOINTS,         int(t.get("max_peer_endpoints", 0))),
    ])
    # Force the wrapper present (length-delimited tag + length, even if length==0)
    # so the receiving runtime sees a non-null throttles message.
    return _tag(CLPR_FIELD_THROTTLES, _WIRE_LEN) + _varint(len(payload)) + payload


def encode_service_endpoint(ip: str, port: int) -> bytes:
    return b"".join([
        pb_string(SE_IP_ADDRESS, ip or ""),
        pb_uint32(SE_PORT, int(port or 0)),
    ])


def encode_endpoint(ep: dict) -> bytes:
    service_ep = encode_service_endpoint(ep.get("ip_address", ""), ep.get("port", 0))
    return b"".join([
        pb_message(EP_SERVICE_ENDPOINT, service_ep),
        pb_bytes(EP_TLS_CERTIFICATE, _parse_hex_blob(ep.get("tls_certificate", ""), "tls_certificate")),
        pb_bytes(EP_ACCOUNT_ID,      _parse_hex_blob(ep.get("account_id", ""), "account_id")),
    ])


def build_clpr_ledger_configuration(
    *,
    protocol_version,
    chain_id,
    service_address,
    initial_trust_anchor,
    throttles=None,
    endpoints=None,
):
    parts = [
        pb_uint32(CLPR_FIELD_PROTOCOL_VERSION, protocol_version),
        pb_string(CLPR_FIELD_CHAIN_ID, chain_id),
        pb_bytes(CLPR_FIELD_SERVICE_ADDRESS, service_address),
    ]
    if throttles is not None:
        parts.append(encode_throttles(throttles))
    # NOTE: field 6 (seed_endpoints) is intentionally NOT emitted. The endpoint
    # set moved out of ClprLedgerConfiguration into the versioned endpoint
    # manifest, and the strict on-chain decoder (ClprProtobuf.decodeControlMessage)
    # rejects any unrecognized field with ClprUnknownWireField — emitting the
    # retired field 6 makes verifyConfig revert. `endpoints` is accepted but
    # ignored for wire compatibility with existing callers.
    _ = endpoints
    parts.append(pb_bytes(CLPR_FIELD_INITIAL_TRUST_ANCHOR, initial_trust_anchor))
    parts.append(pb_bytes(CLPR_FIELD_INITIAL_TRUST_ANCHOR_ID, initial_trust_anchor))
    return b"".join(parts)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Build the QBFT trust anchor + ClprLedgerConfiguration bytes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--validator", required=True,
                        help="hex-encoded 20-byte QBFT validator address")
    parser.add_argument("--service", required=True,
                        help="hex-encoded 20-byte CLPR service contract address on the peer ledger")
    parser.add_argument("--code-hash", required=True,
                        help="hex-encoded 32-byte expected codeHash of the CLPR service contract")
    parser.add_argument("--chain-id", default="",
                        help="CAIP-2 chain identifier (e.g. 'eip155:1'). Defaults to empty.")
    parser.add_argument("--protocol-version", type=int, default=1,
                        help="ClprLedgerConfiguration.protocol_version (default: 1)")
    parser.add_argument("--throttles-json",
                        help="Inline JSON for ClprThrottles. Keys match the proto field names "
                             "(max_messages_per_bundle, max_syncs_per_sec, max_message_payload_bytes, "
                             "max_gas_per_message, max_queue_depth, max_sync_bytes, max_bundles_per_sec). "
                             "Missing keys default to 0. The throttles wrapper is always emitted when this "
                             "flag is present, so a non-null sub-message reaches the receiver even with all-zero "
                             "fields. Mutually exclusive with --config-json's 'throttles' key.")
    parser.add_argument("--seed-endpoints-json",
                        help="Inline JSON list of ClprEndpoint entries. Each entry: {ip_address, port, "
                             "tls_certificate, ecdsa_signing_key, account_id} (latter three as hex; empty "
                             "strings are valid). Mutually exclusive with --config-json's 'endpoints' key.")
    parser.add_argument("--config-json",
                        help="Path to a JSON file with top-level 'throttles' (object) and/or "
                             "'endpoints' (list). Equivalent to passing --throttles-json + "
                             "--seed-endpoints-json from a file. Useful when bridge-from-besu.sh dumps "
                             "Besu's on-chain getLedgerConfiguration() to disk before calling this tool.")
    parser.add_argument("--out-trust-anchor",
                        help="Write the raw RLP trust-anchor bytes to this file (binary). "
                             "If omitted, the hex form is printed to stdout.")
    parser.add_argument("--out-config",
                        help="Write the raw ClprLedgerConfiguration protobuf bytes to this file "
                             "(binary). If omitted, the hex form is printed to stdout.")
    args = parser.parse_args(argv)

    if args.protocol_version < 0 or args.protocol_version > 0xFFFF_FFFF:
        parser.error("--protocol-version must fit in uint32")

    try:
        validator20 = parse_hex_fixed(args.validator, 20, "--validator")
        service20 = parse_hex_fixed(args.service, 20, "--service")
        code_hash32 = parse_hex_fixed(args.code_hash, 32, "--code-hash")
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    # Resolve throttles + endpoints. Precedence:
    #   1. --throttles-json / --seed-endpoints-json (inline) — explicit wins
    #   2. --config-json file values
    #   3. neither — fields stay null/empty, and the receiver will reject the
    #      proof (CLPR_VERIFIER_CONFIG_FAILED). This is intentional: we emit a
    #      diagnostic warning so the operator notices instead of getting an
    #      opaque NPE later on the sync thread.
    file_cfg = {}
    if args.config_json:
        try:
            with open(args.config_json) as f:
                file_cfg = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            print(f"ERROR: could not read --config-json {args.config_json}: {e}", file=sys.stderr)
            return 2

    throttles = None
    if args.throttles_json:
        try:
            throttles = json.loads(args.throttles_json)
        except json.JSONDecodeError as e:
            print(f"ERROR: --throttles-json is not valid JSON: {e}", file=sys.stderr)
            return 2
    elif "throttles" in file_cfg:
        throttles = file_cfg["throttles"]

    endpoints = None
    if args.seed_endpoints_json:
        try:
            endpoints = json.loads(args.seed_endpoints_json)
        except json.JSONDecodeError as e:
            print(f"ERROR: --seed-endpoints-json is not valid JSON: {e}", file=sys.stderr)
            return 2
    elif "endpoints" in file_cfg:
        endpoints = file_cfg["endpoints"]

    if throttles is None or not endpoints:
        # Not fatal here (the tool's job is to encode whatever it's given), but the
        # receiving ledger will reject. Loudly warn so the operator notices.
        print(
            "WARNING: ClprCompleteChannel rejects configs without throttles or endpoints. "
            f"throttles={'set' if throttles is not None else 'missing'}, "
            f"endpoints={len(endpoints) if endpoints else 0}.",
            file=sys.stderr,
        )

    trust_anchor = build_trust_anchor(validator20, service20, code_hash32)
    config = build_clpr_ledger_configuration(
        protocol_version=args.protocol_version,
        chain_id=args.chain_id,
        service_address=service20,
        initial_trust_anchor=trust_anchor,
        throttles=throttles,
        endpoints=endpoints,
    )

    if args.out_trust_anchor:
        with open(args.out_trust_anchor, "wb") as f:
            f.write(trust_anchor)
        print(f"wrote {len(trust_anchor)} bytes of trust anchor to {args.out_trust_anchor}",
              file=sys.stderr)
    else:
        print(f"trust_anchor (0x, {len(trust_anchor)} bytes):")
        print("0x" + trust_anchor.hex())

    if args.out_config:
        with open(args.out_config, "wb") as f:
            f.write(config)
        print(f"wrote {len(config)} bytes of ClprLedgerConfiguration to {args.out_config}",
              file=sys.stderr)
    else:
        print(f"clpr_ledger_configuration (0x, {len(config)} bytes):")
        print("0x" + config.hex())

    return 0


if __name__ == "__main__":
    sys.exit(main())
