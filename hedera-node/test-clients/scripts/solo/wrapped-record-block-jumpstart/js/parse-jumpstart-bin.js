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

if (b.length < 68) fail(`jumpstart.bin too small: ${b.length} bytes (expected at least 68)`);

const blockNum = b.readBigInt64BE(0);
const prevHash = b.subarray(8, 56).toString("hex");
const leafCount = b.readBigInt64BE(56);
const hashCount = b.readInt32BE(64);

if (hashCount < 0) fail(`Invalid negative hashCount ${hashCount}`);

const expected = 68 + (hashCount * 48);
if (b.length !== expected) {
  fail(`jumpstart.bin size mismatch: got ${b.length}, expected ${expected} (hashCount=${hashCount})`);
}

const subtreeHashes = [];
let offset = 68;
for (let i = 0; i < hashCount; i += 1) {
  subtreeHashes.push(b.subarray(offset, offset + 48).toString("hex"));
  offset += 48;
}

console.log(`JUMPSTART_BLOCK_NUMBER=${blockNum.toString()}`);
console.log(`JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH=${prevHash}`);
console.log(`JUMPSTART_STREAMING_HASHER_LEAF_COUNT=${leafCount.toString()}`);
console.log(`JUMPSTART_STREAMING_HASHER_HASH_COUNT=${hashCount}`);
console.log(`JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES=${subtreeHashes.join(",")}`);
