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

// Layout written by hiero-block-node ToWrappedBlocksCommand.saveJumpstartData (post #2612). Its hashes
// are SHA-256 since hiero-block-node#3753, which switched them from SHA-384:
//   [0..7]     blockNumber                (long, 8 bytes)
//   [8..39]    previousWrappedBlockHash   (SHA-256, 32 bytes)
//   [40..71]   consensusTimestampHash     (SHA-256, 32 bytes)   -- added by #2612
//   [72..103]  outputItemsTreeRootHash    (SHA-256, 32 bytes)   -- added by #2612
//   [104..111] streamingHasherLeafCount   (long, 8 bytes)
//   [112..115] streamingHasherHashCount   (int,  4 bytes)
//   [116..]    streamingHasher subtree hashes (32 bytes each)
const HASH_BYTES = 32;
const LEAF_COUNT_OFFSET = 8 + 3 * HASH_BYTES;
const HASH_COUNT_OFFSET = LEAF_COUNT_OFFSET + 8;
const HEADER_SIZE = HASH_COUNT_OFFSET + 4;

if (b.length < HEADER_SIZE) {
  fail(`jumpstart.bin too small: ${b.length} bytes (expected at least ${HEADER_SIZE})`);
}

const blockNum = b.readBigInt64BE(0);
const prevHash = b.subarray(8, 8 + HASH_BYTES).toString("hex");
const consensusTimestampHash = b.subarray(8 + HASH_BYTES, 8 + 2 * HASH_BYTES).toString("hex");
const outputItemsTreeRootHash = b.subarray(8 + 2 * HASH_BYTES, LEAF_COUNT_OFFSET).toString("hex");
const leafCount = b.readBigInt64BE(LEAF_COUNT_OFFSET);
const hashCount = b.readInt32BE(HASH_COUNT_OFFSET);

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
