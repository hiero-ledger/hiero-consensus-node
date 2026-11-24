# SPDX-License-Identifier: Apache-2.0
"""Build the Sei bridge's update from yahcli get-ledger-configuration --include-defaults."""

import base64
import json
import sys
from pathlib import Path


def bridge_config(response, service_hex):
    throttles = response["configuration"]["throttles"].copy()
    for field in (
        "max_messages_per_bundle",
        "max_message_payload_bytes",
        "max_queue_depth",
        "max_sync_bytes",
    ):
        if field not in throttles:
            raise ValueError(f"Missing advertised throttle: {field}")
    throttles["max_gas_per_message"] = 1_000_000
    if service_hex.startswith(("0x", "0X")):
        service_hex = service_hex[2:]
    if len(service_hex) % 2:
        service_hex = "0" + service_hex
    address = bytes.fromhex(service_hex)
    return {"service_address": base64.b64encode(address).decode("ascii"), "throttles": throttles}


if __name__ == "__main__":
    source, service_hex, destination = sys.argv[1:]
    config = bridge_config(json.loads(Path(source).read_text()), service_hex)
    Path(destination).write_text(json.dumps(config, indent=2) + "\n")
