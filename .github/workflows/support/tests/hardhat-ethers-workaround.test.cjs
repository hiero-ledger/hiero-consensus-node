// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} = require("node:fs");
const { tmpdir } = require("node:os");
const { resolve } = require("node:path");
const { setImmediate: flush } = require("node:timers/promises");
const { test } = require("node:test");
const { runInNewContext } = require("node:vm");
const {
  applyWorkaround,
  patchSignerSource,
  patchProviderSource,
} = require("../scripts/patch-hardhat-ethers.cjs");

// Exercise the patched method from the real npm-installed package, without starting Hardhat or a network.
// For local runs, this can also point at an extracted npm pack @nomicfoundation/hardhat-ethers@3.1.0.
const packageDirectory = process.env.HARDHAT_ETHERS_PACKAGE_DIR;
assert.ok(
  packageDirectory,
  "Set HARDHAT_ETHERS_PACKAGE_DIR to the hardhat-ethers 3.1.0 package directory",
);
const original = readFileSync(resolve(packageDirectory, "signers.js"), "utf8");
const metadata = readFileSync(
  resolve(packageDirectory, "package.json"),
  "utf8",
);
const patched = patchSignerSource(original);
const providerPath = "internal/hardhat-ethers-provider.js";
const originalProvider = readFileSync(
  resolve(packageDirectory, providerPath),
  "utf8",
);
const patchedProvider = patchProviderSource(originalProvider);

function harness(t, lookup) {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const start = patched.indexOf("    async sendTransaction(tx) {");
  const end = patched.indexOf("\n    signMessage(", start);
  const warnings = [];
  const signer = runInNewContext(`({${patched.slice(start, end)}})`, {
    setTimeout,
    clearTimeout,
    Promise,
    Error,
    console: { warn: (message) => warnings.push(message) },
  });
  const submissions = [];
  signer.provider = { getBlockNumber: async () => 42, getTransaction: lookup };
  signer._sendUncheckedTransaction = async (tx) => {
    submissions.push(tx);
    return "0xsubmitted";
  };
  return { signer, submissions, warnings };
}

async function tick(t, milliseconds) {
  t.mock.timers.tick(milliseconds);
  await flush();
}

function packageCopy(t) {
  const directory = mkdtempSync(
    resolve(tmpdir(), "hardhat-ethers-workaround-"),
  );
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(resolve(directory, "package.json"), metadata);
  writeFileSync(resolve(directory, "signers.js"), original);
  mkdirSync(resolve(directory, "internal"));
  writeFileSync(resolve(directory, providerPath), originalProvider);
  return directory;
}

test("patches the published package on disk and is idempotent", (t) => {
  const directory = packageCopy(t);
  applyWorkaround(directory);
  assert.equal(readFileSync(resolve(directory, "signers.js"), "utf8"), patched);
  assert.equal(
    readFileSync(resolve(directory, providerPath), "utf8"),
    patchedProvider,
  );
  applyWorkaround(directory);
  assert.equal(readFileSync(resolve(directory, "signers.js"), "utf8"), patched);
  assert.equal(
    readFileSync(resolve(directory, providerPath), "utf8"),
    patchedProvider,
  );
});

test("refuses a dependency upgrade without modifying the installed source", (t) => {
  const directory = packageCopy(t);
  writeFileSync(
    resolve(directory, "package.json"),
    JSON.stringify({
      ...JSON.parse(metadata),
      version: "3.1.1",
    }),
  );
  assert.throws(
    () => applyWorkaround(directory),
    /review or remove.*before upgrading/,
  );
  assert.equal(
    readFileSync(resolve(directory, "signers.js"), "utf8"),
    original,
  );
});

