# CLPR Specification Options to Consider

This document captures possible changes or expansions to the CLPR specification that have emerged from design
review discussions. Each section presents a self-contained proposal: the problem it solves, the proposed
solution, edge cases and limitations, and the value of implementing it. These are presented for evaluation
by the specification authors and reviewers — none are committed changes.

These proposals are ordered independently. They may be adopted individually or in combination.

---

# 1. Application-Level Message Redaction (Clawback / Undo)

## 1.1 Problem

Once an application calls `sendMessage` and the message is enqueued on the Connection's outbound queue, there
is no mechanism for the application to cancel it. The only entity that can redact a queued message is the CLPR
Service Admin. This creates two gaps:

- **No undo for applications.** An application that detects an error (wrong amount, wrong destination,
  fraudulent transaction) immediately after sending has no recourse. The message will be delivered and
  executed on the remote ledger regardless.
- **No clawback for regulated institutions.** Financial institutions subject to regulatory requirements
  (AML holds, fraud detection, compliance reviews) often need the ability to halt or reverse an outbound
  transfer within a short window after initiation. Currently this must be built entirely at the application
  layer — the application would need to implement its own internal queue with a delay window before calling
  `sendMessage`, which adds complexity and latency.
- **No clean unwinding on Connection closure.** When a Connection enters CLOSING or is closed, messages
  sitting in the outbound queue that have not yet been synced to the destination have an ambiguous fate. If
  the application could redact its own undelivered messages, it could cleanly reverse escrowed assets rather
  than waiting for an ambiguous outcome.

## 1.2 Proposed Solution

Allow the original sending application (identified by the `sender` field stamped at enqueue time) to redact
its own messages, subject to the constraint that the message has not yet participated in a sync.

**New operation:** `redactMessage` is extended to accept calls from the message's `sender` (in addition to
the CLPR Service Admin). The protocol behavior is identical to admin redaction: the message payload is
removed, the message slot and running hash are preserved, and a `REDACTED` response is generated for the
application.

**Safety constraint:** The message must not have been included in a bundle that has been submitted to the
destination. The most conservative check: the message ID must be greater than `acked_message_id` (the peer
has not confirmed receipt). A stricter check could also verify that no `submitBundle` transaction referencing
this message has been submitted locally, but tracking this is difficult since endpoints operate off-chain.

## 1.3 Edge Cases and Limitations

**Race with delivery.** The fundamental limitation is that redaction is a local operation, but delivery is a
distributed one. Between the moment the message is enqueued and the moment the application redacts it, an
endpoint may have:
1. Read the message from state
2. Constructed a proof over it (including the running hash)
3. Transmitted it to a peer endpoint
4. The peer endpoint may have submitted it to the destination ledger

If step 4 has occurred, the destination processes the original message and generates a response. The source
has redacted the message and the application believes it was cancelled, but the destination has executed it.
The application receives both a `REDACTED` response (from the local redaction) and — eventually — the actual
response from the destination (which arrives as a Response Message in the inbound queue). The application
must handle the case where a `SUCCESS` or other response arrives for a message it believes was redacted.

This race window is proportional to the sync frequency plus consensus time on the destination. On fast
networks (Hiero-to-Hiero), this may be sub-second. On slower networks (involving Ethereum), it could be
12+ seconds.

**Connector accounting.** The Connector's `authorizeMessage()` was called and returned true at enqueue time.
The Connector may have updated internal state (reserved funds, adjusted exposure tracking). Redaction voids
this commitment without the Connector's explicit involvement. In practice this is benign — the Connector is
not charged (the message was never delivered) — but Connectors with sophisticated accounting may need to
handle a "message redacted" callback or event.

**Running hash preservation.** The running hash was computed over the original payload at enqueue time and
cannot be retroactively changed without breaking the chain for all subsequent messages. The redacted message
slot retains its running hash. When the destination eventually receives this slot in a bundle, it sees an
empty payload but a valid running hash chain. This is identical to admin redaction and is already handled
by the protocol.

## 1.4 Value

