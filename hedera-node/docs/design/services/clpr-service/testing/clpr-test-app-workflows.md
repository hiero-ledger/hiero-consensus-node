# CLPR Application Workflow Test Definitions

This file covers tests for real-world application patterns built on CLPR: token transfers, NFT
transfers, escrow, multi-ledger atomic swaps, and a lightweight DEX. These tests deploy application
contracts on multiple ledgers and exercise the full stack from user action through CLPR message flow
to cross-ledger settlement and response.

The audience for these tests is developers building the CLPR application library and reference
application implementations. These tests validate that the patterns described in the CLPR design
document (§3.4) are implementable and behave correctly end-to-end.

> These test definitions are subject to change as the CLPR specification evolves during implementation.

---

# 1. Common Test Infrastructure

## 1.1 Requirements

- **Two or more networks** (Hiero-to-Hiero for initial testing; Hiero-to-Ethereum for cross-platform
  workflows). Deployed via SOLO or equivalent.
- **Application contracts** deployed on all involved ledgers: token escrow, mint/burn, NFT escrow/mint,
  DEX order book, swap coordinator.
- **Funded user accounts** on all ledgers with the tokens to be transferred or swapped.
- **Funded Connectors** on all involved Connections.
- **Balance query capability** on all ledgers to verify asset movements before and after each operation.

## 1.2 Common Verification Procedures

**Balance snapshot.** Before and after each workflow, record token balances for all involved accounts
on all involved ledgers. The difference must match the expected transfer amounts.

**Response correlation.** Each workflow sends one or more CLPR messages. The test must wait for all
responses and verify that each response carries the expected status and reply data.

**Escrow state verification.** For lock-and-mint and conditional escrow patterns, verify the escrow
contract's state at each phase: assets locked, assets released, or assets returned.

---

# 2. Fungible Token Transfer: Lock-and-Mint

**Reference:** clpr-test-spec.md §6.1.1

**Purpose:** Verify the lock-and-mint pattern for transferring fungible tokens between ledgers.

**Properties to Test:**

- P1: Tokens are locked in escrow on the source. Source balance decreases by the transfer amount.
- P2: Equivalent tokens are minted on the destination. Destination balance increases.
- P3: The response confirms success. The escrow is marked as completed.

**Setup:**

Deploy token escrow contract on source and mint contract on destination. Fund a test user account
with tokens on the source. Record starting balances.

**Steps:**

1. Initiate a transfer of N tokens from the source.
   → The application locks N tokens in escrow and sends a CLPR message to the destination.

2. Wait for delivery on the destination. The destination application mints N tokens.

3. Wait for the response on the source. The escrow is released.

4. Record token balances on both ledgers.
   → Assert: source balance decreased by N. Destination balance increased by N. Escrow marked
   complete.

**Cleanup:** None.

---

# 3. Fungible Token Transfer: Roundtrip

**Reference:** clpr-test-spec.md §6.1.4

**Purpose:** Verify that transferring tokens A→B and then B→A returns balances to their original state.

**Properties to Test:**

- P1: Net balances after a roundtrip equal the starting balances (minus any fees).

**Setup:**

Same as Test 2, with transfer capability in both directions. Record starting balances.

**Steps:**

1. Transfer N tokens from A to B. Wait for completion.

2. Transfer N tokens from B to A. Wait for completion.

3. Record balances.
   → Assert: balances match the starting values (or differ only by protocol-level fees if any).

**Cleanup:** None.

---

# 4. Fungible Token Transfer: Failure Handling

**Reference:** clpr-test-spec.md §6.1.3

**Purpose:** Verify that application-level failures are handled correctly.

**Properties to Test:**

- P1: If the destination application reverts (e.g., minting cap exceeded), the source receives
  `APPLICATION_ERROR`.
- P2: The escrowed tokens on the source are released back to the sender.
- P3: No tokens are minted on the destination.

**Setup:**

Configure the destination mint contract with a cap that will be exceeded by the transfer.

**Steps:**

1. Attempt a transfer that exceeds the minting cap.

2. Wait for the response on the source.
   → Assert: `APPLICATION_ERROR` status. Escrowed tokens released to sender. Destination balance
   unchanged.

**Cleanup:** None.

---

# 5. NFT Transfer: Lock-and-Mint with Metadata

**Reference:** clpr-test-spec.md §6.2.1

**Purpose:** Verify that an NFT can be transferred between ledgers with metadata preserved.

**Properties to Test:**

- P1: The NFT is locked on the source.
- P2: An equivalent NFT is minted on the destination with the same metadata (token ID, URI,
  properties).
- P3: Ownership is correct on both sides after transfer.

**Setup:**

Deploy NFT escrow on source and NFT mint on destination. Create a test NFT on the source.

**Steps:**

1. Record NFT ownership and metadata on source.

2. Initiate the transfer. NFT is locked in escrow. CLPR message carries the metadata.

3. Wait for delivery on destination. Verify: NFT minted with matching metadata.

4. Verify: source NFT is locked (not accessible to original owner). Destination NFT is owned by
   the target account.

**Cleanup:** None.

---

# 6. Escrow: Conditional Release

**Reference:** clpr-test-spec.md §6.3.1

