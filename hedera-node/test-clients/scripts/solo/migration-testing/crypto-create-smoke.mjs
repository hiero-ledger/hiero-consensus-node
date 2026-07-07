// SPDX-License-Identifier: Apache-2.0
//
// CryptoCreate smoke test for the migration-testing regression panel.
// Submits an AccountCreateTransaction via @hashgraph/sdk against the upgraded
// consensus network, then polls Mirror Node REST API
// (`/api/v1/transactions/<id>`) until result == "SUCCESS" or the timeout
// elapses. Exit code is non-zero on any failure.
//
// Required env vars (set by the caller, normally solo-migration-test.sh):
//
//   GRPC_ENDPOINT          host:port of haproxy-node1-svc port-forward
//   MIRROR_REST_URL        e.g. http://127.0.0.1:5551
//   OPERATOR_ACCOUNT_ID    e.g. "0.0.2"
//   OPERATOR_PRIVATE_KEY   Ed25519 private key (DER hex)
//   POLL_TIMEOUT_MS        e.g. 60000

import {
  Client,
  AccountCreateTransaction,
  Hbar,
  PrivateKey,
  Status,
} from "@hashgraph/sdk";

const grpc           = process.env.GRPC_ENDPOINT;
const mirror         = process.env.MIRROR_REST_URL;
const opId           = process.env.OPERATOR_ACCOUNT_ID;
const opKey          = process.env.OPERATOR_PRIVATE_KEY;
const pollTimeoutMs  = Number(process.env.POLL_TIMEOUT_MS || "60000");

if (!grpc || !mirror || !opId || !opKey) {
  console.error("GRPC_ENDPOINT, MIRROR_REST_URL, OPERATOR_ACCOUNT_ID, OPERATOR_PRIVATE_KEY all required");
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const client = Client.forNetwork({ [grpc]: "0.0.3" });
client.setOperator(opId, PrivateKey.fromString(opKey));
client.setMaxAttempts(3);
client.setRequestTimeout(20000);

console.log("--> creating account via CryptoCreate");
const tx = new AccountCreateTransaction()
  .setKeyWithoutAlias(PrivateKey.generateED25519().publicKey)
  .setInitialBalance(new Hbar(1))
  .setMaxTransactionFee(new Hbar(5));
const resp    = await tx.execute(client);
const receipt = await resp.getReceipt(client);
if (receipt.status !== Status.Success) {
  throw new Error(`CryptoCreate consensus status: ${receipt.status.toString()}`);
}

// SDK toString: "0.0.2@123456789.987654321"
// Mirror REST:  "0.0.2-123456789-987654321"
const [acct, ts]       = resp.transactionId.toString().split("@");
const [secs, nanos]    = ts.split(".");
const mirrorTxId       = `${acct}-${secs}-${nanos}`;
console.log(`    consensus OK: account=${receipt.accountId.toString()}  tx=${mirrorTxId}`);

const url = `${mirror}/api/v1/transactions/${mirrorTxId}`;
console.log(`--> polling ${url}`);
const deadline = Date.now() + pollTimeoutMs;
let attempt = 0;
let found   = null;
while (Date.now() < deadline) {
  attempt++;
  try {
    const r = await fetch(url);
    if (r.ok) {
      const j = await r.json();
      const t = (j.transactions || [])[0];
      if (t && t.result === "SUCCESS") { found = t; break; }
    }
  } catch (_) { /* keep polling */ }
  await sleep(2000);
}

await client.close();

if (!found) {
  console.error(`FAIL: mirror did not return result=SUCCESS within ${pollTimeoutMs}ms (${attempt} attempts)`);
  process.exit(1);
}
console.log(`    mirror OK: result=${found.result} (${attempt} attempts)`);