- **Immediate practical value for regulated institutions.** Clawback and compliance holds are real
  requirements for financial applications. Providing this at the protocol layer is simpler and more reliable
  than requiring every application to build its own pre-send staging queue.
- **Clean connection wind-down.** Applications can proactively redact their undelivered messages when a
  Connection is closing, recovering escrowed assets cleanly rather than waiting for ambiguous outcomes.
- **Small specification change.** The redaction mechanism already exists for the admin. Extending it to the
  sending application is a permission change, not a new mechanism. The safety constraints and running hash
  behavior are identical.
- **The race condition is manageable.** The race with delivery produces the same kind of ambiguity that
  already exists when a Connection is closed with in-flight messages. Applications that are designed to
  handle connection closure (as they must be) can handle the redaction race with the same reconciliation
  logic.

---

# 2. Message Migration Between Connections

## 2.1 Problem

Several recovery scenarios in the specification (R2, R4, R5 in §4 of the design document) require closing an
old Connection and registering a new one. When the old Connection is closed, messages in the outbound queue
that have not yet been delivered — and messages that were delivered but whose responses have not yet
returned — have an ambiguous fate. The specification currently states that these messages require
"application-level out-of-band reconciliation," but provides no tooling or protocol support for this.

For applications with significant in-flight message volume, this means that every proof format upgrade, every
verifier replacement, and every Connection migration causes a burst of ambiguous messages that each
application must individually reconcile. This is operationally expensive, error-prone, and creates a strong
disincentive to perform necessary upgrades.

The core question: can undelivered messages on a closing Connection be automatically migrated to an active
Connection that serves the same peer ledger?

## 2.2 Analysis: What Can and Cannot Be Migrated

Messages are bound to their Connection through three mechanisms: message ID sequencing, running hash chains,
and Connector mappings. These bindings mean a message cannot be moved verbatim from one Connection's queue to
another. However, the *payload content* (the application data, target application address, Connector
reference, and sender address) is not intrinsically tied to any Connection — it is transport metadata that
could be re-enqueued on a different Connection.

There are two distinct categories of messages:

**Undelivered outbound messages (source side).** These sit in the source ledger's outbound queue and have
not been synced to the destination. The payload is in local state. Migration means extracting the payload
and re-enqueuing it on the new Connection with a fresh message ID and running hash. This is entirely local
to the source ledger — the destination is not involved.

**Delivered-but-unresponded messages (both sides).** These were delivered to the destination and the
application executed, but the response has not made it back to the source. The response sits in the
destination's outbound queue on the old Connection. Migrating these requires the destination to extract the
response payload and re-enqueue it on the new Connection — which requires coordinated action between both
ledgers' CLPR Services.

## 2.3 Approach A: Source-Side Migration (Undelivered Messages Only)

### Solution

A new CLPR Service admin operation: `migrateMessages(source_connection_id, dest_connection_id,
message_id_range)`. For each message in the range:

1. Verify the message has not been delivered (message ID > `acked_message_id` plus a safety margin to
   account for in-flight bundles).
2. Extract the payload (Data Message content: `connector_id`, `target_application`, `sender`,
   `message_data`).
3. Re-enqueue the payload on the destination Connection via the normal enqueue path — assigning a new
   message ID, computing a new running hash, and calling the Connector's `authorizeMessage()` on the new
   Connection.
4. Mark the original message slot on the old Connection as migrated (a new disposition alongside
   delivered/redacted).
5. Emit a migration record mapping old `(connection_id, message_id)` to new `(connection_id, message_id)`
   so applications can update their correlation state.

### Edge Cases

- **Connector must be registered on the new Connection.** If the same Connector operates on both
  Connections, migration proceeds naturally. If not, the Connector's `authorizeMessage()` will fail and the
  message cannot be migrated. The admin would need to coordinate with the Connector Operator to register on
  the new Connection first.
- **Connector may reject re-authorization.** The Connector's policy may have changed, or its balance on the
  new Connection may be insufficient. Messages that fail re-authorization remain on the old Connection and
  become ambiguous when it closes.
