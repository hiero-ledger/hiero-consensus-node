# TSS Encryption Key Automation

---

## Summary

In order to avoid burdening the Node Operators with maintenance of another encryption key, the life-cycle of the
`tssEncryptionKey` will be automated.

|      Metadata      |   Entities    |
|--------------------|---------------|
| Designers          | Edward        |
| Functional Impacts | Services      |
| Related Proposals  | TSS-Ledger-ID |
| HIPS               | None          |

---

## Purpose and Context

For more background and theory, please read the `TSS-Ledger-ID` and `TSS-Library` proposals.

Each node must maintain a long-term BLS encryption key, called the `tssEncryptionKey`, for receiving its private TSS
shares used in signing. The public portion of this key must be available to every consensus node in the network
that is participating in TSS signing. A node's public `tssEncryptionKey` is used to encrypt the private
`shares of shares` intended for that node. The receiving node uses their private `tssEncryptionKey` to decrypt its
`shares of shares`. Aggregating the decrypted `shares of shares` allows the node to recover its private
share keys.

While the public key must be known by all nodes, the private `tssEncryptionKey` must not be known by any other node.
The security of the ledger id and the ledger's private signing key is dependent on the private `tssEncryptionKey`
remaining private to each node. If a single party were to obtain enough of the private `tssEncryptionKeys`, they
would be able to recover the private shares of multiple nodes and aggregate a threshold number of private shares to
recover the ledger's private signing key.

The reason that the `tssEncryptionKey` must be maintained long-term is that the public key must be known by other
nodes before they can generate private shares for the node. If the `tssEncryptionKey` were to change, the node would
not be able to participate in TSS signing or re-keying until the next candidate roster is keyed and adopted.

To minimize the number of node operator touch points, the life-cycle of the `tssEncryptionKey` will be automated.

### Dependencies, Interactions, and Implications

This proposal should be delivered with or before the delivery of `TSS-Ledger-ID`, which will attempt to key a candidate
roster nightly. The `TSS-Ledger-ID` capability cannot be turned on until enough nodes know the public
`tssEncryptionKey` of enough of the other nodes.

### Requirements

1. The public `tssEncryptionKey` of a node must be recorded in the state and publicly known by other nodes.
2. The private `tssEncryptionKey` of a node must be kept private to that node.
3. The lifecycle of the `tssEncryptionKey` should be automated.

### Design Decisions

* The private `tssEncryptionKey` will be stored in the same directory as the node's private gossip signing key. The
  file format will be `Armored ASCII`, a `Base64` encoding of the raw bytes of the key bookended by leading and
  trailing markers. The filename will be `t-private-<alias>.tss` where `<alias>` is the word `node` followed by the
  node's id + 1.
* The public `tssEncryptionKey` will be stored in the `TssBaseService` state.
* A new `TssEncryptionKeyTransaction` will be gossiped, come to consensus, and handled by the `TssBaseService` to
  update the public `tssEncryptionKey` in the state.
  * This new transaction is system generated only and not user generated. It should be rejected at the HAPI gateway
    for user generated transactions.

#### Alternatives Considered

The alternative to the automated lifecycle is to have the node operators manually generate the `tssEncryptionKey`
before the node starts and provide the public key to the network through a transaction signed by the node admin key.

Requiring human intervention for this key increases friction that can easily be avoided. This saves all the testing
labor related to testing human workflows.

---

## Changes

The following changes to the system are required.

### Architecture and/or Components

The `TssBaseService` already exists with its own state.

* This proposal adds a new virtual map to the `TssBaseService` state with type structure equivalent to
  `Map<NodeId,TssEncryptionKeyTransaction>` for storing the public key of each node.
* This proposal modifies the normal execution of the `TssBaseService` when its current public `tssEncryptionKey` is not
  in the state.

Cryptography Changes:

* The cryptography system adds a dependency on the TSS-Library to generate the `tssEncryptionKey` in the form of a
  `(BlsPrivateKey, BlsPublicKey)` pair which can be encapsulated as `BlsKeyPair`.
* The `KeysAndCerts` record is extended with `BlsPrivateKey privateTssEncryptionKey` and
  `BlsPublicKey publicTssEncryptionKey`.

### Core Behaviors

There are two new behaviors that will be added to the system.

1. At `System Startup`, during cryptography loading, the `tssEncryptionKey` will be loaded from disk or generated if it
   does not exist.
2. At the start of `TssBaseService`, the `TssBaseService` will verify that the public `tssEncryptionKey`
   is in the state, and if not, send a transaction to update the public key in the state. The `TssBaseService`
   cannot proceed with normal execution until the public key is in the state and it is able to decrypt private shares.

#### Loading Cryptography

The cryptography library will add a dependency on the `Tss-Library` to generate new public/private `tssEncryptionKeys`.

In the `EnhancedKeystoreLoader`:

1. During the `scan()` phase, the private `tssEncryptionKey` will be loaded from disk if present.
2. During the `generate()` phase,
   1. if the private `tssEncryptionKey` is not present:
      1. Create a new public/private key pair.
      2. Write the private key to disk.
   2. if the private `tssEncryptionKey` is present:
      1. Create the `BlsPublicKey` from the private key.

At the end of loading cryptography, both the `BlsPrivateKey` and `BlsPublicKey` will be in memory and the private
key will be on disk.

#### TssBaseService Initialization

At the initialization of the `TssBaseService` the private `tssEncryptionKey` loaded from disk and matching public key
will be provided. There are two scenarios:

1. The provided `BlsPublicKey` for the loaded `BlsPrivateKey` is already in the state.
   1. This is the happy path and the `TssBaseService` can continue as normal.
2. The state does not have a `BlsPublicKey` for this node or the provided `BlsPublicKey` does not match the
   `BlsPublicKey` in the state for this node:
   1. Once execution is live, a `TssEncryptionKeyTransaction` will need to be sent periodically until the state
      reflects the correct public key. The `TssBaseService` cannot continue execution as normal.

#### TssBaseService Start Of Execution

If there is a public key update needing to happen:

1. Suspend normal execution: The `TssBaseService` will not be able to decrypt its private shares and cannot
   participate in signing or re-keying.
2. Send (and periodically resend) a `TssEncryptionKeyTransaction` until the public key is updated in the state.
3. Normal execution of the `TssBaseService` cannot resume until a new candidate roster has been keyed and adopted
   that provides private shares to this node that can be decrypted using the new `tssEncryptionKey`.

New `TssEncryptionKeyTransaction` handle workflow:

* Upon receipt of a `TssEncryptionKeyTransaction`:
  1. Add the transaction to the `TssBaseService` state for the node sending it
  2. If the public key added belongs to this node, disable any mechanism created for resending the transaction.
  3. Check the conditions for resuming normal execution.

Resuming / Proceeding with Normal Execution:

* Normal execution can resume when private shares can be decrypted from the `TssMessages` belonging to the
  latest `active roster`.

### Public API

#### TssEncryptionKeyTransaction Protobuf

The `TssEncryptionKeyTransaction` will only appear in events created from the node sending the transaction. The RSA
signature on the event from the node's gossip signing key and the creator node identifier on the event are
sufficient to provide authenticity of the transaction.

```protobuf
message TssEncryptionKeyTransaction {
  /**
   * The raw bytes of the public TSS encryption key of the node sending the transaction.
   */
  bytes publicTssEncryptionKey = 1;
}
```

#### TssBaseService New State

The `TssBaseService` state will be extended with a new virtual map of the public `tssEncryptionKey` for each node.

1. The keys of the map are UInt64 for the `nodeId` of the nodes
2. The values of the map are the latest `TssEncryptionKeyTransaction` from each node.

Data Lifecycle:

* When a node id is not present in any of the `active rosters` or a `candidate roster`, the entry for that node id
  should be removed. This cleanup task could be eagerly triggered any time there is a roster state change or lazily
  triggered whenever a candidate roster is set. If this cleanup happens at startup, it must happen through a schema
  in order for the corresponding state changes to be made visible in the output stream. If it happens during
  execution, it must be on the transaction handling thread.

### Configuration

No new configuration is needed.

### Metrics

A health metric should be created to indicate the state the `TssBaseService` is in:

1. State 1: distributing the public `tssEncryptionKey` to the network.
2. State 2: the latest `tssEncryptionKey` is in the state, waiting to receive private shares.
3. State 3: This node's private shares related to the `active roster` have been decrypted and the `TssBaseService` can
   execute normally.

A health metric should be created that indicates the number of active shares available for signing from each node.

* Nodes which are offline or have bad status in the above health metric should not have their shares counted towards
  the available active shares for signing.
* A warning alarm should be generated if the number of active shares drops close to the aggregation threshold
  required to create a ledger signature.
* A critical error alarm should be generated if the number of available shares drops below the aggregation threshold.

### Performance

There are no expected performance impacts from this proposal.

---

## Test Plan

The behavior of this proposal is fundamental to the operation of TSS. It is sufficient that the TSS capability
comes live with existing TSS integration tests once this proposal is deliverable.

---

## Implementation and Delivery Plan

The implementation of this capability is able to proceed now.

Once delivered, it is advisable that Node Operators backup the cryptography directory containing the
`tssEncryptionKey` private key and include the private key in their disaster recovery plan. If this is not done,
then restoring the node from a backup will require the node to generate a new `tssEncryptionKey` and distribute the
public key to the network. This will prevent the node from being able to participate in signing until the next
candidate roster is adopted.

It is possible to manually force a rotation of the `tssEncryptionKey` by deleting the private key from disk and
restarting the node. This will cause the node to generate a new key pair and distribute the public key to the network.
The node will not be able to participate in signing until the public key is adopted and the next candidate roster is
keyed using the new public key. A more robust rotation mechanism that does not interfere with the node's ability to
participate in signing is a future enhancement.