**Purpose:** Verify a conditional escrow pattern where release depends on the destination's response.

**Properties to Test:**

- P1: On `SUCCESS` response, the escrow releases to the intended recipient.
- P2: On `APPLICATION_ERROR` response, the escrow returns to the sender.

**Setup:**

Deploy an escrow contract on the source that locks assets and releases based on the response status.
Deploy a destination application that can be configured to succeed or revert.

**Steps:**

1. Lock assets in escrow and send a CLPR message. Destination succeeds.
   → Assert: escrow released to recipient.

2. Lock assets in escrow and send a CLPR message. Destination reverts.
   → Assert: escrow returned to sender.

**Cleanup:** None.

---

# 7. Escrow: Timeout

**Reference:** clpr-test-spec.md §6.3.2

**Purpose:** Verify that escrowed assets are released back to the sender if the response does not
arrive within the deadline.

**Properties to Test:**

- P1: If the deadline expires before the response arrives, the escrow releases to the sender.
- P2: If the response arrives after the deadline, the application handles it gracefully (the escrow
  was already released).

**Setup:**

Deploy an escrow contract with a short deadline (e.g., 60 seconds). Introduce a delay in message
delivery (e.g., by pausing the Connection temporarily).

**Steps:**

1. Lock assets with a short deadline. Delay delivery so the response does not arrive in time.

2. After the deadline, verify: escrow released to sender.

3. Allow the response to eventually arrive.
   → Assert: the application handles the late response without error.

**Cleanup:** None.

---

# 8. Escrow: Connection Closure During Escrow

**Reference:** clpr-test-spec.md §6.3.3

**Purpose:** Verify that application escrow logic handles Connection closure while assets are locked.

**Properties to Test:**

- P1: The application detects that the Connection is closing.
- P2: The application provides a reconciliation path for the locked assets.

**Setup:**

Deploy escrow contract. Lock assets and send a CLPR message. Close the Connection before the
response returns.

**Steps:**

1. Lock assets and send a message.

2. Close the Connection (admin).

3. Wait for the Connection to reach CLOSED.

4. Verify: the application detects the closure. The escrowed assets are handled according to the
   application's recovery logic (e.g., timeout-based release, or admin-assisted reconciliation via
   out-of-band query to the remote ledger).

**Cleanup:** None.

---

# 9. Multi-Ledger Atomic Swap (N = 3): Success

**Reference:** clpr-test-spec.md §6.4.1

**Purpose:** Verify a three-party atomic swap where all legs succeed.

**Properties to Test:**

- P1: All three parties end with the correct assets.
- P2: All escrows are released.

**Setup:**

Three networks with triangle connectivity. Deploy swap coordinator contracts on each. Each party
holds a different token. Swap: A→B (token X), B→C (token Y), C→A (token Z).

**Steps:**

1. Initiate the three-party swap. All legs proceed.

2. Wait for all three responses.
   → Assert: A has token Z, B has token X, C has token Y. All escrows released.

**Cleanup:** None.

---

# 10. Multi-Ledger Atomic Swap (N = 3): Partial Failure

**Reference:** clpr-test-spec.md §6.4.2

**Purpose:** Verify that when one leg of a three-party swap fails, all legs roll back.

**Properties to Test:**

- P1: All legs roll back. Each party retains their original token.
- P2: No assets are stuck in escrow.

**Setup:**

Same as Test 9, but configure one leg to fail (e.g., Connector on B↔C is underfunded).

**Steps:**

1. Initiate the swap. One leg fails.

2. Wait for the failure to propagate to all legs.
   → Assert: all legs rolled back. Each party retains their original token. No assets stuck.

**Cleanup:** None.

---

# 11. Lightweight DEX: Cross-Ledger Settlement

**Reference:** clpr-test-spec.md §6.5.1

**Purpose:** Verify that a cross-ledger DEX can match orders and settle trades via CLPR.

**Properties to Test:**

- P1: An order placed on the source ledger is matched and settled on the destination.
- P2: Assets are exchanged correctly on both sides.

**Setup:**

Deploy a DEX order book contract on the source and a settlement contract on the destination.
Fund user accounts with tradeable tokens.

**Steps:**

1. User places a buy order on the source DEX (wants token Y from the destination, offering token X).

2. The DEX matches the order and sends a CLPR message to the destination for settlement.

3. The destination executes the settlement: token Y transferred to the user's destination account,
   token X credited to the counterparty.

4. Response returns to the source confirming the trade.

5. Verify balances on both sides.
   → Assert: correct token exchange. No double-spends.

**Cleanup:** None.

---

# 12. Lightweight DEX: Concurrent Trades

**Reference:** clpr-test-spec.md §6.5.2

**Purpose:** Verify that multiple trades settle correctly in parallel.

**Properties to Test:**

- P1: Multiple concurrent trades on the same Connection all settle correctly.
- P2: No ordering-dependent bugs (trades do not interfere with each other).

**Setup:**

Same as Test 11, with multiple user accounts placing trades simultaneously.

**Steps:**

1. Five users place buy orders concurrently.

2. Wait for all settlements.

3. Verify each user received the correct tokens and no double-spends occurred.

**Cleanup:** None.