- **Control Messages cannot be migrated.** ConfigUpdate messages are Connection-specific. They must be
  skipped during migration.
- **Response Messages cannot be migrated via this approach.** Responses reference inbound message IDs from
  the old Connection that have no meaning on the new Connection. Source-side migration handles only outbound
  Data Messages.
- **In-flight bundles.** A message may have been read by an endpoint and included in a bundle that is in
  transit to the destination but not yet acknowledged. Migrating this message creates a duplicate — the
  original arrives on the destination via the old Connection, and the migrated copy arrives via the new
  Connection. The application on the destination must handle this idempotently, or the safety margin on
  the message ID range must be wide enough to exclude any message that could be in flight.

### Value

- Eliminates ambiguity for the most common case: messages that were enqueued but never left the source
  ledger.
- Makes proof format upgrades (R2) and verifier replacements (R5) significantly less disruptive —
  applications retain their message ordering and do not need to reconcile undelivered messages.
- The mechanism is local to one ledger. No cross-ledger coordination is required for this approach.

## 2.4 Approach B: Coordinated Migration via Control Messages

### Solution

Extend the protocol to support coordinated migration of both undelivered messages and pending responses.
This uses new Control Message types on the new Connection to synchronize the migration between both ledgers.

**Phase 1 — Source-side migration (same as Approach A):**
The source admin migrates undelivered outbound Data Messages to the new Connection. A
`MigrationManifest` Control Message is enqueued on the new Connection's outbound queue, containing:
- The old Connection ID
- A mapping of old message IDs to new message IDs for all migrated messages
- A list of old message IDs that were delivered on the old Connection and are still awaiting responses

**Phase 2 — Destination processes the manifest:**
When the destination's CLPR Service processes the `MigrationManifest` (received via a normal bundle on the
new Connection), it:
1. Looks up each "awaiting response" message ID in the old Connection's state.
2. For messages whose responses are still in the destination's outbound queue on the old Connection:
   extract the response payload and re-enqueue it on the new Connection's outbound queue with a new
   message ID. The `original_message_id` field in the response is remapped using the manifest's ID mapping.
3. For messages whose responses have already been sent (but not yet acknowledged by the source): the
   response may be in flight on the old Connection. These are flagged as "migration-pending" and handled
   when the old Connection's remaining syncs complete.

**Phase 3 — Source receives migrated responses:**
Responses arriving on the new Connection carry remapped `original_message_id` values that correspond to the
new message IDs. The source CLPR Service matches them normally. Applications see responses correlated to the
new message IDs (which they already know about from the migration record emitted in Phase 1).

### Edge Cases

- **Responses already in flight on the old Connection.** If a response was included in a bundle on the old
  Connection that is in transit, it may arrive on the source after migration. The source has already
  remapped the message to the new Connection. It must recognize the old-Connection response and correlate
  it to the migrated message. This requires the source to retain migration state (old message ID → new
  message ID) until the old Connection is fully drained.
- **Partial migration.** Some messages may be migratable and others not (e.g., Connector not registered on
  the new Connection). The manifest must indicate which messages were successfully migrated and which were
  left behind.
- **Ordering guarantees across Connections.** Messages migrated from the old Connection receive new IDs on
  the new Connection. If the new Connection already has messages of its own, the migrated messages are
  interleaved with them. The relative ordering of migrated messages is preserved (they are re-enqueued in
  original ID order), but their position relative to new Connection's native messages depends on when the
  migration occurs. Applications that depend on strict ordering across the old and new Connections must
  be aware of this.
- **Both admins must act.** The source admin initiates migration by migrating messages and sending the
  manifest. The destination admin must have the new Connection active and its CLPR Service must support
  manifest processing. If the destination's CLPR Service does not recognize `MigrationManifest` (e.g.,
  running an older version), the manifest is rejected as an unknown Control Message type and the
  migration fails.