test("refuses unexpected source or an ambiguous method location", () => {
  assert.throws(
    () =>
      patchSignerSource(
        original.replace(
          "const timeouts = [1000, 100]",
          "const timeouts = [1]",
        ),
      ),
    /Unexpected.*implementation/,
  );
  assert.throws(
    () => patchSignerSource("different package"),
    /Unrecognized.*layout/,
  );
  assert.throws(
    () => patchSignerSource(original + "    async sendTransaction(tx) {"),
    /Unrecognized.*layout/,
  );
  assert.throws(
    () =>
      patchProviderSource(
        originalProvider + "    async getTransaction(hash) {",
      ),
    /Unrecognized.*layout/,
  );
});

test("validates provider source before modifying either installed file", (t) => {
  const directory = packageCopy(t);
  const changedProvider = originalProvider.replace(
    "[hash]);",
    "[hash.toLowerCase()]);",
  );
  writeFileSync(resolve(directory, providerPath), changedProvider);
  assert.throws(
    () => applyWorkaround(directory),
    /Unexpected.*getTransaction.*implementation/,
  );
  assert.equal(
    readFileSync(resolve(directory, "signers.js"), "utf8"),
    original,
  );
  assert.equal(
    readFileSync(resolve(directory, providerPath), "utf8"),
    changedProvider,
  );
});

test("returns the replaceable transaction and sends the original input exactly once", async (t) => {
  const tx = { to: "0xrecipient", value: 1n };
  const result = { hash: "0xsubmitted" };
  let calls = 0;
  const { signer, submissions } = harness(t, async (hash) => {
    calls++;
    assert.equal(hash, "0xsubmitted");
    return {
      replaceableTransaction: (block) => {
        assert.equal(block, 42);
        return result;
      },
    };
  });
  assert.equal(await signer.sendTransaction(tx), result);
  assert.deepEqual(submissions, [tx]);
  await tick(t, 60_000);
  assert.equal(calls, 1);
});

test("polls null responses until the submitted transaction becomes visible", async (t) => {
  let calls = 0;
  const { signer, submissions } = harness(t, async () =>
    ++calls < 3 ? null : { replaceableTransaction: () => "found" },
  );
  const pending = signer.sendTransaction({});
  await flush();
  await tick(t, 100);
  await tick(t, 1000);
  assert.equal(await pending, "found");
  assert.equal(calls, 3);
  assert.equal(submissions.length, 1);
  await tick(t, 60_000);
  assert.equal(calls, 3);
});

for (const transient of [
  Object.assign(new Error("connection reset"), { code: "ECONNRESET" }),
  new Error("wrapped transport error", {
    cause: Object.assign(new Error("network"), { code: "ETIMEDOUT" }),
  }),
  Object.assign(new Error("service unavailable"), {
    info: { response: { statusCode: 503 } },
  }),
]) {
  test(`retries ${transient.message} without resubmitting`, async (t) => {
    let calls = 0;
    const { signer, submissions, warnings } = harness(t, async () => {
      if (++calls === 1) throw transient;
      return { replaceableTransaction: () => "found" };
    });
    const pending = signer.sendTransaction({});
    await flush();
    await tick(t, 100);
    assert.equal(await pending, "found");
    assert.equal(submissions.length, 1);
    assert.equal(calls, 2);
    assert.match(warnings[0], /0xsubmitted/);
    assert.ok(warnings[0].includes(transient.message));
  });
}

test("rejects an unexpected lookup error after null responses, preserving the error and hash", async (t) => {
  const cause = Object.assign(new Error("invalid transaction response"), {
    code: "BAD_DATA",
  });
  let calls = 0;
  const { signer, submissions, warnings } = harness(t, async () => {
    if (++calls < 3) return null;
    throw cause;
  });
  const rejected = assert.rejects(signer.sendTransaction({}), (error) => {
    assert.match(error.message, /0xsubmitted.*invalid transaction response/);
    assert.equal(error.cause, cause);
    return true;
  });
  await flush();
  await tick(t, 100);
  await tick(t, 1000);
  await rejected;
  await tick(t, 60_000);
  assert.equal(calls, 3);
  assert.equal(submissions.length, 1);
  assert.deepEqual(warnings, []);
});

