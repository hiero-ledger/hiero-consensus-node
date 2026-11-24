# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

spec = importlib.util.spec_from_file_location(
    "besu_payload", Path(__file__).with_name("build-besu-qbft-config-payload.py")
)
besu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(besu)


class BesuConfigPayloadTest(unittest.TestCase):
    def test_proof_identifies_besu_when_hiero_is_the_deployment_peer(self):
        besu_id, hiero_id = "eip155:1337", "eip155:298"
        with TemporaryDirectory() as directory:
            config = Path(directory) / "besu-ledger-config.json"
            config.write_text(json.dumps({"chain_id": besu_id, "throttles": {}}))
            chain_id, throttles = besu.load_config_json(config, None)
            encoded = besu.build_ledger_configuration(1, chain_id, bytes(20), throttles)
            self.assertIn(besu.common.pb_string(besu.common.CLPR_FIELD_CHAIN_ID, besu_id), encoded)
            with self.assertRaisesRegex(ValueError, "differs from the Besu ledger configuration"):
                besu.load_config_json(config, hiero_id)

    def test_explicit_chain_id_still_works_with_a_throttles_only_file(self):
        with TemporaryDirectory() as directory:
            config = Path(directory) / "throttles.json"
            config.write_text('{"throttles": {"max_peer_endpoints": 4}}')
            self.assertEqual(
                besu.load_config_json(config, "eip155:1337"),
                ("eip155:1337", {"max_peer_endpoints": 4}),
            )


if __name__ == "__main__":
    unittest.main()