- **Running hash divergence.** The old Connection's running hash chain continues to reflect the original
  messages (including those that were migrated). The new Connection has its own independent running hash
  chain. There is no cryptographic linkage between the two — the migration is a semantic operation (payload
  transfer), not a cryptographic continuation.

### Value

- Provides end-to-end migration: both undelivered messages and pending responses are moved to the new
  Connection.
- Eliminates the "ambiguous outcome" problem for proof format upgrades and verifier replacements. Messages
  are not lost — they are relocated.
- Uses the existing Control Message mechanism, extending it with a new subtype rather than introducing a
  separate migration protocol.

### Cost

- Significant protocol complexity. The `MigrationManifest` control message, the destination-side manifest
  processing, the response remapping, and the in-flight handling all add substantial specification and
  implementation surface.
- Both CLPR Services must support the manifest. This creates a version dependency: migration only works if
  both sides are running a version that understands `MigrationManifest`. This may be acceptable for
  planned migrations (proof format upgrades) but not for emergency situations (verifier compromise) where
  the peer may not have upgraded.

## 2.5 Approach C: Application-Layer Migration with Protocol Assistance

### Solution

Rather than migrating messages at the protocol level, provide protocol primitives that make application-layer
migration reliable. The CLPR Service does not move messages itself — it provides the information applications
need to do it.

**New query:** `getUndeliveredMessages(connection_id, message_id_range)` — returns the payload content of
undelivered outbound Data Messages on a Connection. Available to any caller (the payloads are on-chain and
already readable, but this provides a convenient batched interface).

**New event/record:** When a Connection transitions to CLOSING or CLOSED, the CLPR Service emits a
structured record listing all undelivered outbound Data Message IDs and all delivered-but-unresponded Data
Message IDs. Applications subscribe to this event and perform their own migration logic: re-sending
messages on the new Connection using `sendMessage`, handling Connector selection, and managing their own
ID correlation.

### Edge Cases

- **Same race conditions as Approach A** for messages that may be in flight at the time of closure.
- **Each application migrates independently.** If 10 applications have messages on the closing Connection,
  each one must perform its own migration. There is no batched admin operation.
- **Connector selection is the application's responsibility.** The application must choose which Connector
  to use on the new Connection, which may be different from the original.
- **No response migration.** Responses stuck on the destination are not addressed — the application must
  still reconcile these out-of-band. This approach only helps with the source-side undelivered messages.

### Value

- Minimal protocol change — a new query and a new event, no new state transitions or Control Message types.
- Applications retain full control over the migration logic, including Connector selection, message
  filtering, and retry behavior.
- No cross-ledger coordination required at the protocol level.

### Cost

- Migration burden falls on every application individually. A protocol-level migration (Approach A or B)
  does it once for all applications.
- No help with response migration — the hardest part of the problem is not addressed.

## 2.6 Comparison

| Dimension | Approach A (Source-Side) | Approach B (Coordinated) | Approach C (App-Layer) |
|---|---|---|---|
| Undelivered message migration | Yes | Yes | Yes (application does it) |
| Pending response migration | No | Yes | No |
| Cross-ledger coordination | None | Required (both admins) | None |
| Protocol complexity | Low | High | Minimal |
| Application developer burden | Low (automatic) | Low (automatic) | High (per-application) |
| Connector re-authorization | Required | Required | Application handles |
| Version dependency | Single ledger | Both ledgers | None |
| In-flight message handling | Safety margin | Manifest + drain | Application handles |

For a first implementation, **Approach A** provides the highest value-to-complexity ratio. It handles the
most common case (undelivered outbound messages), requires no cross-ledger coordination, and is a single
admin operation. The "ambiguous response" problem for delivered-but-unresponded messages remains, but this
is a smaller set of messages and the same ambiguity already exists with connection closure today.

**Approach B** is the complete solution but carries significant specification and implementation cost. It
may be appropriate as a future enhancement once the protocol is mature and migration patterns are well
understood from operational experience with Approach A.

**Approach C** is the lowest-cost option and may be sufficient as an interim measure, but it places the
migration burden on every application and does not address response migration.

