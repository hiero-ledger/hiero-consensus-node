# HIP-23 (automatic token associations)

This feature adds a new field `maxAutoAssociations` to the account entity, and a
side effect to any transaction that sends units/NFTs of a not-yet-associated token
to an account with `maxAutoAssociations > 0`.

It also adds a `TransactionRecord` field named `automatic_token_associations`.
This field lists all token associations that were created as "side effects" of
any transaction that is not an explicit `TokenAssociate`. (Since an existing
`TokenCreate` _already_ auto-associates the new token treasury, and any fee
collectors for fractional or self-denominated fixed fees, this feature also
"retrofits" the record of a `TokenCreate` with the `automatic_token_associations`
field.)

:warning:&nbsp; Notice that right now, _only_ a `CryptoTransfer` or `TokenCreate`
can have auto-associations as a side effect. But after deployment of HTS precompiles,
a `ContractCreate` or `ContractCall` could also have auto-associations as side effects.

## Scope

This feature affects,
- **HAPI**: `CryptoCreate`, `CryptoUpdate`, `CryptoGetInfo`
- **State**: `MerkleAccountState`
- **Records**: `CryptoTransfer`, `TokenCreate`
- **`handleTransaction`**: `CryptoCreate`, `CryptoUpdate`, `CryptoTransfer`

:small_blue_diamond:&nbsp;Since this feature affects token transfers, we should
consider possible intersections with custom fees, and ensure that our tests give
equal attention to transfers of both fungible token units _and_ NFTs.

:currency_exchange:&nbsp;Since this feature adds side effects to balance changes
in a `CryptoTransfer`, and multi-party `CryptoTransfer`s must commit or rollback
atomically, we should validate that HIP-23 side effects respect these semantics
across a variety of failure modes.

:paperclips:&nbsp;Since this feature has no rebuilt data structures, it has
no special reconnect testing requirements.

So the high-level scope includes:
1. Migration tests.
2. Positive functional tests, especially with multiple accounts being
auto-associated, and accounts receiving multiple auto-associations.
3. Negative functional tests, especially of multi-party `CryptoTransfer`s
whose HIP-23 side effects occur before a later problem with the flow.
4. Record validation for all these positive and negative tests.
5. State validation for all these positive and negative tests.
6. Testing any possible interplay with custom fees.

## Methodology

We now identify what type of tests (and test framework enhancements) will
be needed to cover the above scope.

:cactus:&nbsp;Careful unit testing should suffice for **migration** tests, as
the change to state is very small---a single field added to the
`MerkleAccountState` leaf.

:white_check_mark:&nbsp;**Positive functional** testing will require EET specs
that perform all variants of `TokenCreate`s with implied auto-associations, and
all variants of `CryptoTransfer`s that trigger one or more auto-associations for
one or more accounts.  We should also verify that `CryptoUpdate`s that increase
auto-associations have the expected effect.

:x:&nbsp;**Negative functional** testing will require EET specs that perform all
variants of `CryptoTransfer`s in which auto-associations are attempted, but do
not succeed; and all variants of multi-party `CryptoTransfer`s in which
auto-associations succeed, but must be rolled back due to a later failure in the
`CryptoTransfer` flow. We should also verify that `CryptoUpdate`s that decrease
auto-association slots have the expected effect. It should not be possible to
decrease the number of auto-association slots below the number currently used.

:fountain_pen:&nbsp;**Record validation** will require new EET assertions as
qualifiers on the [`TransactionRecordAsserts` class](https://github.com/hashgraph/hedera-services/blob/master/test-clients/src/main/java/com/hedera/services/bdd/spec/assertions/TransactionRecordAsserts.java#L43). Given these assertions,
the above functional tests can be easily enhanced to validate their records.

:sparkle:&nbsp;**State validation** will require a new assert built-in for the
EET `HapiGetAccountInfo` query, to assert on auto-association state. Given this,
the above functional tests can be easily enhanced to validate state is
changed (or unchanged) as appropriate.

:receipt:&nbsp;**Custom fee interplay** should provide an EET that dissociates
a custom fee collector from its denominating token; and then triggers a custom
fee payment to that collector. If the collector has open auto-association slots,
the custom fee payment should go through (by using a slot).

## Deliverables

Some deliverables above depend on others (e.g., a complete positive functional
EET needs helpers to validate record and state changes). Next we list the
deliverables in the order they should be implemented.

Note the prepatory EET framework items, which make it easier for the functional
tests to validate both records and state changes via queries.

### :fountain_pen:&nbsp;Record validation

- [x] _(EET framework)_ Support for the `automatic_token_associations` field in `TransactionRecordAsserts`.

### :ice_cube:&nbsp;State validation

- [x] _(EET framework)_ `GetAccountInfo` asserts for max and in-use automatic association fields.
- [x] _(EET framework)_ Add account snapshot and change-vs-snapshot asserts for token associations.

### :cactus:&nbsp;Migration

- [x] _(Unit)_ A `MerkleAccountState` now serializes its auto-associations metadata.
- [x] _(Unit)_ A `MerkleAccountState` can be deserialized from a prior-version state.
- [x] _(Unit)_ A `MerkleAccountState` can be deserialized from a current-version state.

### :white_check_mark:&nbsp;Positive functional

- [x] _(EET)_ A `TokenCreate` record includes the treasury auto-association.
- [x] _(EET)_ A `TokenCreate` record includes all fractional fee collector auto-associations.
- [x] _(EET)_ A `TokenCreate` record includes all self-denominated fee collector auto-associations.
- [x] _(EET)_ An account with open auto-association slots can receive units of an unassociated fungible token.
- [x] _(EET)_ An account with open auto-association slot can receive an NFT of an unassociated unique token.
- [x] _(EET)_ An account with no open slots can be updated with more auto-association slots to receve more units/NFTs of unassociated tokens.
- [x] _(EET)_ An account with no open slots can manually dissociate from existing auto-associated tokens to free up more slots.

### :x:&nbsp;Negative functional

- [x] _(EET)_ A failed `TokenCreate` performs and records no auto-associations.
- [x] _(EET)_ A `CryptoCreate` cannot allocate more auto-association slots than the max-per-account limit.
- [x] _(EET)_ A `CryptoUpdate` cannot allocate more auto-association slots than the max-per-account limit.
- [x] _(EET)_ A `CryptoUpdate` cannot renounce more auto-association slots than it has already used.
- [x] _(EET)_ A `CryptoTransfer` cannot create auto-associations without open slots.
- [x] _(EET)_ A multi-party `CryptoTransfer` rolls back all side effects if it fails after auto-associating a with frozen-by-default token.
- [x] _(EET)_ A multi-party `CryptoTransfer` rolls back all side effects if it fails after auto-associating a with KYC token.
- [x] _(EET)_ A multi-party `CryptoTransfer` rolls back all side effects if it fails due to a problem unrelated to auto-association.

### :receipt:&nbsp;Custom fee interplays

- [x] _(EET)_ A royalty fee collector with free auto-association slots can capture exchanged value from unassociated tokens.
- [x] _(EET)_ A royalty fee collector with no auto-association slots cannot capture exchanged value from unassociated tokens.
- [x] _(EET)_ A manually dissociated fixed fee collector with auto-association slots can use auto-association to still receive fees.
- [x] _(EET)_ A manually dissociated fractional fee collector with auto-association slots can use auto-association to still receive fees.
