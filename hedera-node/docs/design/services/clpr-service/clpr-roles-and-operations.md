# CLPR Roles and Operations

This document describes the human roles that participate in the CLPR protocol, their trust relationships, their
operational lifecycles, and how their actions interact. It is derived from the
[CLPR Design Document](clpr-service.md) and the [Cross-Platform Specification](clpr-service-spec.md).

Where the design document defines *what the protocol does*, this document defines *who does what, why, and when*.
Every action in CLPR is ultimately initiated by a human or an organization acting in one of the roles described
below. Understanding these roles — and the trust chain that connects them — is essential for evaluating the
security, economics, and operational viability of a CLPR deployment.

---

# 1. Trust Chain

CLPR's security model is a chain of vetting decisions. Each role evaluates the role below it before committing
economic value or user trust. The chain runs from the End User at the top (who bears the ultimate risk of a failed
cross-ledger interaction) to the Verifier Developer at the bottom (whose code is the cryptographic foundation of
every Connection).

```
End User
  └─ vets → Application Developer (application correctness, choice of Connection and Connector)
       └─ vets → Connector Operator (funding adequacy, reliability, choice of Connection)
            └─ vets → Connection (verifier contract correctness, peer ledger legitimacy)
                 └─ built by → Verifier Developer (proof system implementation)
```

Orthogonally, two infrastructure roles support the entire system:

- **CLPR Service Admin** — emergency authority over all Connections on a CLPR Service instance. Can halt or
  close any Connection but has no role in selecting verifiers, Connectors, or applications.
- **Endpoint Operator** — runs the infrastructure that moves bundles between ledgers. Serves all Connections
  on the ledger without choosing which ones.

**Economic participation is the primary motivator for trust verification.** Connector Operators evaluate verifiers
because their locked stake is at risk if the verifier is compromised. Application Developers evaluate Connectors
because their users' assets depend on reliable message delivery. End Users evaluate applications because their
funds are on the line. At every layer, the party with economic exposure performs the vetting — and the party
without economic exposure (notably the CLPR Service Admin) has the weakest incentive to actively monitor.

## 1.1 Role Summary

| Role | Description | Trust Responsibility | Ongoing Obligations | Economic Participation |
|---|---|---|---|---|
| **End User** | Uses applications built on CLPR | Vets the application, its choice of Connection, and its choice of Connector before use | Monitors application health; withdraws if trust is lost | Bears application-level risk (locked assets, failed transactions) |
| **Application Developer** | Builds and deploys cross-ledger applications on both ledgers | Vets Connections (verifier correctness) and Connectors (funding, reliability) before integrating | Monitors Connector health and Connection status; migrates if Connector fails or Connection is closed | Pays per-message fees to Connectors |
| **Connector Operator** | Funds and operates a Connector on one or more Connections; may also create Connections | Vets verifier implementations before bonding to a Connection; evaluates the source ledger's proof system | Maintains adequate balance and stake; monitors Connection health; decides whether to continue supporting specific routes | Primary economic facilitator — posts balance and locked stake, pays for remote execution, subject to slashing |
| **Verifier Developer** | Builds, audits, and publishes verifier contract implementations for a specific proof system | Responsible for the correctness and security of the verification logic; their reputation is the trust anchor for downstream roles | Maintains verifiers across proof format upgrades; publishes security advisories if vulnerabilities are discovered | None in-protocol — compensated externally |
| **Endpoint Operator** | Runs a node that syncs with peer endpoints and submits bundles | Trusts the local CLPR Service implementation and the Connections it serves | Maintains funded accounts for transaction fees; keeps node software current; monitors sync health | Earns margin from Connector reimbursement; fronts transaction costs; subject to slashing |
| **CLPR Service Admin** | Governs a CLPR Service instance | Emergency authority — acts when Connections are compromised or the protocol is under attack | Monitors the ecosystem for compromised Connections; responds to security advisories; coordinates with peer admins during incidents | None — no fees, no revenue, no bond |

## 1.2 Role Interaction Map

The following table shows direct interactions between roles. Each cell describes the nature of the relationship
from the row role's perspective toward the column role.

|  | End User | App Developer | Connector Op | Verifier Dev | Endpoint Op | CLPR Admin |
|---|---|---|---|---|---|---|
| **End User** | — | Evaluates and uses their application | Indirectly relies on via application | No direct interaction | No direct interaction | No direct interaction |
| **App Developer** | Serves; designs UX and trust signals | — | Selects and integrates; monitors health | Evaluates verifier implementations via Connection | No direct interaction | Monitors for halt/close actions on used Connections |
| **Connector Op** | No direct interaction | Serves (authorizes their messages) | — | Evaluates before bonding to a Connection | Relies on for bundle delivery | Monitors for halt/close actions |
| **Verifier Dev** | No direct interaction | Publishes for their evaluation | Publishes for their evaluation | — | No direct interaction | No direct interaction |
| **Endpoint Op** | No direct interaction | No direct interaction | Relies on for margin reimbursement | No direct interaction (trusts their code implicitly) | — | Operates under their governance |
| **CLPR Admin** | No direct interaction | No direct interaction | No direct interaction | Responds to their security advisories | Governs (can remove on Hiero) | — |