---

# 3. Connector Graceful Wind-Down

## 3.1 Problem

Under the current specification, a Connector cannot deregister while it has unresolved in-flight messages.
The `deregisterConnector` operation is rejected until every Data Message the Connector authorized has
received a response. On an active, healthy Connection this is a manageable wait. On a PAUSED Connection —
particularly one paused due to a response ordering violation — messages may never resolve. The Connector's
entire `locked_stake` and `balance` are frozen indefinitely, with no mechanism to reduce exposure.

This creates a disproportionate penalty for Connectors caught in circumstances outside their control. A
response ordering violation is a peer-side bug, not the Connector's fault. Yet the Connector bears the full
economic consequences: their funds are locked, they cannot redeploy capital to other Connections, and they
have no timeline for resolution. The longer the Connection stays PAUSED, the worse the Connector's position
becomes — and the Connector has no power to fix the situation. The admin may close a PAUSED Connection
(transitions to CLOSING), but the Connection cannot drain until the peer fixes the ordering.

## 3.2 Proposed Solution

Introduce a **wind-down** state for Connectors that immediately stops new message authorization while
allowing partial withdrawal of funds, retaining only a reserve sufficient to cover the worst-case slashing
exposure from remaining in-flight messages.

**New operation:** `windDownConnector(connection_id, source_connector_address)`. Callable by the Connector
admin. The CLPR Service:

1. **Marks the Connector as winding down.** From this point, the CLPR Service auto-denies any
   `authorizeMessage()` call for this Connector. No new messages can enter the pipeline. This is
   instantaneous and has no race condition — authorization is checked by the CLPR Service itself at
   enqueue time.

2. **Computes the minimum reserve.** The CLPR Service counts the number of unresolved Data Messages
   this Connector authorized (messages that have been enqueued but have not yet received a response).
   The reserve is:

   ```
   minimum_reserve = unresolved_message_count × max_slash_per_message
   ```

   Where `max_slash_per_message` is the worst-case escalated slash amount from the platform's slashing
   schedule. This must use the escalated amount (not the base fine) to account for the possibility that
   all remaining messages trigger slashing.

3. **Releases excess funds.** The Connector's `balance` is fully withdrawable (no new messages need
   funding). The Connector's `locked_stake` is withdrawable down to `minimum_reserve`. The Connector
   calls `withdrawConnectorBalance` and receives funds immediately.

4. **Reserve decrements as messages resolve.** Each time a response arrives for one of the Connector's
   in-flight messages:
   - If the response is `SUCCESS`, `APPLICATION_ERROR`, or `REDACTED`: no slash. The reserve for that
     message is freed. The Connector can withdraw the freed amount.
   - If the response is `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED`: the reserve for that message
     is slashed and paid to the submitting endpoint. The reserve decreases by the slashed amount.

5. **Final withdrawal and deregistration.** As in-flight messages resolve, the reserve shrinks. The
   Connector Operator can return at any time to withdraw newly freed funds. When the unresolved count
   reaches zero (all messages have received responses), or when the Connection is closed (no further
   responses will arrive), the Connector calls `deregisterConnector` to reclaim any remaining reserve
   in full and remove the Connector from the Connection.

## 3.3 Scenarios

**PAUSED Connection, peer eventually fixes ordering.** The Connector winds down, withdraws excess funds, and
waits with minimal locked exposure. When the peer fixes the response ordering violation and the Connection
auto-resumes to ACTIVE, responses flow normally. Each response either frees reserve (no slash) or consumes
it (slash). Eventually all messages resolve and the Connector deregisters cleanly.

