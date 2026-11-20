#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run with python3 -m unittest discover -s platform-sdk/consensus-benchmark-tools/scripts."""
import gzip
import importlib.util
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("event_shapes", Path(__file__).with_name("event-shapes.py"))
event_shapes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(event_shapes)

# Block -> BlockItem -> BlockHeader(number=42, block_timestamp=default).
BLOCK = bytes.fromhex("0a060a04182a2200")


class BlockFileInputTest(unittest.TestCase):
    def test_discovery_sorts_mixed_widths_numerically_and_excludes_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            names = ["0000000000000000010.blk.zstd", "000000000000000000000000000000000002.blk.gz",
                     "3.blk", "4.pnd.zstd", "5.blk.zstd.tmp"]
            for name in names:
                Path(directory, name).touch()
            self.assertEqual([Path(path).name for path in event_shapes.input_files([directory])],
                             [names[1], names[2], names[0]])

    def test_explicit_file_with_non_numeric_name_is_kept(self):
        self.assertEqual(event_shapes.input_files(["sample.blk.gz", "2.blk"]), ["sample.blk.gz", "2.blk"])

    def test_plain_and_gzip_decode_block_header(self):
        with tempfile.TemporaryDirectory() as directory:
            for suffix in [".blk", ".blk.gz"]:
                path = Path(directory, "42" + suffix)
                path.write_bytes(gzip.compress(BLOCK) if suffix.endswith(".gz") else BLOCK)
                self.assertEqual(42, event_shapes.parse_file(str(path))["block"])

    @unittest.skipUnless(shutil.which("zstd"), "zstd command is unavailable")
    def test_real_zstd_decodes_block_header(self):
        compressed = subprocess.run(["zstd", "--stdout"], input=BLOCK, check=True,
                                    stdout=subprocess.PIPE).stdout
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "0000000000000000042.blk.zstd")
            path.write_bytes(compressed)
            self.assertEqual(42, event_shapes.parse_file(str(path))["block"])

    @unittest.skipUnless(shutil.which("zstd"), "zstd command is unavailable")
    def test_corrupt_zstd_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "42.blk.zstd")
            path.write_bytes(b"not a zstandard frame")
            with self.assertRaises(subprocess.CalledProcessError):
                event_shapes.parse_file(str(path))


if __name__ == "__main__":
    unittest.main()