---

# 2. End User

## 2.1 Overview

The End User is the person or entity that interacts with applications built on CLPR. They do not interact with the
CLPR protocol directly — they use a cross-ledger application (an asset transfer service, a cross-chain DEX) that
uses CLPR as its transport layer. The End User is at the top of the trust chain: every failure in every layer below
them ultimately affects their assets or their experience.

End Users are permissionless. They interact with applications through the application's own interface — a web
frontend, a wallet integration, or a direct smart contract call. They need an account on at least one of the
ledgers involved and sufficient funds to pay the application's fees.

## 2.2 Relationships and Trust

- **Trusts the Application Developer** to have chosen a trustworthy Connection and Connector, to handle
  error cases correctly (including the possibility of a Connection being closed while messages are in-flight),
  and to faithfully execute the application's advertised behavior.
- **Indirectly trusts the Connector Operator** to maintain adequate funding. Individual Connector failures
  are invisible to the End User — the submitting endpoint fronts execution costs and the Connector is
  penalized separately. The visible impact arises only if all Connectors serving the application become
  non-operational, at which point the application can no longer send new messages.
- **Indirectly trusts the Verifier Developer** to have built correct verification logic — though most End Users
  will never evaluate a verifier directly. A compromised verifier could cause the application to process
  fabricated messages, leading to asset loss.

## 2.3 Actions

### Use a Cross-Ledger Application

- **Trigger:** User wants to perform a cross-ledger operation (transfer assets between ledgers, invoke a
  remote contract, etc.).
- **Preconditions:** Application is deployed on both ledgers. The Connection it uses is ACTIVE. At least one
  Connector serving the application is funded.
- **Procedure:** User interacts with the application's interface. The application calls `sendMessage` on the
  local CLPR Service, specifying the Connection, Connector, target application, and payload.
- **Postconditions:** A message is enqueued on the Connection's outbound queue. The user waits for a response.
- **Failure modes:** Connector refuses authorization (application should surface this). Connection is HALTED or
  CLOSED (application should detect and notify user). All Connectors serving the application are non-operational
  (application cannot send new messages until a Connector is restored or replaced).

## 2.4 Operations and Risks

The End User's operational burden is light — they evaluate the application before using it and monitor for signs
of degraded service. The primary risks are:

- **Application risk.** The application may have bugs, may choose a bad Connection or Connector, or may
  mishandle error cases.
- **Connection closure while messages are in-flight.** If the CLPR Service Admin closes the Connection before
  all responses return, the outcome of pending messages may be ambiguous. The application is responsible for
  designing recovery paths for this scenario.
- **Finality risk.** If the Connection's verifier accepts proofs at a commitment level below finality (e.g.,
  Ethereum `latest` rather than `finalized`), a chain reorganization could produce phantom messages that the
  user's application acts on.

If assets are locked in escrow on either ledger, if possible, the user should complete or unwind those positions
through the application before ceasing use.

---

# 3. Application Developer

## 3.1 Overview

The Application Developer builds and deploys smart contracts on both ledgers that use CLPR as a transport layer
for cross-ledger communication. They are the primary consumer of the CLPR protocol — choosing which Connections
and Connectors to integrate with, designing the message format, handling responses, and building the user-facing
interface.

Application Developers are permissionless. Sending a message through CLPR requires no special authority — only a
funded account and a deployed application contract. They need smart contracts deployed on both ledgers and
knowledge of which Connections and Connectors are available.

## 3.2 Relationships and Trust

- **Trusted by End Users** to have chosen a safe Connection and Connector, and to handle all failure modes
  gracefully.
- **Trusts the Connector Operator** to maintain adequate funding, to authorize messages reliably, and to not
  deregister while messages are in-flight.
- **Trusts the Connection's verifier** to correctly validate proofs from the peer ledger. The Application
  Developer is responsible for evaluating the verifier — either by auditing the code directly, by checking
  third-party audit reports, or by relying on the Connector Operator's evaluation (since the Connector has
  economic skin in the game).
- **Depends on Endpoint Operators** for bundle delivery, but has no direct interaction with them.

## 3.3 Actions

### Send a Cross-Ledger Message

- **Trigger:** An End User or automated process initiates a cross-ledger operation through the application.
- **Preconditions:** Connection is ACTIVE. Connector is registered and funded. Application contracts are
  deployed on both ledgers.
- **Procedure:** The application calls `sendMessage` on the local CLPR Service, specifying `connection_id`,
  `connector_id`, `target_application`, and `message_data`. The CLPR Service stamps the `sender` from the
  transaction caller, calls the Connector's `authorizeMessage()`, and if authorized, enqueues the message.
- **Postconditions:** The message is assigned a `message_id` and added to the Connection's outbound queue.
  The application stores the `message_id` for correlation with the eventual response.