test("rejects response-processing errors instead of retrying or leaving the caller pending", async (t) => {
  const cause = new Error("cannot wrap transaction");
  const { signer, submissions } = harness(t, async () => ({
    replaceableTransaction: () => {
      throw cause;
    },
  }));
  await assert.rejects(signer.sendTransaction({}), (error) => {
    assert.match(error.message, /0xsubmitted.*cannot wrap transaction/);
    assert.equal(error.cause, cause);
    return true;
  });
  assert.equal(submissions.length, 1);
  await tick(t, 60_000);
});

for (const scenario of ["null", "transient error", "hung lookup"]) {
  test(`bounds ${scenario} by the total deadline and stops polling`, async (t) => {
    const cause = Object.assign(new Error("network unavailable"), {
      code: "NETWORK_ERROR",
    });
    let calls = 0;
    let finishHungLookup;
    const { signer, submissions } = harness(t, async () => {
      calls++;
      if (scenario === "null") return null;
      if (scenario === "transient error") throw cause;
      return new Promise((_, reject) => {
        finishHungLookup = reject;
      });
    });
    const rejected = assert.rejects(signer.sendTransaction({}), (error) => {
      assert.match(error.message, /0xsubmitted.*exceeded 30000ms/);
      if (scenario === "transient error")
        assert.equal(error.cause.cause, cause);
      return true;
    });
    await flush();
    await tick(t, 100);
    await tick(t, 1000);
    await tick(t, 28_900);
    await rejected;
    const stoppedAt = calls;
    // Late RPC rejection must be handled, with no background polling or resubmission.
    finishHungLookup?.(new Error("late failure"));
    await tick(t, 60_000);
    assert.equal(calls, stoppedAt);
    assert.equal(submissions.length, 1);
  });
}

test("propagates a submission failure without retrying or starting lookups", async (t) => {
  const cause = new Error("submission failed");
  let sends = 0;
  const { signer } = harness(t, async () => assert.fail("unexpected lookup"));
  signer._sendUncheckedTransaction = async () => {
    sends++;
    throw cause;
  };
  await assert.rejects(signer.sendTransaction({}), (error) => error === cause);
  await tick(t, 60_000);
  assert.equal(sends, 1);
});

function providerHarness(send, format, diagnosticsFile) {
  const errors = [];
  const warnings = [];
  const start = patchedProvider.indexOf("    async getTransaction(hash) {");
  const end = patchedProvider.indexOf(
    "\n    async getTransactionReceipt(",
    start,
  );
  const provider = runInNewContext(`({${patchedProvider.slice(start, end)}})`, {
    Date,
    require,
    process: { env: { HARDHAT_ETHERS_DIAGNOSTICS_FILE: diagnosticsFile } },
    console: {
      error: (message) => errors.push(message),
      warn: (message) => warnings.push(message),
    },
    ethers_utils_1: { formatTransactionResponse: format },
  });
  provider._hardhatProvider = { send };
  provider._wrapTransactionResponse = (tx) => tx;
  return { provider, errors, warnings };
}

