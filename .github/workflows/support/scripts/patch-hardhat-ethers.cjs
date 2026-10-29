// SPDX-License-Identifier: Apache-2.0

// Temporary workaround for https://github.com/hashgraph/hedera-evm-testing/issues/155.
// Remove when the test suite adopts a verified upstream fix. Patch only the installed CI dependency.
const { createHash } = require("node:crypto");
const { readFileSync, writeFileSync } = require("node:fs");
const { resolve } = require("node:path");

const VERSION = "3.1.0";
// Published hardhat-ethers 3.1.0 signers.js, from sendTransaction through its closing brace.
const ORIGINAL_METHOD_SHA256 =
  "2e5d1f0088924c0224b144336837a849da9ef41ea6e5f86c0c5ed6af775db507";
const METHOD_START = "    async sendTransaction(tx) {";
const METHOD_END = "\n    signMessage(";

// This function is inserted as a class method; keep it self-contained.
async function sendTransaction(tx) {
  const blockNumber = await this.provider.getBlockNumber();
  const hash = await this._sendUncheckedTransaction(tx);

  // Only lookups are retried. Never submit the transaction a second time.
  const timeoutMs = 30_000;
  const delays = [100, 1000];
  let lastLookupError;
  let deadlineError;
  let deadlineTimer;
  let delayTimer;
  const deadline = new Promise((_, reject) => {
    deadlineTimer = setTimeout(() => {
      deadlineError = new Error(`Transaction lookup exceeded ${timeoutMs}ms`, {
        cause: lastLookupError,
      });
      reject(deadlineError);
    }, timeoutMs);
  });
  const isTransient = (error) =>
    [error, error?.cause].some(
      (cause) =>
        [
          "NETWORK_ERROR",
          "TIMEOUT",
          "ECONNRESET",
          "ECONNREFUSED",
          "ETIMEDOUT",
          "EAI_AGAIN",
          "EPIPE",
          "UND_ERR_CONNECT_TIMEOUT",
          "UND_ERR_HEADERS_TIMEOUT",
          "UND_ERR_SOCKET",
        ].includes(cause?.code) ||
        [429, 502, 503, 504].includes(
          cause?.status ??
            cause?.statusCode ??
            cause?.response?.status ??
            cause?.info?.response?.statusCode,
        ),
    );

  try {
    for (;;) {
      let transaction = null;
      try {
        // Also bound a lookup that never settles, not just repeated null responses.
        transaction = await Promise.race([
          this.provider.getTransaction(hash),
          deadline,
        ]);
      } catch (error) {
        if (error === deadlineError || !isTransient(error)) {
          throw error;
        }
        lastLookupError = error;
        console.warn(
          `Retrying lookup for submitted transaction ${hash}: ${error?.message ?? String(error)}`,
        );
      }
      if (transaction !== null) {
        // Formatting/processing failures must reject the caller, not silently stop polling.
        return transaction.replaceableTransaction(blockNumber);
      }
      await Promise.race([
        new Promise((resolveDelay) => {
          delayTimer = setTimeout(resolveDelay, delays.shift() ?? 4000);
        }),
        deadline,
      ]);
    }
  } catch (error) {
    throw new Error(
      `Unable to retrieve submitted transaction ${hash}: ${error?.message ?? String(error)}`,
      {
        cause: error,
      },
    );
  } finally {
    clearTimeout(deadlineTimer);
    clearTimeout(delayTimer);
  }
}

const replacement = sendTransaction
  .toString()
  .replace(/^async function /, "async ")
  .split("\n")
  .map((line) => `    ${line}`)
  .join("\n");

function patchSignerSource(source) {
  const start = source.indexOf(METHOD_START);
  const end = source.indexOf(METHOD_END, start);
  if (start < 0 || end < 0 || source.indexOf(METHOD_START, start + 1) !== -1) {
    throw new Error(
      "Unrecognized hardhat-ethers signer layout; review or remove the polling workaround",
    );
  }
  const current = source.slice(start, end);
  if (current === replacement) {
    return source;
  }
  if (
    createHash("sha256").update(current).digest("hex") !==
    ORIGINAL_METHOD_SHA256
  ) {
    throw new Error(
      "Unexpected hardhat-ethers sendTransaction implementation; review or remove the polling workaround",
    );
  }
  return source.slice(0, start) + replacement + source.slice(end);
}

function applyWorkaround(packageDirectory) {
  const directory = resolve(packageDirectory);
  const metadata = JSON.parse(
    readFileSync(resolve(directory, "package.json"), "utf8"),
  );
  if (
    metadata.name !== "@nomicfoundation/hardhat-ethers" ||
    metadata.version !== VERSION
  ) {
    throw new Error(
      `Expected @nomicfoundation/hardhat-ethers@${VERSION}, found ${metadata.name}@${metadata.version}; ` +
        "review or remove the polling workaround before upgrading",
    );
  }
  const file = resolve(directory, "signers.js");
  const source = readFileSync(file, "utf8");
  const patched = patchSignerSource(source);
  if (patched !== source) {
    writeFileSync(file, patched);
  }
  console.log(`Hardhat transaction polling workaround applied to ${file}`);
}

if (require.main === module) {
  if (process.argv.length !== 3) {
    throw new Error(
      "Usage: node patch-hardhat-ethers.cjs <installed hardhat-ethers package directory>",
    );
  }
  applyWorkaround(process.argv[2]);
}

module.exports = { applyWorkaround, patchSignerSource };
