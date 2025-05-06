# Rebuilt data structures

The ground truth of the Services world state is the Merkle tree
with `ServicesState` at the root. We can think of `handleTransaction()`
as a pure reduction that combines the current world state and a consensus
transaction into a new consensus world state.

```
handleTransaction: (world_state, consensus_transaction) -> new_world_state
```

If this was _literally_ true, then the only stateful objects in the system
would belong to the Merkle type hierarchy. In fact it is an idealization.

To implement `handleTransaction()` efficiently, we must build, and maintain,
non-Merkle objects that "denormalize" the Merkle tree. These denormalized
views of state are used to, for example, classify duplicate transactions;
and can thus change the outcome of handling a transaction.

:information_desk_person:&nbsp;We often call these views
**rebuilt data structures** to emphasize the restart and reconnect phases
in the lifecycle of a Services node must give such structures first-class
consideration.

## Lifecycle consistency

Suppose node `A` has been active for the entire lifetime of a network; while
node `R` hit a network partition, missed several hours of consensus transactions,
and only reconnected recently.

Both will use a non-Merkle data structure to classify duplicate transactions
(specifically, a map from `TransactionID`s to `TxnIdRecentHistory`s). Clearly,
when `R` reconnects, it must be able to **rebuild from its Merkle state** the
exact classification map that `A` had when it was handling the next consensus
transaction given that state.

This is a problem of _consistency_.

:warning:&nbsp;No matter how a node derives its non-Merkle state
views, whether one transaction at a time, or all at once from a state learned
via reconnect or found at restart---all nodes must always see the same views
for the same state.

## Intersecting transaction types

Let `x` be a rebuilt data structure. In general, not every type of HAPI
transaction will change `x` when handled; but suppose transaction type
`f` does change `x` during `handleTransaction()`. Then we say that
transaction type `f` **intersects** the rebuilt data structure `x`.
The **main effect** of `f` is still on the Merkle world state; but this
**side effect** on `x` must be given equal consideration.

To achieve consistency for `x`, we must be able to rebuild all the
side effects of intersecting transactions `f` from their main effects
on the Merkle state.

## List of rebuilt data structures

