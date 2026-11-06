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
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("event_shapes", Path(__file__).with_name("event-shapes.py"))
event_shapes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(event_shapes)


class BlockFileInputTest(unittest.TestCase):
    def test_discovery_sorts_mixed_widths_numerically_and_excludes_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            names = ["0000000000000000010.blk.zstd", "000000000000000000000000000000000002.blk.gz",
                     "3.blk", "4.pnd.zstd", "5.blk.zstd.tmp"]
            for name in names:
                Path(directory, name).touch()
            self.assertEqual([Path(path).name for path in event_shapes.input_files([directory])],
                             [names[1], names[2], names[0]])

    def test_plain_and_gzip_input_reach_parser(self):
        with tempfile.TemporaryDirectory() as directory:
            for suffix in [".blk", ".blk.gz"]:
                path = Path(directory, "42" + suffix)
                path.write_bytes(gzip.compress(b"block") if suffix.endswith(".gz") else b"block")
                with patch.object(event_shapes, "parse_block", return_value={}) as parse:
                    self.assertEqual({}, event_shapes.parse_file(str(path)))
                    parse.assert_called_once_with(b"block", path.name)

    def test_zstd_uses_checked_subprocess(self):
        path = "42.blk.zstd"
        with patch.object(event_shapes.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"block")) as run:
            with patch.object(event_shapes, "parse_block", return_value={}) as parse:
                event_shapes.parse_file(path)
                run.assert_called_once_with(["zstd", "--decompress", "--stdout", "--", path],
                                            check=True, stdout=subprocess.PIPE)
                parse.assert_called_once_with(b"block", path)

    @unittest.skipUnless(shutil.which("zstd"), "zstd command is unavailable")
    def test_real_zstd_decodes_block_header(self):
        # Block -> BlockItem -> BlockHeader(number=42, block_timestamp=default).
        block = bytes.fromhex("0a060a04182a2200")
        compressed = subprocess.run(["zstd", "--stdout"], input=block, check=True,
                                    stdout=subprocess.PIPE).stdout
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "0000000000000000042.blk.zstd")
            path.write_bytes(compressed)
            self.assertEqual(42, event_shapes.parse_file(str(path))["block"])

    def test_zstd_failure_is_not_parsed(self):
        with patch.object(event_shapes.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "zstd")):
            with patch.object(event_shapes, "parse_block") as parse:
                with self.assertRaises(subprocess.CalledProcessError):
                    event_shapes.parse_file("42.blk.zstd")
                parse.assert_not_called()


if __name__ == "__main__":
    unittest.main()
