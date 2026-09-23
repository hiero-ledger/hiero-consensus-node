#!/usr/bin/env python3
"""
Build the Besu QBFT ClprQbftLedgerConfigurationPayload consumed by the
Hiero Besu-QBFT verifier.

This deliberately does not set ClprLedgerConfiguration.initial_trust_anchor or
initial_trust_anchor_id. The verifier derives those from the proven genesis
validator, CLPR service address, and CLPR service account code hash.
"""

import argparse
import importlib.util
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request


COMMON_PATH = Path(__file__).with_name("build-besu-qbft-trust-anchor.py")
spec = importlib.util.spec_from_file_location("besu_qbft_common", COMMON_PATH)
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

# SC-189 moved _config to storage slot 23 (adding _endpointManifest / _peerEndpointManifests
# before it), so _config.serviceAddress = base 23 + field offset 2 = slot 25.
CONFIG_SERVICE_ADDRESS_SLOT = 25


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


def hex_to_bytes(value, name):
    if value is None:
        raise ValueError(f"{name} is missing")
    if value.startswith(("0x", "0X")):
        value = value[2:]
    if value == "":
        return b""
    if len(value) % 2:
        value = "0" + value
    try:
        return bytes.fromhex(value)
    except ValueError as e:
        raise ValueError(f"{name} is not valid hex: {e}") from e


def hex_to_int(value):
    if value is None:
        return 0
    if value.startswith(("0x", "0X")):
        value = value[2:]
    return 0 if value == "" else int(value, 16)


def int_to_minimal_bytes(value):
    if value == 0:
        return b""
    return value.to_bytes((value.bit_length() + 7) // 8, "big")


def rlp_int(value):
    return common.rlp_encode_bytes(int_to_minimal_bytes(value))


def block_field(block, name):
    value = block.get(name)
    if value is None:
        raise ValueError(f"block header is missing {name}")
    return value


def optional_present(block, name):
    return name in block and block[name] is not None


def encode_block_header_rlp(block):
    fields = [
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "parentHash"), "parentHash")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "sha3Uncles"), "sha3Uncles")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "miner"), "miner")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "stateRoot"), "stateRoot")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "transactionsRoot"), "transactionsRoot")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "receiptsRoot"), "receiptsRoot")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "logsBloom"), "logsBloom")),
        rlp_int(hex_to_int(block_field(block, "difficulty"))),
        rlp_int(hex_to_int(block_field(block, "number"))),
        rlp_int(hex_to_int(block_field(block, "gasLimit"))),
        rlp_int(hex_to_int(block_field(block, "gasUsed"))),
        rlp_int(hex_to_int(block_field(block, "timestamp"))),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "extraData"), "extraData")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "mixHash"), "mixHash")),
        common.rlp_encode_bytes(hex_to_bytes(block_field(block, "nonce"), "nonce")),
    ]

    if not optional_present(block, "baseFeePerGas"):
        return common.rlp_encode_list(fields)
    fields.append(rlp_int(hex_to_int(block["baseFeePerGas"])))

    if not optional_present(block, "withdrawalsRoot"):
        return common.rlp_encode_list(fields)
    fields.append(common.rlp_encode_bytes(hex_to_bytes(block["withdrawalsRoot"], "withdrawalsRoot")))

    if not optional_present(block, "blobGasUsed") or not optional_present(block, "excessBlobGas"):
        return common.rlp_encode_list(fields)
    fields.append(rlp_int(hex_to_int(block["blobGasUsed"])))
    fields.append(rlp_int(hex_to_int(block["excessBlobGas"])))

    if not optional_present(block, "parentBeaconBlockRoot"):
        return common.rlp_encode_list(fields)
    fields.append(common.rlp_encode_bytes(hex_to_bytes(block["parentBeaconBlockRoot"], "parentBeaconBlockRoot")))

    if not optional_present(block, "requestsHash"):
        return common.rlp_encode_list(fields)
    fields.append(common.rlp_encode_bytes(hex_to_bytes(block["requestsHash"], "requestsHash")))

    if not optional_present(block, "blockAccessListHash"):
        return common.rlp_encode_list(fields)
    fields.append(common.rlp_encode_bytes(hex_to_bytes(block["blockAccessListHash"], "blockAccessListHash")))

    if not optional_present(block, "slotNumber"):
        return common.rlp_encode_list(fields)
    fields.append(rlp_int(hex_to_int(block["slotNumber"])))

    return common.rlp_encode_list(fields)


def get_block(url, block_tag, allow_latest_fallback):
    try:
        result = json_rpc(url, "eth_getBlockByNumber", [block_tag, False])
        if result is None:
            raise RuntimeError(f"eth_getBlockByNumber({block_tag}) returned null")
        return result, block_tag
    except RuntimeError as e:
        if allow_latest_fallback and block_tag != "latest":
            print(f"WARNING: {e}; falling back to latest", file=sys.stderr)
            result = json_rpc(url, "eth_getBlockByNumber", ["latest", False])
            if result is None:
                raise RuntimeError("eth_getBlockByNumber(latest) returned null")
            return result, "latest"
        raise