- **Failure modes:** Connector rejects the message (authorization failed, insufficient funds, rate limited).
  Connection is not ACTIVE. Payload exceeds `max_message_payload_bytes`. Queue is full.

### Handle a Response

- **Trigger:** The CLPR Service delivers a response to a previously sent message.
- **Procedure:** The CLPR Service dispatches the response to the application's callback function with the
  `original_message_id`, `status`, and `reply_data`. The application inspects the status:
  - `SUCCESS` — normal completion. Process `reply_data`.
  - `APPLICATION_ERROR` — the remote application reverted. Handle the error.
  - `CONNECTOR_NOT_FOUND` — the Connector does not exist on the destination. The source Connector is slashed.
  - `CONNECTOR_UNDERFUNDED` — the Connector cannot pay on the destination. The source Connector is slashed.
  - `REDACTED` — the message was redacted by the admin before delivery. No remote processing occurred.
- **Failure modes:** Callback reverts (the CLPR Service records the failure but does not retry).

## 3.4 Operations and Risks

**Selecting and maintaining Connections and Connectors.** Each `sendMessage` call specifies both a
`connection_id` and a `connector_id`. The initial selection — evaluating verifier trust, commitment level,
Connector funding, and operational history — happens during application design, but it is not a one-time
decision. Over the life of the application, the developer must continuously re-evaluate these choices:

- A Connector's balance may decline or it may be slashed, requiring the application to switch to an
  alternative. If a Connector deregisters while messages are in-flight, responses returning
  `CONNECTOR_NOT_FOUND` or `CONNECTOR_UNDERFUNDED` will arrive and must be handled gracefully.
- A Connection may enter HALTED (new `sendMessage` calls are rejected but queued messages continue to be
  delivered) or be closed entirely (the outcome of in-flight messages may be ambiguous and must be
  reconciled out-of-band).
- The source ledger may upgrade its proof format, rendering the Connection's immutable verifier incompatible.
  The application must migrate to a new Connection with a new verifier.
- If no suitable Connection or Connector exists for a desired route, the developer may need to coordinate
  with a Connector Operator to create one.

Well-designed applications should support switching Connections and Connectors without requiring redeployment
— treating the `connection_id` and `connector_id` as configurable parameters rather than hardcoded constants.

**Security posture.** All cross-ledger payloads must be treated as untrusted input. Validate defensively
against crafted responses. If the application requires confidentiality, encrypt payloads at the application
layer — CLPR stores payloads on-chain in plaintext. If the Connection's verifier is compromised, the
application may process fabricated messages — this is the most severe risk and can lead to direct asset loss.

To exit, the Application Developer stops sending messages, waits for in-flight responses, settles escrowed
assets, and communicates the shutdown to End Users.

---

# 4. Connector Operator

## 4.1 Overview

The Connector Operator is the primary economic facilitator in CLPR — the role with the most direct
protocol-level financial exposure. They register a Connector on a Connection, post
a balance (to pay for message execution on the destination ledger) and a locked stake (slashable against
misbehavior), and authorize messages from applications. When a message is delivered on the destination ledger,
the Connector's balance is debited to pay for execution. When delivery fails, the Connector's locked stake
is slashed.

Connector Operators are the natural party to create Connections. They evaluate verifier implementations because
their locked stake is directly at risk if the verifier is compromised — a fraudulent verifier means the
Connector pays for execution of fabricated messages. This economic incentive makes Connector Operators the
most motivated evaluators of verifier trust in the entire system.

Connector registration is permissionless but requires funds. The Connector must be registered on *both*
ledgers — the source side defines the authorization contract (which applications call to send messages) and the
destination side provides the balance and stake for message execution. Prerequisites include accounts funded
with native tokens on both ledgers, a deployed authorization contract implementing `IClprConnectorAuth`, and
confidence in the Connection's verifier.

## 4.2 Relationships and Trust

- **Trusted by Application Developers** to maintain adequate funding, to authorize messages reliably, and to
  have vetted the Connection's verifier.
- **Trusts the Verifier Developer** to have built correct verification logic. A compromised verifier means
  fabricated messages drain the Connector's balance.
- **Trusts Endpoint Operators** to submit bundles promptly and to not submit duplicates (which waste
  Connector funds).
- **Depends on the CLPR Service Admin** not closing the Connection unexpectedly.
- **May also act as Connection Creator** — deploying the verifier contract and calling `registerConnection`
  on both ledgers before registering as a Connector. This is the common case when a Connector Operator
  wants to open a new cross-ledger route.

## 4.3 Actions

### Create a Connection

- **Trigger:** The Connector Operator wants to serve a new cross-ledger route or wants a Connection with a
  specific verifier (e.g., a different commitment level).
- **Preconditions:** A verifier contract for the target proof system has been built. The Connector Operator
  has evaluated and trusts the implementation.
