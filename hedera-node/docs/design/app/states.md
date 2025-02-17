# State

In Hedera, data that is stored "in state" is identical across all nodes in the cluster. The state is used to compute a
total state hash that is gossiped between all nodes. If two nodes have different state hashes, they are said to have
"Invalid State Signatures", or an ISS. The state is also used for "reconnect", where a node may have different
state from other nodes (maybe it was down for a while, or is new to join the network) and the system can synchronize
state between nodes.

The state of the system is, ultimately, stored in a Merkle tree using an API defined by the hashgraph platform. However,
this implementation reality is not a detail that any of the service implementations need to be aware of! Instead, all
state stored in the system can ultimately be broken down into simple singleton or key/value data structures. Indeed,
the hashgraph platform makes this easy with an API for in-memory k/v storage (MerkleMap) and on-disk k/v storage
(VirtualMap).

The `com.hedera.node.app.spi.state` package contains APIs for a service module to interact with state. It was our
objective to eliminate the need for a service module to interact directly with the merkle tree. This was done for four
reasons. First, access to the full merkle tree allows a service to "dig around" in the tree and learn about other state
and possibly depend on other state, outside its own *namespace*. For example, it would be awful for service "A" to find
and depend upon state of service "B" by going through the merkle tree itself. The proper way to do this is to use the
public API available on service "B". The second reason we try to abstract the details of the underlying merkle
implementation is to give us a greater degree of flexibility for re-entrant cases like when the
`hedera-smart-contract-service` uses a system contract to make a call to another service like `hedera-token-service`.
Third, the merkle tree APIs are complex, and we wanted to localize that complexity in one package, and let the many
service modules deal with simple POJO concepts and APIs, to decrease the likelihood of bugs and increase the
testability of service modules.

The final and strongest reason for introducing the classes in the `state` package is that it solves several of the
more challenging problems in the services code base: deciding which copy of the merkle tree state to use, buffering
all modifications to the merkle tree, and keeping track of all state read from the merkle tree. Let's go through each
of these different core use cases and describe how the classes in `state` help with them.

## Merkle Trees

All persistent state is stored in a "merkle tree". A merkle tree is simply a tree structure used to reduce the amount
of work needed to produce a "root hash" of all state in the tree (the tree caches the hashes of each node in the tree,
so when a single leaf changes, we only have to compute the new hash of the leaf and the hash of each parent node in
the tree to compute the new root hash). Performance can be improved by recomputing the root hash for multiple
modifications: that is, re-hashing the tree after every modification is slower than re-hashing every N modifications,
where N is a reasonably large value. We have found that when N is 10,000, it is faster-per-modification than when N
is 1.

We also need to periodically compare "root hashes" of the state with all nodes in the network, to make sure that
all nodes have matching state. Divergent state is a **very bad thing**, because it could mean that in the database
of one machine you have an hbar balance of X, while on another machine you have an hbar balance of Y! Differences in
the "state signature" of different machines is known as an ISS (Invalid State Signature).

The hashgraph algorithm works on `Event`s, sorting them and bundling them together into `Round`s. Each `Event` has
zero or more `PlatformTransaction`s. As each `Round` is produced, it is sent to the Hedera application to be handled.
On `Round` boundaries, the platform makes a "fast copy" of the merkle tree. A "fast copy" allows the application to
continue processing the next `Round`, while the `Round` that was just completed is hashed, signed, and gossiped with
all other nodes to compare signatures. Since hashing, signing, and gossiping take time, this is done asynchronously.
Most of the tree is treated as copy-on-write for a fast-copy, but some parts of the tree are deep-copied.

At any given time, we may have a few 10's of "fast copies" in memory. The most recent is the "working" or "mutable"
state. This is the copy of the tree used by the handle-transaction thread to which all modifications are made.
All other copies are immutable, and will throw exceptions if you attempt to modify them (there is one
modification to the tree permitted after the fast copy is made, and that is to apply the hashes to the nodes in
the tree during the hash phase).

