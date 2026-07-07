// SPDX-License-Identifier: Apache-2.0
//
// Parses the JSON printed by the hedera-state-validator `introspect` command for the
// BlockRecordService:BLOCKS (BlockInfo) singleton and emits shell-friendly KEY=value lines.
//
// The validator logs to SYSTEM_OUT (log4j) and the introspector also prints the singleton as
// JSON on stdout, so the captured output is a mix of log lines and one JSON object. We locate
// the JSON object (the one carrying "lastBlockNumber") and convert its base64 byte fields to
// lowercase hex so they can be compared directly against the block-node wrap jumpstart values.
const fs = require("fs");

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

const file = process.argv[2];
if (!file) fail("Missing validator output file argument");

let text;
try {
  text = fs.readFileSync(file, "utf8");
} catch (e) {
  fail(`Unable to read validator output '${file}': ${e.message}`);
}

// Returns the balanced {...} substring starting at startBrace, or null if unbalanced.
function extractBalanced(s, startBrace) {
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = startBrace; i < s.length; i += 1) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === '"') inStr = false;
    } else if (c === '"') {
      inStr = true;
    } else if (c === "{") {
      depth += 1;
    } else if (c === "}") {
      depth -= 1;
      if (depth === 0) return s.slice(startBrace, i + 1);
    }
  }
  return null;
}

// Scan every '{' until we find a balanced object that parses and carries lastBlockNumber.
let obj = null;
for (let idx = text.indexOf("{"); idx >= 0; idx = text.indexOf("{", idx + 1)) {
  const candidate = extractBalanced(text, idx);
  if (!candidate) continue;
  try {
    const parsed = JSON.parse(candidate);
    if (parsed && Object.prototype.hasOwnProperty.call(parsed, "lastBlockNumber")) {
      obj = parsed;
      break;
    }
  } catch (e) {
    // not JSON (log noise) - keep scanning
  }
}

if (!obj) {
  fail("Could not locate BlockInfo JSON (an object with \"lastBlockNumber\") in the validator output");
}

function b64ToHex(b64) {
  if (b64 === undefined || b64 === null || b64 === "") return "";
  return Buffer.from(String(b64), "base64").toString("hex");
}

const blockNumber = obj.lastBlockNumber != null ? String(obj.lastBlockNumber) : "";
const prevHash = b64ToHex(obj.previousWrappedRecordBlockRootHash);
const interArr = Array.isArray(obj.wrappedIntermediatePreviousBlockRootHashes)
  ? obj.wrappedIntermediatePreviousBlockRootHashes
  : [];
const interHashes = interArr.map(b64ToHex).join(",");
const leafCount = obj.wrappedIntermediateBlockRootsLeafCount != null
  ? String(obj.wrappedIntermediateBlockRootsLeafCount)
  : "0";

if (!/^[0-9]+$/.test(blockNumber)) {
  fail(`BlockInfo.lastBlockNumber missing or non-numeric: '${blockNumber}'`);
}

console.log(`STATE_BI_BLOCK_NUMBER=${blockNumber}`);
console.log(`STATE_BI_PREV_HASH=${prevHash}`);
console.log(`STATE_BI_INTERMEDIATE_HASHES=${interHashes}`);
console.log(`STATE_BI_LEAF_COUNT=${leafCount}`);
