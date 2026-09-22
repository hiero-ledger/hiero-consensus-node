#!/usr/bin/env python3
"""
Build a ClprSeiLedgerConfigurationPayload for the Hiero Sei verifier.

The payload shape is:
  ClprSeiLedgerConfigurationPayload {
    initial_validator_set = validator set fetched from CometBFT RPC   (field 1)
    initial_validator_set_height = the height that set was read at     (field 2)
    ledger_configuration = ClprLedgerConfiguration {                  (field 3)
      protocol_version, chain_id, service_address, throttles, endpoints
    }
  }

The initial trust anchor the verifier derives has id
`hash(initial_validator_set) || initial_validator_set_height`.

This mirrors clpr-evm-endpoint's SeiLedgerConfigurationProvider, but is kept as
a small stdlib-only tool so yahcli setup scripts can complete a channel
before the relay loop is started.
"""

import argparse
import base64
import importlib.util
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request


COMMON_PATH = Path(__file__).with_name("build-besu-qbft-trust-anchor.py")
spec = importlib.util.spec_from_file_location("clpr_common", COMMON_PATH)
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def json_rpc(url, method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode())
    except (urllib.error.URLError, TimeoutError) as e:
        raise RuntimeError(f"{method} failed against {url}: {e}") from e
    if "error" in payload:
        raise RuntimeError(f"{method} returned JSON-RPC error: {payload['error']}")
    return payload.get("result")


def status_chain_id(tm_rpc):
    result = json_rpc(tm_rpc, "status", {})
    network = result.get("node_info", {}).get("network", "")
    if not network:
        raise RuntimeError("status response did not include node_info.network")
    height = int(result.get("sync_info", {}).get("latest_block_height", "0") or "0")
    return network, height


def fetch_validator_set(tm_rpc, height):
    result = json_rpc(
        tm_rpc,
        "validators",
        {"height": str(height), "per_page": "100", "page": "1"},
    )
    validators = result.get("validators", [])
    total = int(result.get("total", len(validators)) or len(validators))
    if len(validators) < total:
        raise RuntimeError(
            f"validator set at height {height} has {total} validators; this helper supports up to 100"
        )
    if not validators:
        raise RuntimeError(f"validator set at height {height} is empty")

    entries = []
    for idx, validator in enumerate(validators):
        pub_key = validator.get("pub_key", {})
        key_type = pub_key.get("type", "")
        if "Ed25519" not in key_type:
            raise RuntimeError(f"validator {idx} has unsupported pub_key.type={key_type!r}")
        key_bytes = base64.b64decode(pub_key.get("value", ""))
        if len(key_bytes) != 32:
            raise RuntimeError(f"validator {idx} pubkey is {len(key_bytes)} bytes, expected 32")
        voting_power = int(validator.get("voting_power", "0") or "0")
        if voting_power <= 0:
            raise RuntimeError(f"validator {idx} voting_power must be positive")
        entries.append(encode_validator_entry(key_bytes, voting_power))

    return b"".join(common.pb_message(1, entry) for entry in entries)


def encode_validator_entry(ed25519_pubkey, voting_power):
    return b"".join(
        [
            common.pb_bytes(1, ed25519_pubkey),
            common.pb_uint64(2, voting_power),
        ]
    )


def build_ledger_configuration(protocol_version, chain_id, service20, throttles, endpoints):
    parts = [
        common.pb_uint32(common.CLPR_FIELD_PROTOCOL_VERSION, protocol_version),
        common.pb_string(common.CLPR_FIELD_CHAIN_ID, chain_id),
        common.pb_bytes(common.CLPR_FIELD_SERVICE_ADDRESS, service20),
    ]
    if throttles is not None:
        parts.append(common.encode_throttles(throttles))
    for endpoint in endpoints or []:
        parts.append(common.pb_message(common.CLPR_FIELD_SEED_ENDPOINTS, common.encode_endpoint(endpoint)))
    return b"".join(parts)


def load_config_json(path):
    if not path:
        return None, []
    with open(path) as f:
        file_cfg = json.load(f)
    return file_cfg.get("throttles"), file_cfg.get("endpoints", [])


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build a Sei ledger-configuration proof payload.")
    parser.add_argument("--tm-rpc", required=True, help="CometBFT RPC URL, e.g. http://localhost:26657")
    parser.add_argument("--service", required=True, help="20-byte CLPR service EVM address")
    parser.add_argument("--chain-id", default="", help="CLPR ledger chain id, e.g. cosmos:sei")
    parser.add_argument("--protocol-version", type=int, default=1, help="CLPR protocol version")
    parser.add_argument("--validator-height", type=int, default=0, help="CometBFT validator height; default latest")
    parser.add_argument("--config-json", help="JSON file with throttles and endpoints")
    parser.add_argument("--out-payload", help="write raw payload bytes to this file")
    args = parser.parse_args(argv)

    if args.protocol_version < 0 or args.protocol_version > 0xFFFF_FFFF:
        parser.error("--protocol-version must fit in uint32")

    try:
        service20 = common.parse_hex_fixed(args.service, 20, "--service")
        network, latest_height = status_chain_id(args.tm_rpc)
        validator_height = args.validator_height or latest_height
        if validator_height <= 0:
            raise RuntimeError("could not determine a positive validator height")
        chain_id = args.chain_id or f"cosmos:{network}"
        throttles, endpoints = load_config_json(args.config_json)
        if throttles is None or not endpoints:
            print(
                "WARNING: ClprCompleteChannel rejects configs without throttles or endpoints. "
                f"throttles={'set' if throttles is not None else 'missing'}, "
                f"endpoints={len(endpoints) if endpoints else 0}.",
                file=sys.stderr,
            )

        validator_set = fetch_validator_set(args.tm_rpc, validator_height)
        ledger_config = build_ledger_configuration(
            args.protocol_version, chain_id, service20, throttles, endpoints
        )
        payload = b"".join(
            [
                common.pb_message(1, validator_set),
                common.pb_uint64(2, validator_height),
                common.pb_message(3, ledger_config),
            ]
        )
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    if args.out_payload:
        with open(args.out_payload, "wb") as f:
            f.write(payload)
        print(f"wrote {len(payload)} bytes of Sei config payload to {args.out_payload}", file=sys.stderr)

    print(f"cometbft_network: {network}")
    print(f"validator_height: {validator_height}")
    print(f"sei_ledger_configuration_payload (0x, {len(payload)} bytes):")
    print("0x" + payload.hex())
    return 0


if __name__ == "__main__":
    sys.exit(main())
