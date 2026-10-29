// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const { mkdtempSync, readFileSync, rmSync, writeFileSync } = require("node:fs");
const { tmpdir } = require("node:os");
const { resolve } = require("node:path");
const { setImmediate: flush } = require("node:timers/promises");
const { test } = require("node:test");
const { runInNewContext } = require("node:vm");
const {
  applyWorkaround,
  patchSignerSource,
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
  return directory;
}

test("patches the published package on disk and is idempotent", (t) => {
  const directory = packageCopy(t);
  applyWorkaround(directory);
  assert.equal(readFileSync(resolve(directory, "signers.js"), "utf8"), patched);
  applyWorkaround(directory);
  assert.equal(readFileSync(resolve(directory, "signers.js"), "utf8"), patched);
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