The next-most recent version of the tree is the "latest immutable state". This is the state used for answering queries
made by customers of the consensus nodes. It is also the state used in `pre-handle` (more on this later) for preparing
asynchronously, and ahead of time, transactions for processing.

Another older state known as the "latest signed state" is the most recent state for which the consensus node has
gathered enough signatures to prove to a third party that the state is "final" and correct. This state is used when
"reconnecting" with another node, and is sent to the node that needs a recent copy of the state.

There may exist other states in memory between the "latest immutable state" and "latest signed state". These are states
for which the node is actively collecting signatures. Under rare circumstances there may not be a "latest signed state".
This can happen after a data migration that changes the state hash, or if the state signing process experiences a
breakdown (usually only possible due to bugs or if there are major network disruptions). There may occasionally be
states held for a very long time. This can happen if the application decides to hold a state, or if writing the state
to disk takes a long time, or if a reconnect takes a long time.

While the "merkle tree" is a complex data structure, the service implementations actually don't need to know
or care about it at all. The merkle tree is an implementation detail of the application, not of the services. If each
service had to know about all these different states and which state it should use for which code path, the code would
become quite complex and prone to mistakes! Instead, we hold the Hedera application responsible for understanding this
complexity. Each service needs only a simple key-value store for storing data. So we created the `ReadableKVState` and
`WritableKVState` classes as simple key-value store APIs which services can work with, and let the Hedera application
implement these interfaces over the right version of the merkle tree for the right calls. From the service's
perspective, it doesn't know anything about any of these `Round`s or `Event`s or `PlatformTransaction`s or fast copies
or mutable and immutable states. It is simply given, by the application, the `ReadableKVStates` to use for reading or the
`WritableKVStates` for writing.

## States

There are two "state" interfaces for k/v maps: `ReadableKVState` and `WritableKVState`. `WritableKVState` extends from
`ReadableKVState`, so a `WritableKVState` is read/write. The application module implements these interfaces on top of
`MerkleMap`s or `VirtualMap`s, depending on whether the application's `Schema` declares the state to be in-memory
or on-disk (more on this later). The service module only declares whether the state should be in-memory or on-disk,
it does not ever get exposed to either the `MerkleMap` or `VirtualMap` directly.

Each state is scoped to a particular service implementation. They are never shared across service implementations.
In fact, the state of a service **MUST NOT** be exposed as public API by any service API. The state of a service
is strictly an implementation detail of the service.

From the `ReadableKVState` a service implementation can:
- `get` values from the k/v store
- check whether a given key is contained by the state
- get an iterator over all keys in the state

This last capability, iterating over all keys in the state, is only supported for in-memory `ReadableKVState`s, and
should generally be avoided. It should be removed in a future release. As the number of items in the map increases,
the time it takes to iterate also increases. While we require this functionality for now for migration purposes,
in a future revision we will eliminate this requirement since it does not scale well to billions of records.

From the `WritableKVState` a service implementation can perform all tasks on a `ReadableKVState`, plus:
- `put` a new value
- `remove` a value
- get keys of all modified values with `modifiedKeys`

The `hedera-app-spi` module also defines a set of useful base classes for implementations of `ReadableKVState`
and `WritableKVState`, and several useful concrete implementations. The `ReadableKVStateBase` keeps track of all keys
that have been read and make them available through an API. The `WritableKVStateBase` buffers all modifications until
the `commit` method is called. Both of these implementations act as a near cache for looked up values. Since many (if
not most) values are stored on disk with a `VirtualMap`, performance is positively impacted by caching looked up values
locally in the state.

Each service implementation has one or more states associated with it. For example, the token service may have several
k/v maps, such as "ACCOUNTS" and "TOKENS" and "NFTS". The `ReadableStates` and `WritableKVStates` objects act as a
map from _state key_ to `ReadableKVState` or `WritableKVState`. The state key is simply a string, namespaced to the
service module (so two service implementations could use the same state key and not conflict).

### States used with Queries