def build_ledger_configuration(protocol_version, chain_id, service20, throttles, endpoints):
    parts = [
        common.pb_uint32(common.CLPR_FIELD_PROTOCOL_VERSION, protocol_version),
        common.pb_string(common.CLPR_FIELD_CHAIN_ID, chain_id),
        common.pb_bytes(common.CLPR_FIELD_SERVICE_ADDRESS, service20),
    ]
    if throttles is not None:
        parts.append(common.encode_throttles(throttles))
    # Field 6 (seed_endpoints) IS emitted here on purpose: this payload feeds the
    # Hiero-side BesuQBFT verifier (system contract), whose verifyConfig bootstraps
    # the channel's endpoint manifest from these seed endpoints when no real
    # endpoint-manifest state proof is supplied (BesuQBFTVerifyConfigCall
    # "seed-fallback" bring-up, synthesized from parsed.endpoints()). Unlike the
    # strict SC-189 Solidity decoder — which rejects field 6 (see
    # build-besu-qbft-trust-anchor.py) — the Hiero verifier requires it for bring-up.
    for endpoint in endpoints or []:
        parts.append(common.pb_message(common.CLPR_FIELD_SEED_ENDPOINTS, common.encode_endpoint(endpoint)))
    return b"".join(parts)


def encode_block_header_message(header_rlp):
    return common.pb_bytes(1, header_rlp)


def encode_storage_proof_entry(entry):
    parts = [
        common.pb_bytes(1, hex_to_bytes(entry["key"], "storageProof.key")),
        common.pb_bytes(2, hex_to_bytes(entry["value"], "storageProof.value")),
    ]
    for node in entry.get("proof", []):
        parts.append(common.pb_bytes(3, hex_to_bytes(node, "storageProof.proof[]")))
    return b"".join(parts)


def load_config_json(path):
    if not path:
        return {}, None, []
    with open(path) as f:
        file_cfg = json.load(f)
    return file_cfg, file_cfg.get("throttles"), file_cfg.get("endpoints", [])


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build a Besu QBFT ledger-configuration proof payload.")
    parser.add_argument("--rpc-url", required=True, help="Besu JSON-RPC URL")
    parser.add_argument("--service", required=True, help="hex-encoded 20-byte CLPR service contract address")
    parser.add_argument("--chain-id", default="", help="CAIP-2 chain identifier")
    parser.add_argument("--protocol-version", type=int, default=1, help="CLPR protocol version")
    parser.add_argument("--config-json", help="JSON file with throttles and endpoints")
    parser.add_argument("--block-tag", default="finalized", help="block tag for current header/proof")
    parser.add_argument("--no-latest-fallback", action="store_true", help="do not fall back to latest")
    parser.add_argument("--out-payload", help="write raw payload bytes to this file")
    args = parser.parse_args(argv)

    if args.protocol_version < 0 or args.protocol_version > 0xFFFF_FFFF:
        parser.error("--protocol-version must fit in uint32")

    service20 = common.parse_hex_fixed(args.service, 20, "--service")
    rpc_service = args.service if args.service.startswith(("0x", "0X")) else "0x" + args.service
    _, throttles, endpoints = load_config_json(args.config_json)

    current_block, resolved_tag = get_block(args.rpc_url, args.block_tag, not args.no_latest_fallback)
    current_block_tag = "0x" + format(hex_to_int(current_block["number"]), "x")
    genesis_block, _ = get_block(args.rpc_url, "0x0", False)

    slot = "0x" + format(CONFIG_SERVICE_ADDRESS_SLOT, "064x")
    proof = json_rpc(args.rpc_url, "eth_getProof", [rpc_service, [slot], current_block_tag])
    storage_proofs = proof.get("storageProof", [])
    if len(storage_proofs) != 1:
        raise RuntimeError(f"eth_getProof returned {len(storage_proofs)} storage proofs, expected 1")

    ledger_config = build_ledger_configuration(args.protocol_version, args.chain_id, service20, throttles, endpoints)
    genesis_header = encode_block_header_message(encode_block_header_rlp(genesis_block))
    current_header = encode_block_header_message(encode_block_header_rlp(current_block))

    parts = [
        common.pb_message(1, genesis_header),
        common.pb_message(2, ledger_config),
        common.pb_message(3, current_header),
    ]
    for node in proof.get("accountProof", []):
        parts.append(common.pb_bytes(4, hex_to_bytes(node, "accountProof[]")))
    for entry in storage_proofs:
        parts.append(common.pb_message(5, encode_storage_proof_entry(entry)))
    payload = b"".join(parts)

    if args.out_payload:
        with open(args.out_payload, "wb") as f:
            f.write(payload)
        print(f"wrote {len(payload)} bytes of QBFT config payload to {args.out_payload}", file=sys.stderr)

    print(f"resolved_block_tag: {resolved_tag}")
    print(f"current_block_number: {current_block_tag}")
    print(f"qbft_ledger_configuration_payload (0x, {len(payload)} bytes):")
    print("0x" + payload.hex())
    return 0


if __name__ == "__main__":
    sys.exit(main())
