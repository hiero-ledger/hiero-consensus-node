# TSS-Ledger-ID

---

## Summary

This proposal is for the integration of a threshold signature scheme into the consensus nodes to create a
private/public key pair for the ledger that can be used to sign blocks and other messages. The private key must be a
secret that no one knows. The public key is known by all and functions as the ledger id. The consensus nodes must be
able to aggregate their individual signatures on a message into a valid ledger signature that is verifiable by the
ledger id. Part of our goal is that users could write EVM smart contracts that can verify the block signatures.

|      Metadata      |                  Entities                  |
|--------------------|--------------------------------------------|
| Designers          | Edward, Cody, Austin, Kore, Anthony, Artem |
| Functional Impacts | Services, DevOps                           |
| Related Proposals  | TSS-Library, TSS-Roster, TSS-Block-Signing |
| HIPS               | N/A                                        |

---

## Purpose and Context

The purpose of this proposal is to integrate a Threshold Signature Scheme (TSS) based on
[BLS](https://en.wikipedia.org/wiki/BLS_digital_signature) into consensus nodes to produce a network ledger id and offer
a mechanism for aggregating signature messages such that the final result can be verified using the ledger id.

In this TSS based on BLS, the network is assigned a durable private/public key
pair where the public key is the ledger id and the private key is a secret that
no entity knows. Each node in the network is given a number of shares
proportional to its consensus weight and each share is a BLS key on the same
elliptic curve as the ledger key. When the network needs to sign a message,
each node uses its shares to sign that message (e.g. a block root hash) and
gossips the signature out to the network. When a node has collected enough
signatures to meet a configured threshold on the same message, it can then
aggregate the signatures into a final ledger signature that is space-efficient
and is verifiable with the ledger id.

The TSS effort has been broken down into 6 separate proposals: TSS-Library, TSS-Roster, TSS-Ledger-ID,
TSS-Block-Signing, TSS-Ledger-ID-Updates, and TSS-Ledger-New-Networks.

1. The `TSS-Library` proposal contains the cryptographic primitives and algorithms needed to implement TSS.
2. The `TSS-Roster` proposal introduces the data structure of a consensus `Roster` to replace the platform's concept of
   an `AddressBook` and modifies the life-cycle for when the platform receives new consensus rosters.
3. This proposal (`TSS-Ledger-ID`) depends on the first two proposals and describes
   the integration of a threshold signature scheme into the consensus node, giving
   the network a ledger id that can verify network signatures.
4. The `TSS-Block-Signing` proposal is everything needed to support the signing of blocks.
5. The `TSS-Ledger-ID-Updates` proposal covers the process of resetting and transplanting ledger ids between networks.
6. The `TSS-Ledger-New-Networks` proposal covers the process of setting up a new network with a ledger id.

This proposal, `TSS-Ledger-ID`, covers changes to the following elements:

- Ledger ID
  - The process of generating the ledger key pair for existing networks and testing environments.
  - Transferring the ability of the ledger to sign a message from one set of consensus nodes to another.
  - The new state data structures needed to store TSS Key material and the ledger id.
  - The new components needed in the framework to support creating and transferring the ledger key.
- System Behavior
  - Each day, generating a new candidate roster at midnight when the node weights are calculated.
  - Adding a new `TSS Base Service` for generating the TSS key material for the candidate roster.
  - A modified startup process for adopting rosters and initializing the signing capability.
- Cryptography
  - For each node, the creation of a new, private to the node, Elliptic Curve (EC)
    key pair, which we call the `tssEncryptionKey`, that is used in creating and
    decrypting shares.

### Goals

The following capabilities are goals of this proposal:

1. `TSS Bootstrap on Existing Network` - Able to setup an existing network with a ledger public/private key pair.
2. `Keying The Next Roster` - Able to transfer the ledger id and ability for the network to sign a message from one
   set of consensus nodes to another.
3. `TSS Bootstrap in Test Networks` - Able to setup a test network with a ledger public/private key pair without
   restart.
4. Testing of the TSS signing capability with verification by an EVM smart contract using the ALT_BN128
   [precompiles that are available in the EVM](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1829.md).

### Non-Goals

The following are not goals of this proposal:

1. Achieving a fully dynamic address book life-cycle.
2. Public API for signing blocks.
3. Removing or replacing the use of the RSA key in
   1. Signing and verifying events.
   2. Signing and verifying the state.
   3. ISS detection.
4. Modifying how reconnect works.
5. Explaining how state transplants between networks or resets of the ledger id work.
6. TSS Bootstrapping for new networks.

### Dependencies, Interactions, and Implications

Dependencies on the TSS-Library

- At minimum, the top-level API in the TSS-Library is defined and can be mocked for initial development.
- The complete implementation of the TSS-Library is required for the TSS-Ledger-ID proposal to be fully implemented.
- Consensus nodes must have their `tssEncryptionKey` generated with the private key stored on disk and the public key
  stored in the roster prior to the TSS-Ledger-ID proposal being delivered.

Dependencies on the TSS-Roster

- Delivery of TSS-Ledger-ID will require that the TSS-Roster proposal is delivered in a prior release.
- Development of the TSS-Ledger-ID proposal is dependent on TSS-Roster proposal in the following ways:
  - The `Roster` data structures must be defined and stored in state.
  - To modify the roster adoption lifecycle with TSS conditions, the TSS-Roster life-cycle must be implemented.

Impacts of TSS-Ledger-ID to Services Team

- Services will need to generate a candidate roster at midnight each day when the node weights are calculated and
  submit the roster to the `TssBaseService` for keying.
- Services may need to know or detect when the candidate roster fails to be adopted due to not having enough key
  material generated.
- More than half and preferably all the nodes' public `tssEncryptionKeys` will need to be added to the address book
  before the ledger id can be generated.
  - This requires modification of the HAPI transactions that manage the address book.

Impacts to DevOps Team

- Each consensus node will need a new `tssEncryptionKey` in addition to the existing RSA key.
  - Key generation will have to happen before a node can join the network.
  - The public keys will need to be added to the address book through signed HAPI transactions.
- Changes to the address book must be made sufficiently far in advance (preferably, more than one day in advance) of
  an upgrade to allow sufficient time for keying to occur so the roster can be adopted at the next upgrade.
- Config only software upgrades can be used to trigger the adoption of a candidate roster, if the candidate roster
  has received enough yes votes.

Implications Of Completion

- The network will have a public ledger id that can verify ledger signatures.
- The active consensus nodes can transfer the ability to sign messages to the next set of consensus nodes.
- `TSS-Block-Signing` and `TSS-Ledger-ID-Updates` proposals becomes unblocked for development.

### Requirements

TSS Core Requirements

| Requirement ID |                                                        Requirement Description                                                        |
|----------------|---------------------------------------------------------------------------------------------------------------------------------------|
| TSS-001        | The ledger MUST have a public/private signing key pair that does not change when the consensus roster changes.                        |
| TSS-002        | The ledger private key MUST be a secret that nobody knows.                                                                            |
| TSS-003        | The `ledger id` MUST be a public key that everyone knows and that is able to verify signatures from the ledger private key.           |
| TSS-004        | The ledger signature MUST be produced through aggregating signatures from consensus nodes representing at least 1/3 consensus weight. |
| TSS-005        | There MUST be a way to bootstrap an existing network's consensus roster with the ledger public/private signing key                    |
| TSS-006        | There MUST be a way for a new network to generate a public/private signing key for the ledger.                                        |
| TSS-007        | There MUST be a way to rotate the ledger's public/private signing key.                                                                |
| TSS-008        | The ledger signature SHOULD be verifiable by an EVM smart contract without excessive gas cost.                                        |
| TSS-009        | The TSS implementation SHOULD be able to switch elliptic curves.                                                                      |
| TSS-010        | The TSS algorithm SHOULD be able to model consensus weight with high precision.                                                       |
| TSS-011        | The TSS design SHOULD minimize the number of steps a Node Operator is required to perform.                                            |

Block Signing Requirements

| Requirement ID |                                   Requirement Description                                   |
|----------------|---------------------------------------------------------------------------------------------|
| TSSBS-001      | The initial block in the block stream SHOULD be signable and verifiable with the ledger id. |
| TSSBS-002      | On a new network, the initial block MUST be block 1                                         |

### Design Decisions

Since the consensus node does not depend on TSS capabilities, a new `TSS Base Service` will be created in the
application to manage state changes and startup behavior. The `TSS Base Service` will be responsible for:

1. Keying the candidate roster with the TSS key material.
2. On Startup: Determining whether to adopt the candidate roster as the active roster for the platform.

#### New Address Book Life-Cycle

Each day, on the first transaction after midnight, after the staking information has been updated by the app, it will
generate a new roster and set it as the candidate roster on the `TSS Base Service`, replacing any previously
set candidate roster. The `TSS Base Service` will attempt to key the candidate roster. This new roster, once keyed,
is not used, unless there is a network upgrade.

**Normal Operation:** On the day the network is upgraded, the latest candidate roster will have started the keying
process at midnight (UTC-0). The upgrade is usually performed during business hours, after 8:00 am (UTC-5). This is
a 13-hour window for the key material to be computed. It is expected that this is sufficient time to compute the key
material, but under certain conditions there is a possibility that the key material will not be computed in time.
Once the ledger id for the network is determined and adopted, a candidate roster will not be adopted until its key
material can recreate the same ledger id. If the key material is not generated in time, the candidate roster will
not be adopted and the network will continue to use the previous active roster.

Special cases around roster adoption in the bootstrap process are explained later.

![TSS Roster Lifecycle](./TSS-Roster-Lifecycle.drawio.svg)

#### TSS Algorithm

The threshold signature scheme chosen for our implementation is detailed in [Non-interactive distributed key
generation and key resharing (Groth21)](https://eprint.iacr.org/2021/339). This scheme is able to meet the
core requirements listed above. The rest of this proposal details the design of how to implement this scheme in the
consensus nodes. See the TSS-Library proposal for more details on the Groth21 algorithm.

The Groth21 algorithm uses [Shamir Secret Sharing](https://en.wikipedia.org/wiki/Shamir%27s_secret_sharing) to hide the
private key of the ledger and distribute shares of it so that a threshold number of node signatures on a message can
generate the ledger signature on the same message. The threshold should be high enough to ensure at least 1 honest
share is required to participate in the signing since a threshold number of shares can also collude to recover the
ledger private key, if they so choose.

The Groth21 algorithm requires a specific class of Elliptic Curve (EC) with the ability to produce bilinear pairings.
Each node will need its own long-term EC key pair, called a `tssEncryptionKey`, based on the chosen curve, for use
in the Groth21 TSS algorithm. Each node receives some number of shares in proportion to the consensus weight
assigned to it. Each share is an EC key pair on the same curve. All public keys of the shares are known, and
aggregating a threshold number of share public keys will produce the ledger id. Each node only has access to the
private key of its own shares, and none other. Each node must keep their private keys secret because a threshold
number of share private keys can aggregate to recover the ledger private key. A threshold number of signatures
on the same message from the share private keys will aggregate to produce a ledger signature on the message.

Transferring the ability to generate ledger signatures from one set of consensus nodes to another is done by having
each node generate a `TssMessage` for each share that fractures the share into a number of subshares or `shares of
shares`. Each `share of a share` is encrypted with the public `tssEncryptionKey` of the node in the next consensus
roster that it belongs to so that only the intended node can use the `share of a share` to recover that node's
share's private key. The collection of `TssMessages` generated from nodes in the previous consensus roster forms the
`key material` for the new consensus roster.

To bootstrap a network and generate the ledger private and public keys, each node creates a random share for
themselves and generates a `TssMessage` containing `shares of shares` for the total number of shares needed in the
consensus roster. The use of random shares at the outset creates a random ledger private key that nobody knows.

In the context of the consensus nodes using the TSS library, the term `share index` refers to the index of the share as
it is returned in the list of public shares from the tss library. The first share in the list has share index `0`. If
there are 10 shares total, the last share index is `9`. The shares are assigned to nodes in the order of NodeIds. If
Node 0 has 3 shares, then it is given share with index `0`, `1`, and `2`. If Node 1 has 2 shares, then it is given
share indexes `3` and `4`, and so on. The `TssMessages` are similarly ordered. TssMessage `0` is the message generated
for share index `0`.

![TSS re-keying next consensus roster](./Groth21-re-keying-shares-of-shares.drawio.svg)

This process of keying the candidate roster during an address book change is asynchronous and takes an
indeterminate amount of time through multiple rounds of consensus to complete. This requires saving incremental
progress in the state to ensure that the process can resume from any point if a node or the network restarts.
Switching to the candidate roster must not happen until enough nodes have voted that they have verified a threshold
number of TssMessages from the active roster. Verification succeeds when a node is able to recover the ledger id.
The first threshold number of valid TssMessages to come to consensus are used to recover the ledger id. A vote
consists of a bit vector where each bit index corresponds to the order of TssMessages as they have come through
consensus. A bit at index `i` in the vote is set to 1 if the `ith` TssMessage to come through consensus is
valid and used in the recovery of the ledger id, otherwise the bit is `0`. The `0th` TssMessage is the first to
reach consensus.

##### Elliptic Curve Decisions

Our first implementation of Groth21 will use the ALT_BN_128 ([BN254](https://hackmd.io/@jpw/bn254)) elliptic curve which
has precompiles in the EVM. If the Ethereum ecosystem creates precompiles for BLS12_381, we may switch over to that
more secure curve.

##### New Elliptic Curve Node Keys

Each node will need a new `tssEncryptionKey` pair in addition to the existing RSA key pair. The `tssEncryptionKey` pair
will be used in the Groth21 algorithm to generate and decrypt `TSS-Messages`. These new EC Keys will not be used for
signing messages, only for generating the shares. It is the share keys that are used to sign messages. The public
keys of the `tssEncryptionKeys` must be in the address book.

##### Groth21 Drawbacks

The Groth21 algorithm is not able to model arbitrary precision proportions of weight assigned to nodes in the
network. For example if each node in a 4 node network received 1 share, then every share has 1/4 of the total.
If there are a total of N shares, then the distribution of weight can only be modeled in increments of (1/N) * total
weight. The cost of re-keying the network in an address book change is quadratic in the number of shares. (See the
picture of TssMessages above.) This forces us to pick a number of total shares with a max value in the thousands.
This modeling of weight uses small integer precision.

##### Calculating Number of Shares from Weight and Recovery Threshold

Given that we have a low resolution for modeling weight, the number of shares assigned to each node will be
calculated as follows using java integer arithmetic where division rounds down:

- Let `N` be the max number of shares that any node can have. (A configured value.)
- Let `maxWeight` be the max weight assigned to any node.
- Let `weight[i]` be the weight assigned to node `i`.
- Let `numShares[i]` be the number of shares assigned to node `i`.
- then `numShares[i] = (N * weight[i] + maxWeight - 1) / maxWeight`

###### Threshold for recovery of Signatures and Ledger Id.

The network must be able to tolerate less than 1/3 of the weight assigned to malicious nodes. Modeling the weight as
a fewer number of shares introduces a potential error in representation. To account for this error, we need a
guaranteed safe threshold for the number of shares that must be used to create a valid signature.

A threshold of at least `(TS + 2) / 2`, with java integer division rounding down, has been proven secure under the
following conditions:

* Let `minWeight` be the minimum non-zero weight assigned to any node.
* Let `maxWeight` be the maximum weight assigned to any node.
* Let `R = maxWeight / minWeight` be the real number ratio.
* Let `N>0` be the max number of shares that any node can have.
* If `R <= 2*N` then the threshold of `(TS + 2) / 2` is secure.

Required Theorems:

- SECURITY: If the nodes in a set together have at least 1/2 of the shares, then they have at least 1/3 of the weight.
- LIVENESS: If the nodes in a set together have more than 2/3 of the weight, then they have more than 1/2 of the shares.

Proof Sketch by Dr. Leemon Baird:

- Assume WLOG that the weights are real numbers. Further assume WLOG that the largest weight held by any node is exactly
  N, and that all nodes have positive weight (none have zero).
- For any given node, you can calculate its weight per share (wps). That's its weight divided by how many shares it
  gets. It gets the shares from the ceiling of the ratio. It's easy to show that if we assume R=2N, then this guarantees
  a wps in the range [1/2, 1] inclusive, for every share. Imagine each of its shares as a card with that wps number
  written on it. In other words, that card is a single share, and it by itself constitutes that much weight. We are
  basically dividing up a given node's weight equally among all its shares.
- Then, ignore how the shares are distributed among the nodes. Look solely at them as an arbitrary set of shares with
  a wps value assigned to each one, without worrying about how many shares get each value, or how many shares each node
  gets. We only know they are all in the range [1/2, 1].
- If you want to collect 1/3 weight and have as large a fraction of the shares as possible, then that happens when
  all your shares have wps of 1/2, and all the ones you don't collect have a wps of 1. Which means you'll have 1/2 the
  shares.
- Conversely, if you want to collect more than 2/3 the weight while holding as few shares as possible, that happens when
  you collect only shares with wps of 1, and all the rest are 1/2. And again, that means you have more than 1/2 the
  shares.
- So both theorems are proved.

###### Threshold for Votes to Adopt a Candidate Roster

The threshold for votes to adopt a candidate roster will be at least 1/3 of the consensus weight to ensure that at
least 1 honest node has validated the TSS key material.

###### Impacts on Service's Calculation of Weight from Staked HBars

The services layer will track staking to nodes, clipping any stake above the max, and reducing any stake below `1/R` of
the maximum clipped stake to zero (where `R` is a setting of services, set to twice the max number of shares allowed per
node).

The resulting stake (after clipping or zeroing) is passed to the platform layer, which treats it as a “weight” for
consensus, and which then generates a number of shares for each node. That share count is proportional to that weight,
and acts as a low-resolution version of that weight.

##### TSS Algorithm - Alternatives Considered

The list of options considered here were based off of prototypes developed by [Rohit Sinha](https://github.com/rsinha),
the Hashgraph cryptography expert. The Groth21 algorithm was chosen because it is efficient for use in smart
contract verification, and we can assign a multiplicity of shares to nodes to get close enough in modeling the
distribution of weight between nodes.

| Requirement | hinTS | Groth21 |
|-------------|-------|---------|
| TSS-001     | Yes   | Yes     |
| TSS-002     | Yes   | Yes     |
| TSS-003     | Yes   | Yes     |
| TSS-004     | Yes   | Yes     |
| TSS-005     | Yes   | Yes     |
| TSS-006     | Yes   | Yes     |
| TSS-007     | Yes   | Yes     |
| TSS-008     | No    | Yes     |
| TSS-009     | Yes   | Yes     |
| TSS-010     | Yes   | No      |

###### hinTS

The hinTS algorithm is a threshold signature scheme that is able to measure double precision distributions of weight
across nodes. The complicating factor with hinTS is that during an address book change over, a recursive proof needs
to be constructed to prove that the new roster is a descendent of the original genesis roster. Validation of this
recursive proof proved too expensive for EVM smart contracts, even with elliptic curve precompiles.

#### TSS Bootstrap Process

The TSS bootstrap process is the process of generating the ledger public/private key pair for an
existing network. There are several options to consider in the TSS bootstrap process:

1. Do we allow block number and round number to be out of sync?
2. Do we need the initial block produced to be signed by the ledger id?
3. For existing networks, does the TSS bootstrap roster sign blocks prior to its existence?

The answer to these questions has evolved over time, but their current answers are:

1. Yes, it will be possible to have multiple rounds per block.
2. If at all possible, yes, but a no will be acceptable if we ship block stream without block proofs.
3. No, only blocks created after the ability to sign will be signed.  Merkle Proofs can be constructed to create
   block proofs for earlier blocks.

##### For Existing Networks

In order to reduce risk and density of complex software changes, giving the nodes their `tssEncryptionKeys` and
changing the roster life-cycle through the TSS-Roster effort should be completed and delivered in a software release
prior to the delivery.

The schedule of delivery of capability is explored in a later section in this proposal.

The setup of the bootstrap key material will be observable in the record stream prior to the Block Streams going live,
but this observability is not consequential. What matters is the cryptographic verification that the TSS
material can generate the ledger key.

##### For Test Networks

Since restarts in test networks are complicated, a test-only configuration flag will be added to tell the system to
key the active roster with TSS key material and immediately adopt the ledger id without waiting for a software
upgrade to adopt the candidate roster. This will allow the test network to generate a ledger id without a restart.
If a candidate roster is set, it will be keyed to inherit the ledger id generated for the active roster.

##### TSS Bootstrap Process - Alternatives Considered

| N |                                                    Option                                                     | 1-1 round to block | round # == block # | Ledger signs initial block | Ledger signs block 1 | TSS keying in hashgraph history | TSS keying in block history | Minimal DevOps Impact | Implementation Complexity |                                       Notes                                        |
|---|---------------------------------------------------------------------------------------------------------------|--------------------|--------------------|----------------------------|----------------------|---------------------------------|-----------------------------|-----------------------|---------------------------|------------------------------------------------------------------------------------|
| 1 | TSS Setup in block history, initial block > 1 and not signed                                                  | YES                | YES                | NO                         | NO                   | YES                             | YES                         | YES                   | LOW                       | Mainnet if Block Streams ships before Block Proofs                                 |
| 2 | TSS Setup not in block history, initial block > 1 and is signed                                               | YES                | YES                | YES                        | NO                   | YES                             | NO                          | YES                   | LOW                       | Mainnet if Block Streams ships with Block Proofs                                   |
| 3 | TSS Setup not in block history, initial block == 1 and is signed                                              | YES                | NO, fixed offset   | YES                        | NO                   | YES                             | NO                          | YES                   | LOW-MED                   | Cognitive burden to reason about block # and round # discrepancy.                  |
| 4 | TSS Setup in block history, initial block > 1, covers all prior rounds, and is signed                         | NO                 | YES                | YES                        | NO                   | YES                             | YES                         | YES                   | MED                       |                                                                                    |
| 5 | TSS Setup in block history, initial block == 1, covers all prior rounds, and is signed                        | NO                 | NO, fixed offset   | YES                        | NO                   | YES                             | YES                         | YES                   | MED                       | Cognitive burden to reason about block # and round # discrepancy.                  |
| 6 | Retrospectively sign the initial blocks with TSS once it becomes available.                                   | YES                | YES                | YES                        | YES                  | YES                             | YES                         | YES                   | HIGH                      | TSS Genesis Roster signs older blocks.  This is compatible with all other options. |
| 7 | Pre-Genesis: Separate app with genesis roster creates key material in state before network officially starts. | YES                | YES                | YES                        | YES                  | NO                              | NO                          | NO                    | HIGH                      | Applies to new networks only.  Use Option 1 or 2 for existing networks.            |
| 8 | Instead of separate app, detect genesis, key the state, restart the network with a keyed genesis state.       | YES                | YES                | YES                        | YES                  | NO                              | NO                          | YES                   | HIGH                      | Applies to new networks only.  Use Option 1 or 2 for existing networks.            |

Option 2 was chosen for existing networks and Option 1 is chosen for test networks. Option 8 will likely be used
for new networks, but this decision is deferred to the `TSS-Ledger-New-Networks` proposal.

---

## Changes

The changes are presented in the following order:

1. Public API
2. Core Behaviors
3. Component Architecture
4. Configuration
5. Metrics
6. Performance

### Public API

The public API changes consist of the following:

- System Transactions
- State Data Structures

#### System Transactions

The following are new system transactions needed to key rosters and generate ledger signatures:

1. `TssMessageTransaction` - Used to send a TssMessage to the network for a candidate roster.
2. `TssVoteTransaction` - Used to vote on the validity of TssMessages for a candidate roster.

The creator of the event is the presumed author of the system transactions.

##### TssMessage System Transaction

```protobuf
/**
 * The TssMessageTransaction is used to send a TssMessage to the network for a candidate roster.
 */
message TssMessageTransaction {
  /**
   * A hash of the roster containing the node generating the TssMessage.
   */
  bytes source_roster_hash = 1;

  /**
   * A hash of the roster that the TssMessage is for.
   */
  bytes target_roster_hash = 2;

  /**
   * An index to order shares.
   * This establishes a global ordering of shares across all shares in
   * the network.  It corresponds to the index of the public share in the
   * list returned from the TSS library when the share was created for
   * the source roster.
   */
  uint64 share_index = 3;

  /**
   * A byte array containing the the TssMessage generated by the node
   * for the share_index.
   */
  bytes tss_message = 4;
}
```

##### TssVote System Transaction

```protobuf
/**
 * A transaction used to vote on the validity of TssMessages for a candidate roster.
 */
message TssVoteTransaction {

  /**
   * A hash of the roster containing the node generating this TssVote.
   */
  bytes source_roster_hash = 1;

  /**
   * A hash of the roster that this TssVote is for.
   */
  bytes target_roster_hash = 2;

  /**
   * An identifier (and public key) computed from the TssMessages for the target roster.
   */
  bytes ledger_id = 3;

  /**
   * A signature produced by the node.
   * This is produced using the node RSA signing key to sign the ledger_id.
   * This signature is used to establish a chain of trust in the ledger id.
   */
  bytes node_signature = 4;

  /**
   * A bit vector where the index position of each bit corresponds to the
   * sequence number of a `TssMessageTransaction`. The least significant bit
   * of byte[0] corresponds to sequence number 0 and the most significant bit
   * of byte[0] corresponds to sequence number 7.  The least significant bit
   * of byte[1] corresponds to sequence number 8 and the most significant bit
   * of byte[1] corresponds to the sequence number 15, and so on. If a bit is
   * set to 1, then the TssMessage for the corresponding TssMessageTransaction
   * sequence number was received as one of the first Threshold number of
   * valid TssMessages in consensus order.  If a bit is set to 0, then the
   * TssMessage was invalid or there was no TssMessageTransaction with a
   * sequence number corresponding to the bit index.
   */
  bytes tss_vote = 5;
}
```

#### State Datastructures

The state needs to store the relevant ledger id and TSS Data (key material and votes).

##### Ledger Id Queue

The `ledgerId` is the public ledger key able to verify ledger signatures. It is used by the platform and other
entities outside the consensus node. This value should not change during normal address book changes and its value does
not change unless the network goes through another TSS bootstrap process. When the value changes, to transfer trust,
the old ledger private key should sign the new ledger id or the nodes should sign the new ledger id with their RSA
signing keys, or both. Quads of (ledger id, round, ledger signature, roster_signatures) are stored in a queue to record
the history of ledger ids on the network.

NOTE: The detailed processes for updating the ledger id can be found in the `TSS-Ledger-ID-Updates` proposal.

```protobuf
/**
 * A public key that identifies the ledger and can be used to verify ledger signatures.
 */
message LedgerId {

  /**
   * A public key.
   * This key both identifies the ledger and can be used to verify ledger signatures.
   * <p>
   * This value MUST be set.
   * This value MUST NOT be empty.
   * This value MUST contain a valid public key.
   */
  bytes ledger_id = 1;

  /**
   * A round number.
   * This identifies when this ledger id becomes active.
   * <p>
   * This value is REQUIRED.
   */
  uint64 round = 2;

  /**
   * A signature from the prior ledger key.
   * This signature is the _previous_ ledger ID signing _this_ ledger ID.
   * <p>
   * This value MAY be unset, if there is no prior ledger ID.
   * This value SHOULD be set if a prior ledger ID exists
   * to generate the signature.
   */
  bytes ledger_signature = 3;

  /**
   * The signatures from nodes in the active roster signing the new ledger id.
   * These signatures establish a chain of trust from the network to the new ledger id.
   * <p>
   * This value MUST be present when the ledger signature of a previous ledger id is absent.
   */
  RosterSignatures roster_signatures = 4;
}

/**
 * A collection of signatures from nodes in a roster.
 */
message RosterSignatures {
  /**
   * A roster hash for the roster that the node signatures are from.
   */
  bytes roster_hash = 1;

  /**
   * A list of node signatures on the same message where all node ids in the NodeSignature objects are from the
   * roster that the roster_hash represents.
   */
  repeated NodeSignature node_signatures = 2;
}

/**
 * A pair of a _RSA_ signature and the node id of the node that created the signature.
 */
message NodeSignature {
  /**
   * The node id of the node that created the _RSA_ signature.
   */
  uint64 node_id = 1;

  /**
   * The bytes of an _RSA_ signature.
   */
  bytes node_signature = 2;
}

```

##### TSS Data Maps

There are two maps of tss related data stored in the state through the public state API.

1. `TssMessageMap` - A map of TssMessageTransactions that have come to consensus for a roster.
2. `TssVoteMap` - A map of TssVoteTransactions that have come to consensus for a roster.

Keys in the `TssMessageMap` have the following structure:

```protobuf
/**
  * A key for use in the TssMessageMaps.
  */
message TssMessageMapKey {

  /**
   * A hash of the target roster for the associated value in the map.
   */
  bytes roster_hash = 1;

  /**
   * A number representing consensus order.
   * This declares the order in which the mapped value came to consensus.
   */
  uint64 sequence_number = 2;
}
```

The values in the `TssMessageMap` are of type `TssMessageTransaction` where the `target_roster_hash` in the value is
the `roster_hash` in the key.

Keys in the `TssVoteMap` have the following structure:

```protobuf
/**
  * A key for use in the TssVoteMaps.
  */
message TssVoteMapKey {

  /**
   * A hash of the target roster for the associated value in the map.
   */
  bytes roster_hash = 1;

  /**
   * The node id of the node that created the TssVote.
   */
  uint64 node_id = 2;
}
```

The values in the `TssVoteMap` are of type `TssVoteTransaction` where the `target_roster_hash` in the value is the
`roster_hash` in the key. The order in which votes come to consensus is not important. At least 1/2 of the weight
must contribute yes votes that have the same vote structure for the candidate roster to be adopted. This ensures
that at least 1 honest node has voted yes for the majority voting pattern.

Lifecycle Invariants

1. Inserts should only be made for candidate rosters that are going through the keying process.
2. When a roster is no longer stored in the state, the corresponding key material and votes should be removed.
3. Every roster that has become active after the TSS bootstrap process must have sufficient key material to be
   able to recover the ledger id.

### Core Behaviors

We will add new behavior related to the bootstrap process of creating the ledger id
and transferring the ability to sign messages from one roster to the next roster

#### Startup for New Networks

At the start of a new network there will be a new pre-genesis phase to generate the key material for the genesis
roster and then restart the network, after adding the new key material and ledger id
to the genesis state.

The following startup sequence on new networks is modified from existing practices.

1. The app will read the genesis address book and initial cryptography from disk
2. The app will generate a genesis state and genesis roster.
3. The app will ensure that it does not accept user transactions.
4. The app will start the platform with the above and key the genesis roster with the TSS key material.
5. After the genesis roster is keyed, the app will adopt the ledger id and restart the platform with the key
   material and ledger id added to the genesis state.
6. User transactions are accepted after the platform restarts and becomes active.
7. Nodes in the genesis roster that did not participate in the above bootstrapping process must perform a reconnect
   to receive the updated state containing the TSS key material.

#### Startup for Existing Networks

##### The Release Containing the `tssEncryptionKey`

This release must occur with or after the complete deployment of the TSS-Roster proposal.

This release contains all the state data structures needed to store the public `tssEncryptionKey` in the state and all
code needed to support storing and retrieving the private `tssEncryptionKey` on disk and managing the `tssEncryptionKey`
life cycle, including key rotation.

The release supporting the addition of the `tssEncryptionKey` to the roster also provides modified HAPI
transactions for updating the address book with a node's `tssEncryptionKey`. Until the next software upgrade, only
the candidate roster will have the `tssEncryptionKey` possibly set in roster entries.

The only change to the startup sequence is that the `tssEncryptionKey` private key is read from disk, provided to
the platform through the `PlatformBuilder` and validated against the public `tssEncryptionKey` in the active roster.
A mismatch is a critical error that will cause the node to shut down.

##### The Release Containing the TSS Bootstrap Process

This release must be at least 1 release after the `tssEncryptionKey` release.

This sequence will work for this release and all subsequent releases, unless the
startup sequence is modified by subsequent releases.
The following startup sequence will occur:

1. The app will read the state and cryptography from disk (including support for
   reading the private `tssEncryptionKey`)
2. The app will validate the cryptography and state.
3. If a software upgrade is detected,
   1. If the existing active roster and candidate roster do not have complete
      key material and the network does not yet have a ledger id, the node will
      adopt the candidate roster immediately on software upgrade.
      - This will retain the pre-ledger-id behavior of the network until the
        ledger id can be created for the first time.
   2. If the candidate roster exists, has key material, and the number of yes
      votes passes the threshold for adoption, then the candidate roster
      will be adopted and become the new active roster.
      1. Validation Check: if the state already has a ledger id set, the ledger
         id recovered from the key material must match the ledger id
         in the state.
      2. If there is no ledger id in the state and the previously active roster
         has no key material, the ledger id recovered from the candidate roster
         key material will be set in the state as the new ledger id.
   3. In all other cases the existing active roster will remain the
      active roster.
4. The application will provide the platform with the active roster to use.
5. The platform will continue through its normal startup sequence.

Note: If this is release N, then if all goes well and the candidate roster is successfully keyed, the next software
upgrade (release N+1) is when the network will receive its ledger id. Release N+2 should carry forward the ledger
id into the next roster automatically. No additional code deployment is required to make this happen.

##### The Cleanup Release

This release will contain any code cleanup required from the migration to the new TSS bootstrap process.

### Component Architecture

A new Service called `TSS Base Service` is introduced to the application layer. This service is responsible for all
TSS related state and computation. In this proposal, the capability is scoped to keying the candidate rosters to
generate a ledger id and transfer it from one roster to the next. In the `TSS-Block-Signing` proposal, the
capability will be expanded to signing messages with share private keys and aggregating the signatures to create a
ledger signature from the network.

The TSS Base Service will be broken out into one interface and two components.

1. `TssBaseService` - the interface that defines the public API for the TSS Base Service.
2. `TssStateManager` - executes on the transaction handling thread and updates state.
3. `TssCryptographyManager` - executes on a separate thread and is responsible for cryptographic operations.
4. Transaction Resubmission

![TSS Platform Wiring](./TSS-Wiring-Diagram.drawio.svg)

#### TssBaseService

The `TssBaseService` interface provides the inter-service API for the TSS Base Service. It is responsible for
setting the candidate roster for the TSS base service to generate key material. This is a necessary step before the
roster can be adopted. In the `TSS-Ledger-ID-Block-Signing` proposal, the `TssBaseService` will be expanded to include
the ability to request ledger signatures on messages.

```java

/**
 * The TssBaseService will attempt to generate TSS key material for any set candidate roster, giving it a ledger id and
 * the ability to generate ledger signatures that can be verified by the ledger id.  Once the candidate roster has
 * received its full TSS key material, it will be made available for adoption by the platform.
 */
public interface TssBaseService {

    /**
     * Set the candidate roster for the TSS base service to generate key material.  This is a necessary step before the
     * roster can be adopted by the platform.  There can be only one candidate roster at a time. If there is an existing
     * candidate roster in the state, it will be replaced and all related data will be removed from the state. For
     * this reason, setting the candidate roster does not guarantee its adoption.
     *
     * @param candidateRoster The candidate roster to set.
     */
    void setCandidateRoster(final Roster candidateRoster);
}
```

#### `TssStateManager`

The TSS state manager is executed at startup and on the transaction handling thread and is capable of reading and
writing the state during round handling.

Responsibilities:

1. At node startup
   - Read all rosters, `TssMessageTransactions`, and `TssVoteTransactions` from the state.
   - If the active roster has TSS key material, compute the ledger id from the public shares of the active roster's
     `TssMessageTransactions` and verify it matches the ledger id in the state, log an error and throw an illegal
     state exception if it does not match.
     - NOTE: handling changes to the LedgerId are addressed in the `TSS-Ledger-ID-Updates` proposal.
   - If conditions are met, adopt the candidate roster and cleanup the state.
     - If there was no Ledger Id in the state and the candidate roster has a ledger id, set the ledger id in the
       state.
   - Give the active roster to the `TssCryptographyManager`.
   - Give all `TssVoteTransactions` to the `TssCryptographyManager`.
   - Give all `TssMessageTransactions` to the `TssCryptographyManager`.
   - If it exists, give the candidate roster to the `TssCryptographyManager`.
     - setting the candidate roster last ensures that `TssCryptographyManager` can avoid sending unnecessary
       `TssMessageTransactions` to the network.
   - Give/Send/Set the active roster in the PlatformBuilder before building the platform.
2. Handle `TssMessageTransaction` system transactions.
   - Handle items in consensus order.
   - Determine the next sequence number to use for the `TssMessageMap` key.
   - Insert into the `TssMessageMap` if it is correct to do so.
     - Don’t insert multiple messages for the same share index.
   - If inserted into the `TssMessageMap`, send the `TssMessageTransaction` to the `TssCryptographyManager`.
3. Handle `TssVoteTransaction` system transactions.
   - If a threshold number of votes (totaling at least 1/3 of weight), all with the same vote byte
     array, have already been received for the candidate roster, then discard the `TssVoteTransaction`.
   - Insert into the `TssVoteMap` if it is correct to do so.
     - Don’t insert multiple votes from the same node; discard it if it is redundant.
   - Send the `TssVoteTransaction` to the `TssCryptographyManager`.
4. Method `setCandidateRoster(Roster candidateRoster)`:
   - If there already exists a candidate roster in the state, erase it and its related data from the `TssMessageMap`
     and `TssVoteMap`.
   - Set the new candidate roster in the state.
   - Send the candidate roster to the `TssCryptographyManager`.

#### `TssCryptographyManager`

The TSS manager executes on a separate thread and is responsible for all asynchronous cryptography computations that
cannot be performed on the transaction handling thread. The computational responsibilities include:

- Keying the candidate roster.
- Submitting TssMessageTransactions to the transaction pool.
- Submitting TssVoteTransactions to the transaction pool.
- Computing public and private keys of shares when there is enough key material generated for the candidate roster.
- Computing the ledger id.
- Submitting the candidate roster to the platform for adoption when the candidate roster has enough key material to
  generate the ledger id.

##### `TssCryptographyManager` Details

Internal Elements:

1. NodeId (constructor)
2. TssEncryptionKey (constructor)
3. private RSA signing key (constructor argument)
4. integer maxSharesPerNode (constructor argument from config)
5. boolean keyActiveRoster (constructor argument from config)
6. boolean createNewLedgerId := default false
7. active roster hash
8. candidate roster hash
9. Map<RosterHash, Roster> rosters
10. Map<RosterHash, Map<NodeID, Integer>> shareCounts
11. Map<RosterHash, List<PairingPrivateKey>> privateShares
12. Map<RosterHash, List<PairingPublicKey>> publicShares
13. Map<RosterHash, PairingPublicKey> ledgerIds
14. Map<RosterHash, List<TssMessageTransaction>> tssMessages
15. Map<RosterHash, List<TssVoteTransaction>> tssVotes
16. Set<RosterHash> votingClosed

Inputs:

1. Active Roster
2. Candidate Roster
3. TssMessageTransactions
4. TssVoteTransactions

Input Invariants: (Non-Dynamic AddressBook Semantics)

1. Active Roster must be set before Candidate Roster and never set again.

Outputs:

1. TssMessageTransaction
2. TssVoteTransaction
3. A fully keyed candidate roster

`On Active Roster`: (Non-Dynamic AddressBook Semantics)

1. If there is an existing active roster, log an error and throw an illegal state exception.
   - This behavior will change in the future when we have fully dynamic address book.
2. Set the active roster.
3. Compute the share counts per node for the active roster.
4. If `keyActiveRoster` is true and there is no key material for the active roster, generate the key material.
   1. Set `createNewLedgerId` to true
   2. Create a random share for the node.
   3. Send a `TssMessageTransaction` to the network generated from the random share targeting the active roster.

`On Candidate Roster`:

1. If there is an existing candidate roster.
   1. If the roster hash of the new candidate roster is the same as the existing candidate roster, do nothing and
      return.
   2. Otherwise, replace the candidate roster with the new candidate roster and clear out the old candidate roster
      data.
2. Compute the share counts per node for the candidate roster.
3. If the active roster does not have TSS key material and `keyActiveRoster` is not true, generate key material for a
   new ledger id.
   1. Set `createNewLedgerId` to true
   2. Create a random share for the node.
   3. Send a `TssMessageTransaction` to the network generated from the random share targeting the candidate roster.
4. If the active roster has TSS key material,
   1. If the candidate roster has enough votes for adoption, add the roster hash to `votingClosed` and send the
      candidate roster to the platform for adoption.
   2. If the candidate roster has key material from this node and all of its shares, do nothing and return.
   3. Otherwise generate any missing key material for the candidate roster from this node's shares and submit a
      `TssMessageTransaction` for each share.

`On TssMessageTransaction`:

- NOTE: The TssMessageTransactions must be provided in consensus order for the target rosters in the transactions.

1. If a TSS Message already exists for the share index of the origin roster for the target roster, do nothing.
2. Add it to the list of `TssMessageTransactions` for the target roster.
3. If the target roster hash is not in the `votingClosed` set and does not have a `TssVoteTransaction` from this node:
   1. Validate the TSS Message in the `TssMessageTransaction`.
   2. Update the sequence number bit in the vote vector with the results of validation for the TssMessageTransaction.
4. Let `tssMessageThresholdMet` be true under the following conditions and false otherwise:
   - if `createNewLedgerId` is false and there are a source roster's threshold number of valid TSS Messages indicated
     as valid in the vote vector
   - if `createNewLedgerId` is true and there are enough valid TssMessages from nodes to account for >= 1/2
     consensus weight.
5. If the public keys for the target roster have not been computed and `tssMessageThresholdMet` is true:
   1. Compute the public keys and save it in the public keys map for the target roster.
   2. Aggregate the public key to retrieve the ledger id and save it in the ledger id map.
   3. If this node is in the target roster with the same public `tssEncryptionKey`, recover the private keys and
      save it in the map for the target roster.
      - NOTE: If the node is in the roster, but the `tssEncryptionKey` is different, a restart may be required to
        pickup the correct encryption key from disk.
   4. If the target roster hash is not in the `votingClosed` set and there is no `TssVoteTransaction` from this node:
      1. Sign the ledger id with the RSA signing key of this node.
      2. Construct a `TssVoteTransaction` with the ledger id and the vote vector and submit it to the platform.

`On TssVoteTransaction`:

1. If voting is closed for the target roster or the vote is a second vote from the originating node, do nothing.
2. Add the `TssVoteTransaction` to the list for the target roster.
3. If the voting threshold is met by at least 1/3 consensus weight voting yes:
   1. add the target roster hash to the` `votingClosed` set.
   2. Non-Dynamic Address Book Semantics
      1. if `keyActiveRoster` is false, do nothing here, rely on the startup logic to rotate the candidate roster to
         the active roster.
      2. if `keyActiveRoster` is true, adopt the ledger id in the state.
         - Since this computation is not on the transaction handling thread, adoption of the ledger id must be
           scheduled for the `TssStateManager` to handle on the next round.
   3. **(NOT IMPLEMENTED in this proposal)**: Full Dynamic Address Book Semantics
      1. Rotate the candidate roster to the active roster and schedule the roster for adoption with the platform a
         safe number of rounds into the future.

#### Transaction Resubmission

If a stale event contains a `TssMessageTransaction` or `TssVoteTransaction`, the node will need to resubmit the
transaction to the network.

### Configuration

The following are new configuration:

1. `tss.maxSharesPerNode` - The maximum number of shares that can be assigned to a node. The default value is 3.
2. `tss.keyActiveRoster` - A test-only configuration set to true to key the active roster with TSS key material. The
   default value is false.

### Metrics

The following metrics will be added to the platform:

1. The consensus time when a candidate roster is set.
2. The number of TSS Messages collected for a candidate roster.
3. The number of votes collected for a candidate roster.
4. The time it takes to compute shares from the key material.

### Performance

The `TSS State Manager` execution on the transaction handling thread routes TSS
related system transactions to the relevant components and updates the TSS
related data structures in the state. This routing should be a negligible
impact provided the receiving components receive the messages and handle them
on their own thread(s). The state updates should be simple "crud" operations,
to add, update, and delete from data structures. All other computation on the
transaction handling thread should be basic bookkeeping calculations.

---

## Test Plan

### Unit Tests

Apart from the obvious unit testing of methods and classes, the following scenarios should be unit tested:

- Failure Scenarios
  - Bad TssMessages
  - Failure to reach threshold number of votes
- HAPI Tests
  - A down node during pre-genesis needing to get the key material from post-genesis node.
  - Down nodes during bootstrap and re-keying
  - Multi-release migrations

### Integration Tests

Additional Integration Tests:

- TSS bootstrap for New Networks
- TSS bootstrap for Existing Networks
  - Multiple software upgrades to check transfer of the ledger id.
- Network State Transplant

### Performance Tests

The following needs performance profiling:

1. How long does it take to generate the key material for a new candidate roster as a function of roster size?
2. How much space is being used in the state for all TSS related data structures?

---

## Implementation and Delivery Plan

### Implementation

1. The protobufs for the new system transactions and state data structures, adding to block stream protobuf.
2. The TSS Message Creator and TSS Message Validator Components (pre-wiring), dependent on (1)
3. The TSS State Manager Component (wired to 2), dependent on (1,2), disabled by feature flag
4. The new startup logic, dependent on (3), behavior determined by feature flag.
5. Any Services Implementation, dependent on (4)
6. HAPI Tests, dependent on (5)
7. Integration Tests, dependent on (5)

### Delivery

1. The TSS-Roster proposal must be delivered in total.
2. The release containing the `tssEncryptionKey` set in the Roster may be delivered with (1).
3. The release containing the TSS Bootstrap Process must be delivered after (2).

---