The HAPI supports a number of queries. Queries are always answered either from **the latest immutable state** or the
**latest signed state** (if a state proof is requested). Query logic only needs a read-only view of the state, and so
queries use `ReadableKVState`. It is not important to keep track of the different keys queries use. The application
module reuses instances of states as appropriate to cut down on object allocation rates.

### States used during Ingestion

When clients send a transaction to a node, the node must perform pre-check logic before sending the transaction
to the hashgraph platform for consensus. The pre-check logic needs access to **the latest immutable state**. It never
performs any modification to the state. The [Ingest Workflow](workflows.md#ingest-workflow) needs access to key
information on the transaction payer, and uses a `ReadableKVState` to get this information, by delegating to the
token service.

### States used during Pre-Handle

Before a transaction comes to consensus, the platform gives the application an opportunity to do some pre-work in
the background. This pre-transaction work is always done using **the latest immutable state** at the time the pre-work
began. Doing pre-work is perilous, because if care is not taken, it can lead to an ISS! Since this possibility motivates
some additional complexity in the design, it is worth a brief discussion on how an ISS here can happen.

Suppose I have two nodes in the network, Carol and Bob. Each is receiving a steady stream of transactions and gossiping
between themselves and all other nodes in the network. At some point, a `Round` of transactions comes to consensus and
each node is able to process those transactions in order, updating their state accordingly. While that `Round`'s
transactions are being processed on the _handle transaction_ thread, many other threads in the background are doing
pre-handle work on transactions which have not yet come to consensus.

Suppose I have some account A with a balance of 10 hbars. In the current `Round` being handled by the _handle
transaction_ thread there is a transaction which will change the balance to 100 hbars. Suppose a transaction in
pre-handle would modify the balance of A by subtracting 50 hbars. Due to the racy nature of the _handle transaction_
thread and the multiple pre-transaction worker threads, it may be that on Carol the **latest signed state** is different
from that on Bob. Perhaps the _handle transaction_ thread has already adjusted the balance to 100hbars for account A and
completed that round by the time Carol sees this new transaction that wants to subtract 50 hbars. When Carol sees this,
she concludes that subtracting 50 hbar is a perfectly fine thing to do (since 100 - 50 > 0), and marks the transaction
as good. It may be that Bob's **latest signed state** still shows an account balance of 10 for A, because it hasn't
finished running the transaction that would change it to 100. So when Bob pre-handles the transaction to decrement by
50 hbars, he may reject the transaction because 10 - 50 < 0! Carol and Bob thus have divergent state. This would be
very bad. We could give up on concurrency entirely, but this would be unfortunate for performance reasons.

Instead, we can see that the underlying problem is that during pre-handle a node may see some state S0, which may or
may not get modified in a racy way with the _handle transaction_ thread to become S1. So what we need to do is
remember that in pre-handle we accessed the state S0, and when we get to handling that transaction on the
_handle transaction_ thread (perhaps some time later), we need to verify that the value associated with that state
is still S0. If it has become S1, then we need to throw away whatever we computed in pre-handle, and recompute it
again, this time with the **current working state**.

Therefore, when the [Pre-handle Workflow](workflows.md#pre-handle-workflow) processes a transaction, it **must** use a
`ReadableKVState` that tracks all state that was accessed, and remember the set of state that was accessed. When the
transaction is handled on the _handle transaction_ thread, we will check whether any state accessed during pre-handle
has been modified since it was accessed. If so, we throw away any result from pre-handle and run again, this time
serially on the _handle transaction_ thread with the **current working state**. If no state used during pre-handle
has been modified, then we can trust the pre-work we did and complete the transaction.

### States used during Transaction Handling

When a state is used during the [Transaction Handling Workflow](workflows.md#handling-transactions), it is always
a `WritableKVState` based on the **current working state**. The only workflow that supports writable state is this one.
After handling a transaction, the state is committed to the merkle tree.

## Serialization & Deserialization

Each service instance is intended to use simple business objects as their keys and values in k/v states. They no longer
need to extend any merkle data structure types. However, one question that must be solved, is how to perform
serialization on these POJOs. The hashgraph platform will need to serialize them for hashing, reconnect, state-saving,
state-loading, and saving and loading to/from the MerkleDB.

The `Serdes` interface supplies the methods required for serializing POJOs. A special _protobuf_ library called
**PBJ**, developed specifically for Hedera, provides implementations of this interface, making the serialization and
deserialization of these POJOs trivial for the service modules. We use protobuf serialization for **all** serialization
needs for service business objects (in fact, our business objects are defined in protobuf schema and generated by
PBJ, along with unit tests and serialization and deserialization logic in multiple wire formats).

## Schema Registry

Each service instance must define the states that it supports, across each release of the service. This is done by
using `Schema`s, and the `SchemaRegistry`. When a `Service` instance is created, it is given a `SchemaRegistry`
instance, which has been scoped to just this service module. Through this interface, the service is able to `register`
multiple `Schema`s. Our design for `Schema`s is based on the concepts used with (Flyway)[https://flywaydb.org/].

Each `Schema` defines a version number (aligned with the application version numbering), the set of states that are
created within that schema, a migration method for migrating from any previous `Schema` to this one, and the set
of states that should be dropped as part of this `Schema`.

The service module must register **every `Schema` that it has ever used**. During genesis (initial bootstrapping of
a new consensus node), every single `Schema` is processed, with each latter `Schema` building up the former until we
get to the correct final configuration of states. During upgrade, only those `Schema`s that have not yet been executed
will be executed.

The following code is an example of a `Schema` which defines a single k/v state, and pre-populates it with some data.

```java
public class SchemaV1 extends Schema {
    public SchemaV1() {
       super(new BasicSoftwareVersion(1));
    }

    @Override
    public Set<StateDefinition> statesToCreate() {
       return Set.of(
               new StateDefinition(
                       "ACCOUNTS",
                       new AccountIDSerdes(),
                       new AccountSerdes(),
                       10_000_000,
                       false));
    }

    @Override
    public abstract void migrate(
            @NonNull ReadableStates previousStates,
            @NonNull WritableStates newStates) {

       final var accounts = newStates.get("ACCOUNTS");
       accounts.put(new AccountID("0.0.1"), new Account(...));
       accounts.put(new AccountID("0.0.2"), new Account(...));
       accounts.put(new AccountID("0.0.3"), new Account(...));
    }
}
```

This next example shows what a second schema might look like, which moves the map to disk, and adds some more accounts.

```java
public class SchemaV2 extends Schema {
    public SchemaV2() {
       super(new BasicSoftwareVersion(2));
    }

    @Override
    public Set<StateDefinition> statesToCreate() {
       return Set.of(
               new StateDefinition(
                       "ACCOUNTS",
                       new AccountIDSerdes(),
                       new AccountSerdes(),
                       10_000_000_000,
                       true));
    }

    @Override
    public abstract void migrate(
            @NonNull ReadableStates previousStates,
            @NonNull WritableStates newStates) {

        final var oldAccounts = previousStates.get("ACCOUNTS");
        final var accounts = newStates.get("ACCOUNTS");
        for (final var accountID : oldAccounts.keys().toIterable()) {
           accounts.put(accountID, oldAccounts.get(accountID));
        }

        accounts.put(new AccountID("0.0.800"), new Account(...));
        accounts.put(new AccountID("0.0.801"), new Account(...));
        accounts.put(new AccountID("0.0.802"), new Account(...));
    }
}
```

These schemas are then both registered with the `SchemaRegistry` when the service is created:

```java
public class MyService implements Service {
   public MyService(@NonNull final SchemaRegistry registry) {
       register.register(new SchemaV1());
       register.register(new SchemaV2());
   }
}
```

The application is responsible for calling each needed schema in the correct order based on the knowledge the
application has of the version of state it loaded from disk, or if it is a genesis condition where no state was
available.

**NEXT: [Throttles](throttles.md)**