**PAUSED Connection, peer never fixes ordering.** This is the most important scenario. Without wind-down, the
Connector's entire stake is locked indefinitely — possibly forever if the bug is never fixed. With wind-down:
the Connector immediately recovers everything above the reserve. The reserve sits locked until the peer fixes
the ordering (Connection auto-resumes, responses flow, slashing occurs or doesn't). The Connector's locked
exposure drops from their entire `locked_stake` to just the reserve covering in-flight messages. Note: the
admin may close a PAUSED Connection (transitions to CLOSING), but it cannot drain until the peer fixes
the ordering.

**CLOSING Connection.** When the admin calls `closeConnection`, the Connection transitions to CLOSING: queued
messages receive `CONNECTION_CLOSED` responses (no slashing), and the Connection drains to CLOSED. Each
`CONNECTION_CLOSED` response frees reserve (no slash). The Connector's remaining reserve is returned in full
at deregistration. CLOSING resolves the indefinite lock-up problem for admin-initiated exits — the Connector
is no longer blocked waiting for responses that may never come.

**Active connection, normal exit.** The Connector winds down on a healthy Connection. Authorization stops
immediately. Responses flow normally and the reserve decrements with each one. This is strictly better than
the current model where the Connector must wait with their full stake locked until all messages settle.

**Multiple Connectors, one winds down.** Other Connectors on the same Connection are unaffected. They
continue to authorize messages and operate normally. Applications that were using the winding-down Connector
see their `authorizeMessage()` calls rejected and must switch to an alternative Connector.

## 3.4 Edge Cases and Limitations

**Reserve calculation must use worst-case escalated slash amounts.** The slashing schedule escalates with
repeated failures. If the reserve is computed using the base fine and all remaining messages trigger
slashing, the reserve is insufficient. The reserve formula must use the maximum possible slash per message
assuming all remaining messages fail. Platform-specific specifications define the slashing schedule and must
provide this worst-case value.

**Destination-side wind-down.** The Connector exists on both ledgers. On the destination side, the
Connector's `balance` pays for execution of incoming messages, and `locked_stake` covers destination-side
slashing. The same wind-down logic applies: count in-flight messages that haven't been processed, compute
reserve, allow partial withdrawal. The destination's estimate of in-flight messages is less precise (it
depends on what the source has sent but not yet arrived), so the destination must use a conservative
estimate derived from the Connection's queue metadata — the gap between `next_message_id` on the source
(learned via the most recent sync) and `received_message_id` on the destination.

**Wind-down is irreversible.** Once a Connector enters wind-down, it should not be able to return to active
status on the same Connection. If the Connector wants to resume serving the Connection later, it must
deregister (after messages resolve) and re-register. This prevents gaming where a Connector winds down to
withdraw funds, then reactivates with reduced stake.

**No new attack surface.** A Connector in wind-down is strictly less capable than an active Connector — it
cannot authorize new messages, cannot increase exposure, and can only withdraw funds. There is no mechanism
for a winding-down Connector to harm endpoints or applications beyond the exposure that already existed
from its previously authorized messages.

## 3.5 Value

- **Directly solves the Connector deregistration timing problem.** This is an identified open issue in the
  specification. The current model can lock Connector funds indefinitely on a PAUSED Connection with no
  resolution timeline (the peer may never fix the ordering violation, and even if the admin closes the
  Connection it cannot drain until the peer fixes the ordering). Wind-down provides an immediate,
  quantifiable reduction in exposure. For CLOSING Connections,
  `CONNECTION_CLOSED` responses resolve messages without slashing, so the Connector drains naturally —
  wind-down is still useful to release excess funds faster during the drain period.
- **Fair to Connectors.** A response ordering violation is not the Connector's fault. Wind-down lets them
  limit their exposure to the actual risk (in-flight messages) rather than bearing the full cost of a
  system-level problem.
- **Encourages Connector participation.** Connector Operators evaluating the risk of serving a Connection
  will be more willing to participate if they know they can limit their downside in adverse scenarios.
  Without wind-down, serving a Connection is an open-ended commitment with unbounded lock-up risk on
  PAUSED Connections.
- **Modest specification change.** One new operation (`windDownConnector`), one new Connector state
  (winding down), and a reserve computation that uses data the CLPR Service already tracks (unresolved
  message count per Connector, slashing schedule). No new Control Message types, no cross-ledger
  coordination, no changes to the bundle verification algorithm.
