# SPDX-License-Identifier: Apache-2.0
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class SeiBridgeConfigTest(unittest.TestCase):
    def test_update_carries_the_live_advertisement_and_endpoint_limits(self):
        generator = Path(__file__).with_name("build-sei-bridge-config.py")
        with tempfile.TemporaryDirectory() as directory:
            live = Path(directory) / "current.json"
            update = Path(directory) / "update.json"
            for count, size, depth, sync in (
                (1000, 65536, 10000, "4194304"),
                (2000, 90000, 20000, "8388608"),
            ):
                with self.subTest(depth=depth):
                    throttles = {
                        "max_messages_per_bundle": count,
                        "max_message_payload_bytes": size,
                        "max_queue_depth": depth,
                        "max_sync_bytes": sync,
                        "max_gas_per_message": "15000000",
                        "max_peer_endpoints": 77,
                    }
                    response = {
                        "configuration": {"throttles": throttles},
                        "configurationStateProof": "AA==",
                    }
                    live.write_text(json.dumps(response))
                    subprocess.run(
                        [sys.executable, str(generator), str(live), "0x016e", str(update)],
                        check=True,
                    )
                    self.assertEqual(
                        json.loads(update.read_text()),
                        {
                            "service_address": "AW4=",
                            "throttles": {**throttles, "max_gas_per_message": 1_000_000},
                        },
                    )


if __name__ == "__main__":
    unittest.main()
