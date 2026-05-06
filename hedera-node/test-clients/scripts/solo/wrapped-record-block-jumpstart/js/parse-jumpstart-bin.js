const fs = require("fs");

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

const file = process.argv[2];
if (!file) fail("Missing jumpstart.bin path argument");

let b;
try {
  b = fs.readFileSync(file);
} catch (e) {
  fail(`Unable to read jumpstart file '${file}': ${e.message}`);
}

// Layout written by hiero-block-node ToWrappedBlocksCommand.saveJumpstartData (post #2612):
//   [0..7]    blockNumber                (long, 8 bytes)
//   [8..55]   previousWrappedBlockHash   (SHA-384, 48 bytes)
//   [56..103] consensusTimestampHash     (SHA-384, 48 bytes)   -- added by #2612
//   [104..151] outputItemsTreeRootHash   (SHA-384, 48 bytes)   -- added by #2612
//   [152..159] streamingHasherLeafCount  (long, 8 bytes)
//   [160..163] streamingHasherHashCount  (int,  4 bytes)
//   [164..]    streamingHasher subtree hashes (48 bytes each)
const HEADER_SIZE = 164;
const HASH_BYTES = 48;

if (b.length < HEADER_SIZE) {
  fail(`jumpstart.bin too small: ${b.length} bytes (expected at least ${HEADER_SIZE})`);
}

const blockNum = b.readBigInt64BE(0);
const prevHash = b.subarray(8, 56).toString("hex");
const consensusTimestampHash = b.subarray(56, 104).toString("hex");
const outputItemsTreeRootHash = b.subarray(104, 152).toString("hex");
const leafCount = b.readBigInt64BE(152);
const hashCount = b.readInt32BE(160);

if (hashCount < 0) fail(`Invalid negative hashCount ${hashCount}`);

const expected = HEADER_SIZE + (hashCount * HASH_BYTES);
if (b.length !== expected) {
  fail(`jumpstart.bin size mismatch: got ${b.length}, expected ${expected} (hashCount=${hashCount})`);
}

const subtreeHashes = [];
let offset = HEADER_SIZE;
for (let i = 0; i < hashCount; i += 1) {
  subtreeHashes.push(b.subarray(offset, offset + HASH_BYTES).toString("hex"));
  offset += HASH_BYTES;
}

console.log(`JUMPSTART_BLOCK_NUMBER=${blockNum.toString()}`);
console.log(`JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH=${prevHash}`);
console.log(`JUMPSTART_CONSENSUS_TIMESTAMP_HASH=${consensusTimestampHash}`);
console.log(`JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH=${outputItemsTreeRootHash}`);
console.log(`JUMPSTART_STREAMING_HASHER_LEAF_COUNT=${leafCount.toString()}`);
console.log(`JUMPSTART_STREAMING_HASHER_HASH_COUNT=${hashCount}`);
console.log(`JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES=${subtreeHashes.join(",")}`);