The following rebuilt data structures exist in Services:
1. The [`existingRels`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/accounts/BackingTokenRels.java#L46) set of non-dissociated token-account relationships.
2. The [`existingAccounts`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/accounts/BackingAccounts.java#L35) set of non-removed accounts.
3. The [`extantSchedules`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/schedule/HederaScheduleStore.java#L70) map from each extant scheduled transaction to its schedule entity id.
4. The [`knownTreasuries`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L138) map from each token treasury account to the set of tokens which it serves.
5. The [`uniqueTokenAssociations`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ServicesState.java#L462) one-to-many relation from each unique token type to its minted NFTs.
6. The [`uniqueOwnershipAssociations`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ServicesState.java#L466) one-to-many relation from each account to its owned NFTs.
7. The [`uniqueTreasuryOwnershipAssociations`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ServicesState.java#L470) one-to-many relation from each unique token type to its treasury-owned NFTs.
8. The [`txnHistories`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/context/ServicesContext.java#L1599) map from the id of each transaction handled in the last 180s of consensus time to the "recent history" of that id.
9. The [`payerRecordExpiries`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiryManager.java#L74) queue of the pending expirations of the payer records in state.
10. The [`shortLivedEntityExpiries`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiryManager.java#L76) priority queue of the pending expirations of the scheduled transactions in state.

Here we document how the side effects of each structure's intersecting
transactions can be consistently rebuilt from the main effects in state.

### The `existingRels` set

```
Set<Pair<AccountID, TokenID>> existingRels = new HashSet<>();
```

This structure gives a small performance boost when checking if an
`AccountID` and `TokenID` are associated, as it avoids both (1)
converting from gRPC to Merkle types; and (2), acquiring a read lock
on the `tokenAssociations` FCM.

It will be removed as part of the ongoing HTS "layered architecture"
refactor, likely in the 0.17.0 release.

:gear:&nbsp;Any transaction that associates or dissociates tokens will
change the `existingRels` structure. These include the following HAPI
functions: `TokenCreate`, `TokenAssociateToAccount`, and
`TokenDissociateFromAccount`.

Notice that `TokenUpdate` and `TokenDelete` do _not_ change the set of
token associations at this time.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over associations in state and adding each to the set](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/accounts/BackingTokenRels.java#L57).

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `TokenCreate`, all accounts are auto-associated using the
[`HederaTokenStore.associate()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L201). Since this method delegates to the
`TransactionalLedger.create()` method, its side effect on `existingRels`
is guaranteed by the consistency of the [`BackingTokenRels.put()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/accounts/BackingTokenRels.java#L74) itself.
2. For `TokenAssociateToAccount` and `TokenDissociateFromAccount`,
associations change via `TokenRelationship`s persisted via the
[`TypedTokenStore.persistTokenRelationships()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L172). New associations propagate to
`existingRels` via the [`BackingTokenRels.addToExistingRels()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L201) method; and
removed associations propagate via the [`BackingTokenRels.removeFromExistingRels()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L179) method.

### The `existingAccounts` set

```
Set<AccountID> existingAccounts = new HashSet<>();
```

This structure provides a small performance boost when checking if a
given `AccountID` refers to a non-removed account, as it avoids both (1)
converting from gRPC to Merkle types; and (2), acquiring a read lock
on the `accounts` FCM.

It will be removed as the ongoing "layered architecture" refactor extends
to non-token operations, possibly even in the 0.17.0 release.

:gear:&nbsp;Only the `CryptoCreate` HAPI operation can change the
`existingAccounts` structure (since a `CryptoDelete` just marks an account
as deleted, but does not remove it from state). _If_ auto-removal is active,
then the [`RenewalHelper.removeLastClassifiedAccount()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/renewal/RenewalHelper.java#L129) can also
affect `existingAccounts`.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over accounts in state and adding each to the set](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/accounts/BackingAccounts.java#L46).

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `CryptoCreate`, accounts are created via the `TransactionLedger.create()`;
thus the problem reduces to consistency of the
[`BackingAccounts.put()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/accounts/BackingAccounts.java#L58).
2. For auto-removal, changes are propagated from the `RenewalHelper`
via the [`BackingAccounts.remove()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/renewal/RenewalHelper.java#L145).

### The `extantSchedules` map

```
Map<MerkleSchedule, MerkleEntityId> extantSchedules = new HashMap<>();
```

This structure allows a node to efficiently detect
[re-creation of an existing schedule](https://github.com/hashgraph/hedera-services/blob/master/docs/scheduled-transactions/revised-spec.md#duplicate-creations), resolving a `ScheduleCreate` to `IDENTICAL_SCHEDULE_ALREADY_CREATED`
instead of `SUCCESS`.

:gear:&nbsp;The only HAPI operation that can change `extantSchedules`
is `ScheduleCreate`. But this map must be updated whenever a schedule
entity is expired from state, which can happen as a consequence of
handling any transaction type.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over the schedules in state and updating the map with each](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/schedule/HederaScheduleStore.java#L184).

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `ScheduleCreate`, the [`HederaScheduleStore.commitCreation()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/schedule/HederaScheduleStore.java#L152)
maintains consistency between the state FCM and `extantSchedules`.
2. For schedule expiration, again consistency is achieved by
updating state and view together in the [`HederaScheduleStore.expire()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/schedule/HederaScheduleStore.java#L222).

### The `knownTreasuries` map

```
Map<AccountID, Set<TokenID>> knownTreasuries = new HashMap<>();
```

This structure has three uses:
1. It allows the [`CryptoDelete` transition logic](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/txns/crypto/CryptoDeleteTransitionLogic.java#L77) to efficiently
resolve to `ACCOUNT_IS_TREASURY` when the target account is the
treasury of a non-deleted token.
2. It allows the `GetAccountNftInfos` query logic to find all the
NFTs an account owns by [virtue of its role as token treasury](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/views/TreasuryWildcardsUniqTokenView.java#L98).
3. It helps the [`HederaTokenStore.changeOwner()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L342) determine when an
NFT is exiting from or returning to its token treasury.

Note the second and third uses are both suspect, and should be
eliminated.

In the second use, because `knownTreasuries` is not fast-copyable, concurrent
`GetAccountNftInfos` queries could return different answers from the
`FCOneToManyRelation`s in the _same signed state_, if those queries
happen to be "racing" a `TokenUpdate` that affects the target account's
treasury role. (But this can wait until state proofs are enabled.)

In the third use, it is safer to simply retrieve from state the tokens
involved in the NFT ownership change and identify their treasuries directly.

:gear:&nbsp;The `TokenCreate`, `TokenUpdate`, `TokenDelete` HAPI operations
can all change `knownTreasuries`. (Even though a deleted token remains
in state, and a `GetTokenInfo` query will return its final treasury account,
that account is no longer _functionally_ a treasury.)

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over the non-deleted tokens in state and updating the map from each](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L163).
The "non-deleted" qualifier is critical, since the sole _necessary_
use of `knownTreasuries` in guarding `CryptoDelete` logic no longer
applies for deleted tokens.

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `TokenCreate`, the [`HederaTokenStore.commitCreation()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L627)
simultaneously updates the state FCM and `knownTreasuries`.
2. For `TokenUpdate`, the [`HederaTokenStore.update()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L771) ensures
`knownTreasuries` reflects any treasury change.
3. For `TokenDelete`, the [`HederaTokenStore.delete()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L645) removes
the mapping for a deleted token's treasury from `knownTreasuries`.

### The `uniqueTokenAssociations` one-to-many relation

```
FCOneToManyRelation<Integer, Long> uniqueTokenAssociations = new FCOneToManyRelation<>();
```

This structure supports the `TokenGetNftInfos` query logic by providing
efficient iteration through the NFTs minted by a unique token type.

:gear:&nbsp;The `TokenMint`, `TokenBurn`, and `TokenAccountWipe` HAPI operations
can all change `uniqueTokenAssociations`.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over the unique tokens in state and updating the relation for each](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L246).

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `TokenMint`, the [`TypedTokenStore.persistToken()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L304) uses [`TypedTokenStore.persistMinted()`](https://github.com/hashgraph/hedera-services/blob/f58e0220a2be5e7217789c9e6e362bd0b380e196/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L332)
to simultaneously update the `uniqueTokens` FCM and `uniqueTokenAssociations`.
2. For `TokenBurn`, the [`TypedTokenStore.persistToken()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L304) uses [`TypedTokenStore.destroyRemoved()`](https://github.com/hashgraph/hedera-services/blob/f58e0220a2be5e7217789c9e6e362bd0b380e196/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L319)
to simultaneously update the `uniqueTokens` FCM and `uniqueTokenAssociations`.
3. For `TokenAccountWipe`, again the [`TypedTokenStore.persistToken()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L304) uses [`TypedTokenStore.destroyRemoved()`](https://github.com/hashgraph/hedera-services/blob/f58e0220a2be5e7217789c9e6e362bd0b380e196/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L319)
to simultaneously update the `uniqueTokens` FCM and `uniqueTokenAssociations`.

In both cases, the actual mutations to the `uniqueTokenAssociations`
relation are done by the appropriate method in [`UniqTokenViewsManager`](https://github.com/hashgraph/hedera-services/blob/f58e0220a2be5e7217789c9e6e362bd0b380e196/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L35).

### The `uniqueOwnershipAssociations` one-to-many relation

```
FCOneToManyRelation<Integer, Long> uniqueOwnershipAssociations = new FCOneToManyRelation<>();
```

This structure supports the `TokenGetAccountNftInfos` query logic by providing
efficient iteration through the NFTs owned by a specific crypto account.
(Note that this iteration is refined to
include _only_ the NFTs that account owns by virtue of a `CryptoTransfer`; it
may _also_ own NFTs by virtue of serving as treasury for one or more unique tokens.)

:gear:&nbsp;HAPI operations affect the `uniqueOwnershipAssociations` relation
Only `TokenAccountWipe` and `CryptoTransfer` can change this relation.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over the unique tokens in state and updating the relation for non-treasury
owned NFTs](https://github.com/hashgraph/hedera-services/blob/01682-M-AuditRebuiltDataStructures/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L246).

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `TokenAccountWipe`, the [`TypedTokenStore.persistToken()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L304) uses [`TypedTokenStore.destroyRemoved()`](https://github.com/hashgraph/hedera-services/blob/f58e0220a2be5e7217789c9e6e362bd0b380e196/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L319)
to simultaneously update the `uniqueTokens` FCM and `uniqueOwnershipAssociations`; where the
actual mutation is done by [`UniqTokenViewsManager.wipeNotice()`](https://github.com/hashgraph/hedera-services/blob/01682-M-AuditRebuiltDataStructures/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L125).
2. For `CryptoTransfer`, the [`HederaTokenStore.changeOwner()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L356) again delegates to the
`UniqTokenViewsManager` methods; here the `uniqueOwnershipAssociations` relation
only changes on use of the [`UniqTokenViewsManager.exchangeNotice()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L161), since treasury-owned
tokens do not appear in the `uniqueOwnershipAssociations` relation.

:no_entry:&nbsp;There is an atomicity failure with `CryptoTransfer` here.
On the one hand, the `HederaTokenStore.changeOwner()` method **directly** updates the
`uniqueOwnershipAssociations` relation via `UniqTokenViewsManager`.  On the other hand,
it **defers** changes to the `uniqueTokens` FCM until `HederaLedger.commit()` is called.
But if `HederaLedger.dropPendingTokenChanges()` is called before `commit()`, the changes
to `uniqueOwnershipAssociations` will not be reverted along with the FCM changes; and
the view and state will become inconsistent.
(C.f. [#1918](https://github.com/hashgraph/hedera-services/issues/1918).)

### The `uniqueTreasuryOwnershipAssociations` one-to-many relation

```
FCOneToManyRelation<Integer, Long> uniqueTreasuryOwnershipAssociations = new FCOneToManyRelation<>();
```

This structure supports the `TokenGetAccountNftInfos` query logic
by providing efficient iteration through the NFTs
owned by a specific crypto account.

:gear:&nbsp;HAPI operations affect the `uniqueTreasuryOwnershipAssociations` relation
The `CryptoTransfer`, `TokenMint`, and `TokenBurn`
can all change the `uniqueTreasuryOwnershipAssociations`.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure consists of
[iterating over the unique tokens in state and updating the relation for all treasury-owned NFTs](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L228).

:memo:&nbsp;**Maintaining** this structure works as below.
1. For `CryptoTransfer`, the [`HederaTokenStore.changeOwner()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/HederaTokenStore.java#L356) again delegates to the
`UniqTokenViewsManager` methods; here the `uniqueOwnershipAssociations` relation
only changes on use of the [`UniqTokenViewsManager.treasuryReturnNotice()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L201) and
[`UniqTokenViewsManager.treasuryExitNotice()` methods](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/tokens/views/UniqTokenViewsManager.java#L179), since only treasury-owned
tokens appear in the `uniqueTreasuryOwnershipAssociations` relation.
2. For `TokenMint`, the `TypedTokenStore.persistToken()` method uses
[`TypedTokenStore.persistMinted()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L332) to simultaneously update the state FCM and `uniqueTreasuryOwnershipAssociations`.
3. For `TokenBurn`, the `TypedTokenStore.persistToken()` method uses
[`TypedTokenStore.destroyRemoved()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/TypedTokenStore.java#L319) to simultaneously update the state FCM and `uniqueTreasuryOwnershipAssociations`.

:no_entry:&nbsp;Just as above, the `CryptoTransfer` implementation has an
atomicity failure, since there is no mechanism to rollback changes to this
relation when `HederaLedger.rollback()` is called. (C.f. [#1918](https://github.com/hashgraph/hedera-services/issues/1918).)

### The `txnHistories` map and `payerRecordExpiries` queue

```
Map<TransactionID, TxnIdRecentHistory> txnHistories = new ConcurrentHashMap<>();
MonotonicFullQueueExpiries<Long> payerRecordExpiries = new MonotonicFullQueueExpiries<>();
```

These structures support efficient classication of duplicate transactions,
where the "classification window" is the previous 180s of consensus time.

The first structure, the `txnHistories` map, gives the "recent history" for a `TransactionID`,
represented by a `TxnIdRecentHistory` object whose [`TxnIdRecentHistory.currentDuplicityFor()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/records/TxnIdRecentHistory.java#L172)
returns the "duplicity" of the `TransactionID` given a submitting member id.
(The possibilities are `BELIEVED_UNIQUE`, `NODE_DUPLICATE`, and `DUPLICATE`;
where `NODE_DUPLICATE` means the _same member_ submitted duplicates of a
`TransactionID`, suggesting malicious behavior.)

The second structure, the `payerRecordExpiries` queue, has an [`expireNextAt()` method](https://github.com/hashgraph/hedera-services/blob/d4c87420876a7ca63eb877c41a0e950cdafad90c/hedera-node/src/main/java/com/hedera/services/state/expiry/MonotonicFullQueueExpiries.java#L54)
that accepts a consensus time in seconds, and returns the number of the next account
that has a payer record expiring at that time. (A record expires in 180s, so it _also_
"drops out" of the classification window at the moment it expires.) Since each
record includes its originating `TransactionID`, each time a payer record
expires, we can use it to make the corresponding call to the correct
[`TxnIdRecentHistory.forgetExpiredAt()` method](https://github.com/hashgraph/hedera-services/blob/d4c87420876a7ca63eb877c41a0e950cdafad90c/hedera-node/src/main/java/com/hedera/services/records/TxnIdRecentHistory.java#L151), and
thus keep the `txnHistories` up-to-date.

:gear:&nbsp;All HAPI operations affect the `txnHistories` map and `payerRecordExpiries`
queue, since they operate on the top-level `TransactionID` shared by all transaction types.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** these structures relies on the payer
records stored in state in the `accounts` FCM. That is, we [scan the `accounts` FCM](https://github.com/hashgraph/hedera-services/blob/d4c87420876a7ca63eb877c41a0e950cdafad90c/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiryManager.java#L133)
and, for each payer record, both (1) "stage" it in the relevant `txnHistories` entry;
and (2) add its "expiration event" to an unsorted list. After all records have been
processed, we sort these expiration events by consensus time, and use the
`payerRecordExpiries.track()` method to rebuild the `payerRecordExpiries` queue.
Finally, for each entry in the `txnHistories` map, we initialize its duplicate
classification internals by calling [`TxnIdRecentHistory.observeStaged()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/records/TxnIdRecentHistory.java#L115).

:memo:&nbsp;**Maintaining** these structures has two aspects.

First, each time we add a new record to state via [`HederaLedger.commit()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/HederaLedger.java#L197), the
[`TxnAwareRecordsHistorian.saveExpirableTxnRecord()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/records/TxnAwareRecordsHistorian.java#L72) method will consecutively
save the new payer record to state, add its expiration event to the
`payerRecordExpiries` queue, and update the `txnHistories` map---the
first two via a call to [`ExpiringCreations.saveExpiringRecord()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiringCreations.java#L84), and the
third via a call to [`RecordCache.setPostConsensus()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/records/RecordCache.java#L72).

Second, at the beginning of each call to `handleTransaction`, we invoke
[`ExpiryManager.purge()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/legacy/services/state/AwareProcessLogic.java#L94). For the first transaction handled in each
consensus second, this can trigger one or more calls to the
[`MonotonicFullQueueExpiries.expireNextAt()` method](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/MonotonicFullQueueExpiries.java#L54) of `payerRecordExpiries`,
continuing until the head of the queue is an expiration event whose
consensus time is not after the current consensus second. For each
expiration event, we then [consistently update](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiryManager.java#L179) the related records FCQ in the `accounts`
FCM and the `txnHistories` map.

:information_desk_person:&nbsp;Note that here we depend on the "invariance"
of the FCM root hash to the order of `get()` and 'put()' calls made within
a single `handleTransaction()`, since the order that expiration events with
the same consensus time appear in a rebuilt `payerRecordExpiries` may _not_
be the same as the order they appear in a constantly maintained queue.
(C.f. [Platform #3655](https://github.com/swirlds/swirlds-platform/issues/3655).)

## The `shortLivedEntityExpiries` priority queue

```
/* Since the key in Pair<Long, Consumer<EntityId>> is the schedule entity number---and
entity numbers are unique---the downstream comparator below will guarantee a fixed
ordering for ExpiryEvents with the same expiry. The reason for different scheduled entities having
the same expiry is that we round expiration times to a consensus second. */
Comparator<ExpiryEvent<Pair<Long, Consumer<EntityId>>>> PQ_CMP = Comparator
        .comparingLong(ExpiryEvent<Pair<Long, Consumer<EntityId>>>::getExpiry)
        .thenComparingLong(ee -> ee.getId().getKey());

PriorityQueueExpiries<Pair<Long, Consumer<EntityId>>> shortLivedEntityExpiries = new PriorityQueueExpiries<>(PQ_CMP);
```

This structure supports efficient expiration of schedule entities. Unlike
the `payerRecordExpiries` queue, which requires expiration events to be enqueue
with monotonic increasing consensus times, the `shortLivedEntityExpiries` is
backed by a `java.util.PriorityQueue` ordered by the `PQ_CMP` above; so it supports
insertion of schedule entities with expiration times in any order.

:gear:&nbsp;Only `ScheduleCreate` HAPI operations can insert into the `shortLivedEntityExpiries`
priority queue; but just as above with `payerRecordExpiries`, any call to `handleTransaction()`
can cause expiration events to be polled from this structure.

#### Consistency audit

:building_construction:&nbsp;**Rebuilding** this structure relies on the schedule
entities stored in state in the `schedules` FCM. That is, we [scan the `schedules` FCM](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiryManager.java#L148)
and, for each schedule entity, add its "expiration event" to the `shortLivedEntityExpiries`.

:radioactive:&nbsp;The current implementation performs an unnecessary (and pointless)
intermediate step of building a list with the expiration events in sorted order
before it enqueues them.

:memo:&nbsp;**Maintaining** this structures has two aspects.

First, a successful `ScheduleCreate` updates the transaction context with an [`ExpiringEntity` instance](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/txns/schedule/ScheduleCreateTransitionLogic.java#L126)
representing the newly scheduled transaction. The [`HederaLedger.commit()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/ledger/HederaLedger.java#L202) at the end of `handleTransaction()`
then triggers the `ExpiryManager` to [begin tracking](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/ExpiryManager.java#L113) the expiring entity.

Second, at the beginning of each call to `handleTransaction`, we invoke
[`ExpiryManager.purge()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/legacy/services/state/AwareProcessLogic.java#L94). For the first transaction handled in each
consensus second, this can trigger one or more calls to the [`PriorityQueueExpiries.expireNextAt()`](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/state/expiry/PriorityQueueExpiries.java#L55)
method, continuing until the head of the queue is an expiration event whose
consensus time is not after the current consensus second. For each
expiration event, we then immediately remove the corresponding
entry in the `schedules` FCM via the expired entity's [`HederaScheduleStore.expire()` callback](https://github.com/hashgraph/hedera-services/blob/master/hedera-node/src/main/java/com/hedera/services/store/schedule/HederaScheduleStore.java#L222).
(Note that this method _also_ updates the `extantSchedules` rebuilt structure.)
