const fs = require("fs");
const path = require("path");

const FIRST_BLOCK_TIME = "2019-09-13T21:53:51.396440Z";

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

function parseTimestampToEpochNanos(tsLike) {
  const ts = String(tsLike).replace(/_/g, ":");
  const m = ts.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/);
  if (!m) throw new Error(`Invalid timestamp format: ${tsLike}`);

  const [, y, mo, d, h, mi, s, fracRaw = ""] = m;
  const ms = Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s));
  const epochSeconds = BigInt(Math.floor(ms / 1000));
  const fracNanos = BigInt((fracRaw + "000000000").slice(0, 9));
  return (epochSeconds * 1_000_000_000n) + fracNanos;
}

function recordNameToEpochNanos(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  return parseTimestampToEpochNanos(base.slice(0, z + 1));
}

function dayFromRecordName(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  return base.slice(0, z + 1).replace(/_/g, ":").slice(0, 10);
}

function resolveNextUrl(base, next) {
  if (!next) return "";
  if (next.startsWith("http://") || next.startsWith("https://")) return next;
  if (next.startsWith("/")) return `${base}${next}`;
  return `${base}/${next}`;
}

async function fetchAllBlocksUpTo(mirrorBase, maxBlock) {
  const blocks = [];
  let nextUrl = `${mirrorBase}/api/v1/blocks?order=asc&limit=100`;

  while (nextUrl) {
    const response = await fetch(nextUrl);
    if (!response.ok) throw new Error(`HTTP ${response.status} from ${nextUrl}`);

    const body = await response.json();
    const page = Array.isArray(body.blocks) ? body.blocks : [];
    if (page.length === 0) break;

    for (const b of page) {
      const n = Number(b.number);
      if (!Number.isFinite(n)) continue;
      if (n > maxBlock) return blocks;
      blocks.push({ number: n, name: b.name || "", hash: String(b.hash || "").replace(/^0x/i, "") });
    }

    const lastNumber = Number(page[page.length - 1].number);
    if (Number.isFinite(lastNumber) && lastNumber >= maxBlock) break;
    nextUrl = resolveNextUrl(mirrorBase, body.links && body.links.next);
  }

  return blocks;
}

function ensureNoBlockGaps(sortedBlocks) {
  if (sortedBlocks.length < 2) return;
  for (let i = 1; i < sortedBlocks.length; i += 1) {
    const expected = sortedBlocks[i - 1].number + 1;
    const actual = sortedBlocks[i].number;
    if (actual !== expected) throw new Error(`Gap in mirror blocks: expected ${expected}, got ${actual}`);
  }
}

async function main() {
  const mirrorBase = String(process.env.MIRROR_REST_URL || "http://127.0.0.1:5551").replace(/\/$/, "");
  const maxBlockRaw = process.env.MIRROR_BLOCK_NUMBER;
  const blockTimesFile = process.env.BLOCK_TIMES_FILE;
  const dayBlocksFile = process.env.DAY_BLOCKS_FILE;

  if (!maxBlockRaw) fail("MIRROR_BLOCK_NUMBER is required");
  if (!blockTimesFile) fail("BLOCK_TIMES_FILE is required");
  if (!dayBlocksFile) fail("DAY_BLOCKS_FILE is required");

  const maxBlock = Number(maxBlockRaw);
  if (!Number.isInteger(maxBlock) || maxBlock < 0) fail(`Invalid MIRROR_BLOCK_NUMBER: ${maxBlockRaw}`);

  const blocks = await fetchAllBlocksUpTo(mirrorBase, maxBlock);
  if (blocks.length === 0) fail("Mirror returned no blocks for metadata generation");

  blocks.sort((a, b) => a.number - b.number);
  ensureNoBlockGaps(blocks);

  const highest = blocks[blocks.length - 1].number;
  if (highest < maxBlock) fail(`Mirror highest fetched block ${highest} is below requested ${maxBlock}`);

  const firstEpochNanos = parseTimestampToEpochNanos(FIRST_BLOCK_TIME);
  const buf = Buffer.alloc((maxBlock + 1) * 8);
  const byDay = new Map();

  for (const b of blocks) {
    const epochNanos = recordNameToEpochNanos(b.name);
    const blockTime = epochNanos - firstEpochNanos;
    if (blockTime < 0n) fail(`Negative block time for block ${b.number}`);

    buf.writeBigInt64BE(blockTime, b.number * 8);

    const day = dayFromRecordName(b.name);
    const [year, month, dayNum] = day.split("-").map(Number);
    const prev = byDay.get(day);
    if (!prev) {
      byDay.set(day, {
        year,
        month,
        day: dayNum,
        firstBlockNumber: b.number,
        firstBlockHash: b.hash,
        lastBlockNumber: b.number,
        lastBlockHash: b.hash,
      });
    } else {
      prev.lastBlockNumber = b.number;
      prev.lastBlockHash = b.hash;
    }
  }

  fs.mkdirSync(path.dirname(blockTimesFile), { recursive: true });
  fs.mkdirSync(path.dirname(dayBlocksFile), { recursive: true });
  fs.writeFileSync(blockTimesFile, buf);

  const dayBlocks = Array.from(byDay.values()).sort(
    (a, b) => a.year - b.year || a.month - b.month || a.day - b.day,
  );
  fs.writeFileSync(dayBlocksFile, `${JSON.stringify(dayBlocks, null, 2)}\n`);
  console.log(`PASS: generated ${blockTimesFile} and ${dayBlocksFile}`);
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
