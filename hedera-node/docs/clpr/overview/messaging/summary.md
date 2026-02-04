---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Messaging layer (target)

## Responsibilities

- Maintain ordered outbound queues per connection.
- Form bundles and transmit them with state proofs.
- Verify inbound bundles and state proofs.
- Provide verified messages to the middleware.
- Provide the assigned message id with inbound message delivery so middleware
  can populate responses.
- Track queue metadata for send/receive progress and running hashes.
- Expose enqueue/handle APIs for requests and responses.
- Proof verification is handled entirely within the messaging layer; middleware
  receives only verified messages.
- Deliver messages and responses exactly once per connection.

## Connections and endpoints

- A connection is a logical link between two ledgers with a shared
  ordered queue in each direction.
- Endpoints are registered on-ledger and can push or pull messages.
- A single honest and reachable endpoint per ledger is sufficient.

## Queue metadata (conceptual)

- Counts of sent/received messages.
- Running hash values for verification continuity.
- Next send message id and throttle parameters.
  - Throttles are defined per ledger configuration (size, count, bytes).

## Push/pull semantics

- When endpoints are advertised, both sides can push.
- When a ledger does not advertise endpoints, it must drive both
  push and pull for the connection.

## Configuration updates

- Ledger configuration updates are transported alongside non-empty
  message bundles (not via the queue as standalone messages).
- An out-of-band update path remains available for recovery when normal
  messaging is inactive.
- Out-of-band updates are paid by the submitting entity using normal
  ledger transaction fees.

## Verification model

- Bundles are accepted only if the running hash derived from the
  local starting hash and the bundle payloads matches the state
  proven final hash.
- Message ids are inferred from the last message id in the proof,
  enabling deterministic ordering without per-message ids in the
  bundle list.

## API expectations (MVP)

- Middleware guarantees message validity before enqueue.
- Queue failures return typed reasons to middleware; failure catalog is deferred.