- **Procedure:**
  1. Deploy the verifier contract on the local ledger.
  2. Generate an ECDSA secp256k1 keypair. The Connection ID is `keccak256(uncompressed_public_key)`.
  3. Obtain a configuration proof from the peer ledger (off-chain coordination — documentation, direct
     communication with the peer ledger's operators, or public proof services).
  4. Call `registerConnection` with the Connection ID, ECDSA signature, verifier address, and configuration
     proof bytes. The CLPR Service calls `verifyConfig()` on the verifier to extract the peer's verified
     configuration.
  5. Repeat the process on the peer ledger for bidirectional communication.
- **Postconditions:** The Connection exists on both ledgers in ACTIVE state. The verifier is immutable.
- **Failure modes:** Verifier contract not deployed or not conforming to `IClprVerifier`. ECDSA signature
  invalid. Config proof fails verification. Duplicate Connection ID.

### Register a Connector on a Connection

- **Trigger:** The Connector Operator decides to serve a Connection — either one they created or one created
  by another party.
- **Preconditions:** The Connection is ACTIVE. The Connector Operator has evaluated the Connection's verifier.
- **Procedure:** Call `registerConnector` specifying `connection_id`, `source_connector_address` (counterpart
  on the peer ledger), `connector_contract` (authorization contract), `initial_balance`, and `stake`. The
  caller becomes the Connector admin.
- **Postconditions:** The Connector is registered. Applications can now send messages through it. Balance and
  stake are held by the CLPR Service.
- **Failure modes:** Connection does not exist or is not ACTIVE. Insufficient funds. Minimum stake not met.

### Manage Funds

- **Top up:** Call `topUpConnector` to add funds when balance is running low.
- **Withdraw:** Call `withdrawConnectorBalance` to reclaim surplus funds (locked stake cannot be withdrawn
  while registered).

### Deregister

- **Trigger:** The Connector Operator wants to stop serving a Connection.
- **Preconditions:** No unresolved in-flight messages.
- **Procedure:** Call `deregisterConnector`. Remaining balance and locked stake are returned.
- **Failure modes:** In-flight messages exist — deregistration is rejected until they are settled. If the
  Connection is halted or closed, messages may never settle, potentially blocking deregistration indefinitely
  (see Open Issues §9.5).

## 4.4 Operations and Risks

- **Balance and stake management.** If the Connector's balance drops below the cost of pending executions,
  messages fail with `CONNECTOR_UNDERFUNDED`, triggering slashing. Repeated failures escalate penalties —
  if the locked stake is exhausted, the Connector is banned from the Connection. The Connector must also
  maintain presence on both ledgers; if the destination-side registration lapses, messages fail with
  `CONNECTOR_NOT_FOUND` and the source-side Connector is slashed.
- **Application vetting.** The authorization contract controls which applications can send messages. A
  malicious application could craft messages designed to maximize execution cost. The Connector should
  maintain allow-lists and rate limits.
- **Connection lifecycle.** If the Connection is halted, the Connector's funds remain locked and no new
  outbound messages are accepted, but inbound bundles continue flowing (acknowledgements proceed). If the
  Connection is closed while messages are in-flight, the outcome is ambiguous — the destination may or may
  not have charged for execution.
- **Verifier compromise.** A compromised verifier causes the Connector to pay for execution of fabricated
  messages. This is the Connector's most severe risk and directly drains their balance.
- **Peer ledger risk.** If the peer ledger suffers a consensus failure, messages may be processed incorrectly,
  leading to responses that trigger slashing on the source side.

To exit gracefully: stop authorizing new messages (update the authorization contract to reject all requests),
wait for in-flight messages to settle, then call `deregisterConnector` on both ledgers.

---

# 5. Verifier Developer

## 5.1 Overview

The Verifier Developer builds, audits, and publishes verifier contract implementations for a specific proof
system. They are an entirely off-chain role — they have no protocol-level identity, no on-chain registration,
and no direct economic participation in CLPR. None of the Verifier Developer's actions are CLPR protocol
operations; they write code, publish artifacts, and communicate with other roles outside the protocol. Yet they
are arguably the most trust-critical role in the system: every Connection depends on a verifier, and every
verifier was built by someone.

The Verifier Developer understands the source ledger's consensus mechanism, its state structure, and the
cryptographic scheme used to produce state proofs. They translate that understanding into a contract that can
run on a different ledger and verify those proofs. Prerequisites include deep knowledge of the source ledger's
proof format, the ability to implement verification logic on the target platform, and access to the source
ledger's proof generation infrastructure for testing.

## 5.2 Who Builds Verifiers

Verifier development requires deep cryptographic expertise and sustained maintenance. The parties most likely
to undertake this work are those with direct financial motive to get it right:

- **Ledger implementors.** The team that builds and maintains a ledger (e.g., the Hiero core team, the
  Ethereum client teams) benefits from increased transaction volume driven by interledger communication. They
  understand their own proof system better than anyone and are the natural publishers of reference verifier
  implementations for their chain.
- **Connector Operators.** A Connector Operator who wants to serve a new route has direct economic incentive
  to build (or commission) a verifier for that route. Their locked stake depends on the verifier's
  correctness. If no verifier exists, building one is the cost of opening the business.
- **Application Developers with high-value operations.** A team building a major cross-ledger application
  (e.g., a large-scale asset transfer protocol) may build their own verifier rather than depend on a
  third party. Their users' assets are at stake, and controlling the verifier eliminates a trust dependency.
- **Security auditing firms.** Firms that audit cross-chain infrastructure (e.g., Trail of Bits, OpenZeppelin)
  may build and maintain reference verifier implementations as a natural extension of their audit business.
  Being the firm that built the canonical verifier for a major route is a significant credential.
- **Competing Connectors.** If a single Connector Operator builds the only verifier for a route, competitors
  have incentive to build an alternative — either to break the dependency on a competitor's code or to offer
  a different commitment level as a differentiated service.

The common thread: every party with financial motive to build a verifier is one that either operates on or
profits from the route that verifier serves.

## 5.3 Relationships and Trust

- **Trusted by Connector Operators** to have built correct verification logic. Connector Operators evaluate
  the Verifier Developer's work (directly or via audit reports) before staking funds on a Connection.
- **Trusted by Application Developers** to have chosen an appropriate commitment level and to have correctly
  implemented the source ledger's proof verification.
- **No direct interaction with End Users**, Endpoint Operators, or the CLPR Service Admin, though all of
  them depend on the verifier's correctness.

## 5.4 Actions

All Verifier Developer actions are off-chain. They produce artifacts (code, documentation, advisories) that
other roles consume when performing their own on-chain operations (deploying contracts, creating Connections,
halting Connections).

### Build a Verifier Implementation

- **Trigger:** A new cross-ledger route is desired (e.g., Ethereum-to-Hiero), or an existing proof format
  is being upgraded.
- **Procedure (off-chain):** The Verifier Developer implements a contract that conforms to `IClprVerifier`:
  - `verifyConfig(bytes) → ClprLedgerConfiguration` — verifies a configuration proof.
  - `verifyBundle(bytes) → (ClprQueueMetadata, ClprMessagePayload[])` — verifies a message bundle proof.
  - `verifyMetadata(bytes) → ClprQueueMetadata` — verifies a metadata-only proof.
  The verifier may maintain internal mutable state (e.g., tracking validator set rotations or sync
  committee changes).
- **Failure modes:** Incorrect cryptographic implementation (accepts invalid proofs or rejects valid ones).
  Incorrect commitment level enforcement (accepts proofs at `latest` when `finalized` was intended).

### Publish and Support the Verifier

- **Trigger:** The implementation is ready for use.
- **Procedure (off-chain):** Publish the source code, deployment artifacts, and documentation. Commission or
  facilitate independent security audits. Clearly document the commitment level and any trust assumptions.
  Other roles (Connector Operators, Application Developers) then perform their own on-chain actions —
  deploying the contract and creating Connections — based on this published material.
- **Failure modes:** Inadequate documentation. Undiscovered bugs. Audit scope that misses critical paths.

### Respond to a Vulnerability Discovery

- **Trigger:** A vulnerability is discovered in the verifier (by the developer, an auditor, or the community).
- **Procedure (off-chain):** Issue a security advisory identifying affected Connections. Coordinate with
  Connector Operators and the CLPR Service Admin. Publish a patched implementation. The Verifier Developer
  does not perform any on-chain actions — the advisory prompts other roles to act: the CLPR Service Admin
  halts affected Connections, Connector Operators create new Connections with the patched verifier, and
  Application Developers migrate.
- **Failure modes:** Delayed disclosure allows exploitation before Connections are halted.

## 5.5 Operations and Risks

- **Proof format changes.** When the source ledger upgrades its proof format, the Verifier Developer must
  publish a new implementation. All affected Connections must be replaced. The source ledger should maintain
  backward compatibility long enough for the ecosystem to migrate.
- **Upgrade key risk.** If the verifier is deployed behind an upgradeable proxy and the upgrade key is
  compromised, the attacker can silently replace the verification logic. All Connections using that verifier
  are compromised.
- **No in-protocol compensation.** Verifier Developers are compensated externally (grants, ecosystem
  funding, consulting contracts, or as part of their role as a Connector Operator or ledger implementor).
  There is no in-protocol mechanism to incentivize ongoing maintenance. This creates a sustainability
  concern for critical infrastructure — though in practice, the parties most likely to build verifiers
  (see §5.2) have their own financial reasons to maintain them.
- **Reputational risk.** A vulnerability can cause direct financial losses for Connector Operators and
  End Users. The Verifier Developer's reputation is their primary asset — and their primary liability.

Verifier Developers exit by ceasing to maintain the implementation. Existing Connections continue to operate,
but will eventually stop working if the source ledger upgrades its proof format.

---

# 6. Endpoint Operator

## 6.1 Overview

The Endpoint Operator runs a node that participates in the CLPR sync protocol — exchanging bundles with
peer endpoints, constructing state proofs, and submitting received bundles to the local ledger as
transactions. Endpoints are the infrastructure layer of CLPR: they move data between ledgers, but they do
not choose which Connections to serve or which messages to deliver.

On Hiero, every consensus node is automatically a CLPR endpoint. When CLPR is enabled, the node software
registers all consensus nodes as local endpoints. No manual management is required — joining and leaving the
consensus roster automatically updates the endpoint set.

On Ethereum and other permissionless ledgers, endpoints register explicitly by calling `registerEndpoint`
and posting a bond for Sybil resistance.

All endpoint operators must pre-fund their signing accounts with sufficient native tokens to cover
`submitBundle` transaction fees. Margin reimbursement occurs post-consensus and cannot cover the initial cost.

## 6.2 Relationships and Trust

- **Relies on Connector Operators** for economic viability. Endpoints are reimbursed via margin from
  Connector payments when they submit bundles on the destination ledger. Without active Connectors, there
  is no revenue.
- **Trusts the local CLPR Service implementation** to correctly process bundles, calculate fees, and
  enforce the protocol.
- **Trusts peer endpoints** to provide valid bundles, but verifies locally before submitting. Invalid
  bundles are discarded. Individual endpoint implementations may track peer reliability for their own
  peer selection decisions, but this is not part of the CLPR protocol.
- **Operates under the CLPR Service Admin's governance.** The admin's halt/close actions affect which
  Connections the endpoint serves. On Hedera, the governing council can also remove a node from the roster.

## 6.3 Actions

### Submit a Bundle

- **Trigger:** The endpoint receives a `ClprSyncPayload` from a peer endpoint during a sync cycle.
- **Preconditions:** The endpoint has verified the payload locally (pre-consensus check using the
  Connection's verifier). The payload passes verification.
- **Procedure:** Construct a `submitBundle` transaction and submit it to the local ledger.
- **Postconditions:** The CLPR Service processes the bundle — verifying proofs, updating queue metadata,
  dispatching messages to applications, and generating responses.
- **Failure modes:** Verifier rejects the proof (endpoint pays transaction cost, not reimbursed). Bundle
  contains replayed messages (rejected). Bundle exceeds throttle limits (rejected). Multiple endpoints
  submit the same bundle — only the first succeeds; subsequent submissions are rejected and the submitter
  pays the transaction cost. Duplicate submission mitigation strategies are platform-specific and depend
  on the relationship between consensus time and sync frequency.

### Initiate a Sync

- **Trigger:** The sync orchestrator determines that a Connection has pending outbound messages.
- **Preconditions:** The Connection is ACTIVE. The endpoint has connectivity to at least one peer endpoint.
- **Procedure:** Read the latest immutable state, construct proof bytes over unacknowledged messages and
  queue metadata, sign the payload, open a gRPC connection to a peer endpoint, and exchange
  `ClprSyncPayload` messages.
- **Failure modes:** No reachable peer endpoints. Peer returns an invalid payload. Network timeout.
  If the Connection is HALTED, inbound bundles are still processed but outbound syncs are skipped.

### Discover Peer Endpoints

- **Trigger:** The endpoint needs additional peers (first startup, peer became unreachable, etc.).
- **Procedure:** Call the `discoverEndpoints` gRPC RPC on a known peer. Maintain the resulting peer list
  locally (ephemeral, not persisted on-chain). Seed endpoints from the peer ledger's configuration provide
  initial connectivity.

### Register / Deregister (Permissionless Ledgers)

On ledgers where endpoint participation is open (Ethereum, Solana, Avalanche, Polygon, and other
permissionless networks), endpoints register and deregister explicitly:

- **Register:** Call `registerEndpoint`, posting the required bond. The bond must be large enough to make
  Sybil attacks economically infeasible.
- **Deregister:** Call `deregisterEndpoint` after ensuring no in-flight submissions. Bond is returned.

On Hiero, endpoints are derived automatically from the consensus roster — no explicit registration or
deregistration is needed.

## 6.4 Operations and Risks

- **Transaction cost exposure.** Endpoints front the cost of `submitBundle` and are reimbursed via
  Connector margin only on successful delivery. Invalid bundles, failed verifications, and duplicate
  submissions result in unreimbursed costs.
- **Slashing.** Misbehaving endpoints (e.g., duplicate submissions) are subject to slashing by the local
  ledger.
- **Peer misbehavior.** The endpoint tracks inbound sync frequency per remote endpoint. If a peer exceeds
  its fair share of `MaxSyncsPerSec`, the local endpoint shuns it — refusing further syncs from that
  endpoint.
- **Sybil attacks (permissionless ledgers).** On Ethereum, an attacker can register many cheap endpoints
  to eclipse honest endpoints — controlling which bundles get submitted and enabling censorship. The bond
  size must be calibrated to make this economically infeasible.

---

# 7. CLPR Service Admin

## 7.1 Overview

The CLPR Service Admin governs a CLPR Service instance. A ledger may host more than one CLPR Service instance,
each with its own admin — this document does not reason about multi-instance configurations but avoids
precluding them. Who the admin is depends on the network's governance model. On
Hedera, this is the governing council (the 0.0.2 privileged key). On other Hiero-based networks (HashSpheres),
the Admin is determined by the network operator's governance structure. On Ethereum and other permissionless
ledgers, it is whoever controls the CLPR Service contract — which in any secure deployment should be a multisig
or DAO with a timelock.

The Admin's power is broad: they can set the ledger's configuration, halt or close any Connection, and redact
queued messages. They are the emergency authority — the last line of defense when a Connection is compromised,
a verifier is broken, or the protocol is under attack.

The Admin's power is also *exclusively destructive or protective*. They cannot create Connections, register
Connectors, send messages, or participate in the economic activity of the protocol. They can only configure,
halt, resume, close, and redact.

The Admin requires control of the CLPR admin key. On Hedera, this is the council key (0.0.2) and is not
rotatable via CLPR transactions. On other Hiero networks, the admin key is determined by the network's
governance configuration. On Ethereum and other permissionless ledgers, this is the `ADMIN_ROLE` in the
CLPR Service contract's access control.

## 7.2 Relationships and Trust

- **Trusted by all roles** to not abuse their power (closing legitimate Connections, halting healthy routes)
  and to act promptly when intervention is needed (compromised verifiers, protocol attacks).
- **Depends on Verifier Developers** for security advisories about compromised verifiers.
- **Coordinates with peer ledger admins** during incidents that affect both sides of a Connection (e.g.,
  response ordering violations that require the peer to fix a bug).

## 7.3 Actions

### Set Ledger Configuration

- **Trigger:** Operational requirements change — throttle adjustments, seed endpoint updates, etc.
- **Procedure:** Call `setLedgerConfiguration` with the updated `ClprLedgerConfiguration`. The handler
  auto-sets `protocol_version`, `chain_id` (immutable), and `timestamp`. ConfigUpdate Control Messages
  are lazily enqueued on each Connection at its next interaction.
- **Failure modes:** Caller does not hold the admin key. Invalid throttle values. Throttle tightening
  affects all Connections; messages enqueued under the old configuration must be honored, but the admin
  should be aware of the propagation delay.

### Halt a Connection

- **Trigger:** A Connection is suspected of being compromised, a verifier vulnerability has been reported,
  or an investigation is needed.
- **Procedure:** Call `haltConnection`. The Connection transitions to HALTED. No new outbound messages are
  accepted. Inbound bundles are still processed (to keep acknowledgements flowing and prevent the peer's
  queue from stalling).

> 💡 HALTED can also be triggered automatically by a response ordering violation detected during
> `submitBundle` processing. The admin-initiated and protocol-triggered halts produce the same state.

### Resume a Connection

- **Trigger:** Investigation is complete. The issue has been resolved (peer fixed a bug, false alarm, etc.).
- **Procedure:** Call `resumeConnection`. The Connection transitions back to ACTIVE.

### Close a Connection

- **Trigger:** The Connection is irrecoverably compromised, the peer ledger is permanently unavailable, or
  the Connection is no longer needed.
- **Procedure:** Call `closeConnection`. The Connection transitions to CLOSED — terminal, cannot be reopened.
  All processing stops. On Ethereum, if the CLPR Service contract uses a multisig, a halt may take hours
  due to multi-signature and timelock requirements. During that window, the Connection remains in its
  previous state.

### Redact a Message

- **Trigger:** A queued outbound message must be removed before delivery — for legal, regulatory, or
  emergency reasons.
- **Procedure:** Call `redactMessage`. The message payload is removed, but the message slot and running
  hash are preserved. The destination receives an empty payload. The originating application receives a
  `REDACTED` response.
- **Failure modes:** Message already delivered. Message not found.

## 7.4 Operations and Risks

- **Monitoring.** Watch for security advisories from Verifier Developers, reports from Connector Operators,
  and anomalous activity on Connections. When a verifier vulnerability is reported, halt affected
  Connections before exploitation occurs.
- **Peer coordination.** When a Connection is halted due to a response ordering violation, coordinate with
  the peer ledger's admin to fix the bug before resuming.
- **Abuse of power.** The Admin can halt or close any Connection at any time, disrupting all economic
  activity. On decentralized networks, governance mechanisms (multisig, timelock, DAO) mitigate this risk.
- **Inaction risk.** A compromised Connection that is not halted promptly allows continued exploitation.
  The Admin's response time is the window of vulnerability.

The CLPR Service Admin does not exit — the role exists as long as the CLPR Service is deployed. On
permissionless ledgers, the admin role can be transferred via the contract's access control mechanism.

> ‼️ **Economic incentive gap.** The CLPR Service Admin has significant responsibility and broad power
> but no in-protocol economic participation — no fees, no revenue, and no bond. Their motivation to
> actively monitor and promptly respond to incidents is entirely external to the protocol (governance
> obligation, reputational concern, legal liability). This asymmetry between responsibility and incentive
> is a known gap in the current design. On Hedera, the governing council has strong external incentives
> (the network's reputation and token value depend on CLPR operating correctly). On a permissionless
> network with a lightweight governance structure, this gap may be more acute.

---

# 8. Cross-Role Scenarios

The following scenarios trace end-to-end workflows that span multiple roles acting in sequence.

## 8.1 Setting Up a New Cross-Ledger Route

**Roles involved:** Verifier Developer, Connector Operator (acting as Connection Creator), Application
Developer, End User.

1. **Verifier Developer** publishes a verifier implementation for the target proof system (e.g., an
   Ethereum BLS sync committee verifier that runs on Hiero). The implementation is audited and the source
   code is available.
2. **Connector Operator** evaluates the verifier — reviews audit reports, checks the commitment level,
   assesses the source ledger's security. Satisfied, the Connector Operator:
   a. Deploys the verifier contract on the local ledger.
   b. Generates an ECDSA keypair and registers the Connection on both ledgers.
   c. Registers as a Connector on both sides, posting balance and locked stake.
3. **Application Developer** discovers the new Connection (via documentation, registries, or direct
   communication with the Connector Operator). They evaluate the Connection's verifier and the
   Connector's funding. Satisfied, they integrate the Connection and Connector into their application.
4. **End User** uses the application to perform a cross-ledger operation. The trust chain is active from
   top to bottom.

## 8.2 Proof Format Upgrade

**Roles involved:** Verifier Developer, Connector Operator, Application Developer, CLPR Service Admin,
End User.

1. **Source ledger** announces a proof format upgrade.
2. **Verifier Developer** publishes a new verifier implementation that validates the new proof format.
3. **Connector Operator** evaluates the new verifier. Creates a new Connection on both ledgers using the
   new verifier. Registers as a Connector on the new Connection.
4. **Application Developer** integrates the new Connection. Communicates the migration timeline to End
   Users.
5. **End Users** begin using the application through the new Connection.
6. **Source ledger** switches to the new proof format. The old Connection's verifier can no longer
   validate new proofs. Syncs fail.
7. **CLPR Service Admin** closes the old Connection. The outcome of any remaining in-flight messages is
   ambiguous.
8. **Application Developer** reconciles any ambiguous messages through out-of-band queries to the remote
   ledger.

The critical coordination point is step 6 — the source ledger must maintain backward compatibility long
enough for steps 2-5 to complete.

## 8.3 Responding to a Compromised Verifier

**Roles involved:** Verifier Developer, CLPR Service Admin, Connector Operator, Application Developer,
End User.

1. **Verifier Developer** (or an independent security researcher) discovers a vulnerability in a deployed
   verifier and issues a security advisory.
2. **CLPR Service Admin** halts all affected Connections.
3. **Connector Operators** on affected Connections evaluate the severity and assess financial damage if
   the vulnerability was exploited.
4. **Verifier Developer** publishes a patched verifier implementation.
5. **Connector Operators** create new Connections with the patched verifier and register as Connectors.
6. **Application Developers** migrate to the new Connections.
7. **CLPR Service Admin** closes the old (compromised) Connections.

The window of vulnerability is between step 1 (discovery) and step 2 (halt). The Admin's response time
determines how long the compromised Connection remains exploitable.

## 8.4 Connector Withdrawal Under Load

**Roles involved:** Connector Operator, Application Developer, End User.

1. **Connector Operator** decides to stop serving a Connection and updates the authorization contract to
   reject all new `authorizeMessage` calls.
2. **Application Developers** observe the rejection and switch to an alternative Connector (if available)
   or notify End Users.
3. Messages already enqueued continue through the pipeline. Responses flow back normally.
4. Once all in-flight messages have settled, the **Connector Operator** calls `deregisterConnector`.
   Remaining balance and locked stake are returned.

The protocol prevents deregistration while messages are in-flight. The Connector cannot abandon its
obligations, only wind them down gracefully.

---

# 9. Open Issues

1. **CLPR Service Admin economic incentive gap.** The Admin has significant responsibility (monitoring
   Connections, responding to incidents, coordinating with peers) but no in-protocol economic
   participation. Whether the protocol should include an admin fee or incentive mechanism is an open
   design question.

2. **Verifier Developer sustainability.** Verifier Developers perform critical work but have no in-protocol
   compensation. If external funding dries up, verifiers may go unmaintained, creating a systemic risk
   for all Connections that depend on them.

3. **Connection creation anti-griefing.** Connection creation is permissionless and free (beyond
   transaction fees). Requiring a bonded Connector at registration time would provide economic friction,
   but this is not yet part of the specification.

5. **Connector deregistration timing.** The protocol prevents Connector deregistration while messages
   are in-flight, but the maximum wait time before a Connector can force-exit is not fully specified. A
   Connector whose messages are stuck (e.g., due to a halted Connection) may be unable to deregister
   indefinitely.