test("captures the original malformed RPC result, without another lookup, and preserves the signer error", async (t) => {
  const file = resolve(packageCopy(t), "transaction-lookup-failures.jsonl");
  const raw = {
    hash: "0xsubmitted",
    r: "0x",
    s: "0x",
    v: "0x0",
    input: "0xabcdef",
    blockHash: "0xblock",
    transactionIndex: "0x1",
    type: "0x0",
  };
  const expected = structuredClone(raw);
  const cause = Object.assign(new Error("Cannot convert 0x to a BigInt"), {
    code: "INVALID_ARGUMENT",
  });
  let calls = 0;
  const { provider, errors, warnings } = providerHarness(
    async (method, params) => {
      calls++;
      assert.equal(method, "eth_getTransactionByHash");
      assert.deepEqual(Array.from(params), ["0xsubmitted"]);
      return raw;
    },
    (result) => {
      result.input = "formatter mutated this";
      throw cause;
    },
    file,
  );
  const { signer, submissions } = harness(t, (hash) =>
    provider.getTransaction(hash),
  );
  await assert.rejects(signer.sendTransaction({}), (error) => {
    assert.equal(error.cause, cause);
    assert.match(error.message, /0xsubmitted.*Cannot convert 0x/);
    return true;
  });
  const lines = readFileSync(file, "utf8").trim().split("\n");
  assert.equal(lines.length, 1);
  const diagnostic = JSON.parse(lines[0]);
  assert.deepEqual(diagnostic.result, expected);
  assert.equal(diagnostic.resultCaptured, true);
  assert.equal(diagnostic.method, "eth_getTransactionByHash");
  assert.deepEqual(diagnostic.params, ["0xsubmitted"]);
  assert.equal(diagnostic.error.code, "INVALID_ARGUMENT");
  assert.equal(diagnostic.error.stack, cause.stack);
  assert.ok(Number.isFinite(Date.parse(diagnostic.requestedAt)));
  assert.ok(
    Date.parse(diagnostic.receivedAt) >= Date.parse(diagnostic.requestedAt),
  );
  assert.deepEqual(errors, [
    `[hardhat-ethers transaction response] ${lines[0]}`,
  ]);
  assert.deepEqual(warnings, []);
  await tick(t, 60_000);
  assert.equal(calls, 1);
  assert.equal(submissions.length, 1);
});

test("keeps successful and null lookups quiet and preserves their results", async () => {
  const tx = { hash: "0xvalid" };
  const formatted = { hash: "0xformatted" };
  const { provider, errors, warnings } = providerHarness(
    async () => tx,
    (result) => {
      assert.equal(result, tx);
      return formatted;
    },
  );
  assert.equal(await provider.getTransaction(tx.hash), formatted);
  provider._hardhatProvider.send = async () => null;
  assert.equal(await provider.getTransaction("0xabsent"), null);
  assert.deepEqual(errors, []);
  assert.deepEqual(warnings, []);
});

test("appends separate diagnostics for concurrent lookups and wrapper failures", async (t) => {
  const file = resolve(packageCopy(t), "transaction-lookup-failures.jsonl");
  const cause = new Error("cannot wrap response");
  const { provider } = providerHarness(
    async (_method, [hash]) => ({ hash }),
    (tx) => tx,
    file,
  );
  provider._wrapTransactionResponse = () => {
    throw cause;
  };
  await Promise.all(
    ["0xfirst", "0xsecond"].map((hash) =>
      assert.rejects(provider.getTransaction(hash), (error) => error === cause),
    ),
  );
  const records = readFileSync(file, "utf8").trim().split("\n").map(JSON.parse);
  assert.equal(records.length, 2);
  assert.deepEqual(records.map((record) => record.params[0]).sort(), [
    "0xfirst",
    "0xsecond",
  ]);
  for (const record of records)
    assert.equal(record.result.hash, record.params[0]);
});

test("does not mask a parser error when the diagnostic file cannot be written", async (t) => {
  const file = resolve(
    packageCopy(t),
    "missing-directory",
    "diagnostics.jsonl",
  );
  const cause = new Error("invalid signature");
  const { provider, errors, warnings } = providerHarness(
    async () => ({ r: "0x" }),
    () => {
      throw cause;
    },
    file,
  );
  await assert.rejects(
    provider.getTransaction("0xsubmitted"),
    (error) => error === cause,
  );
  assert.equal(errors.length, 1);
  assert.match(errors[0], /"r":"0x"/);
  assert.match(warnings[0], /Unable to save transaction response diagnostics/);
});

test("preserves transport errors without claiming to have received an RPC result", async () => {
  const cause = new Error("RPC unavailable");
  const { provider, errors } = providerHarness(
    async () => {
      throw cause;
    },
    () => assert.fail("unexpected formatting"),
  );
  await assert.rejects(
    provider.getTransaction("0xsubmitted"),
    (error) => error === cause,
  );
  assert.deepEqual(errors, []);
});
