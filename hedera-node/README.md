# Hedera Services Node

Implements the Hedera cryptocurrency, consensus, smart contract, and file
services on the Platform.

## Overview

Each node serves a gRPC API defined by the _*.proto_ files in the
[`hapi-proto`](../hapi/hedera-protobuf-java-api/src/main/proto). This
API consists of two types of operations:

1. **Transactions** - requests to mutate the state of the network; for example,
   to transfer cryptocurrency between two accounts.
2. **Queries** - requests to get information on the current state of the network; for
   example, to get the contents of a file.

### Permissions

The network state consists of entities---accounts, topics, contracts, and files---that
are controlled by zero or more Ed25519/ECDSA(secp256k1) keypairs arranged in a hierarchy called
a **Hedera key**. (An entity with an empty key is _immutable_.) Mutable entities
can only be changed by transactions that are signed by the private keys of a
sufficient subset of the Ed25519/ECDSA(secp256k1) keypairs in their Hedera key.

### Fees

For all transactions and most queries, there is a **fee** that
must be paid in the network cryptocurrency denomination hbar (ℏ). In the
case of a transaction, the network charges the fee to the **payer account**
originating the transaction. In the case of a non-free query, the request
must include a `CryptoTransfer` transaction in its header which
compensates the receiving node for answering the query.

## Deprecations

See the [_deprecations.md_](deprecations.md) for how to understand the use of
deprecated protobuf elements in the Java code.
