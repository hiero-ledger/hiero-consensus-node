# HIP-24 (Pause feature on Hedera Token Service)

This feature adds two new transactions `TokenPause` and `TokenUnpause` which toggle the new `Paused` field
of the token entity. Also added is a new `pauseKey` field to the token entity which will be needed to sign the
Pause and Unpause transactions.

A `Paused` token type cannot be part of transactions other than the above mentioned Pause and Unpause transactions.

By default a token when created will be `unpaused` and can be used as expected. This will ensure that the existing
tokens comply with this feature.

## Scope

This feature effects,
- **HAPI**: `TokenCreate`, `TokenUpdate`, `TokenGetInfo`, `TokenPause`, `TokenUnpause`
- **`handleTransaction`**: `TokenCreate`, `TokenUpdate`, `CryptoTransfer`, `TokenPause`, `TokenUnpause`, `TokenMint`,
`TokenWipe`, `TokenBurn`, `TokenAssociate`, `TokenDissociate`, `TokenDelete`, `TokenFeeScheduleUpdate`, `TokenFreeze`,
`TokenUnfreeze`, `TokenGrantKyc`, `TokenRevokeKyc`

As `CryptoTransfers` are involved, we need need to validate that custom-fee semantics follow the restrictions
put in place by a `Paused` token.

So the high-level scope includes:
1. Migration tests.
2. Positive functional tests, which test if a token's pause status can be toggled using the
the new TokenPause and TokenUnpause transactions.
3. Negative functional tests, which test failure scenarios of transactions involving a paused token.
4. State validation for all these positive and negative tests.
5. Testing any possible interplay with custom fees.

## Methodology

Tests needed to cover the above scope.

Careful unit testing should suffice for **migration** tests, as
the change to state is very small---two fields added to the
`MerkleToken` leaf.

:white_check_mark:&nbsp;**Positive functional** testing will require EET specs
that perform all variants of `TokenCreate`s with a `pauseKey` and test if the token's pause status is toggled
appropriately using the new `TokenPause` and `TokenUnpause` transactions.

:x:&nbsp;**Negative functional** testing will require EET specs that perform all
variants of `TokenCreate`s with a `pauseKey` and pause the token so that all transactions involving this token
do not succeed.

:sparkle:&nbsp;**State validation** will require a new assert built-in for the
EET `HapiGetTokenInfo` query, to assert on its pause status. Given this,
the above functional tests can be easily enhanced to validate state is
changed (or unchanged) as appropriate.

:receipt:&nbsp;**Custom fee interplay** should provide an EET that pauses
a custom fee's denominating token; and then triggers a custom
fee payment. The triggering transaction should fail.

## Deliverables

Some deliverables above depend on others (e.g., a complete positive functional
EET needs helpers to validate state changes). Next we list the
deliverables in the order they should be implemented.

Note the prepatory EET framework items, which make it easier for the functional
tests to validate state changes via queries.

Tests should validate both fungible and non-fungible-unique token types.

### :ice_cube:&nbsp;State validation

- [x] _(EET framework)_ `GetTokenInfo` assert for pause status of the Token.
- [x] _(EET framework)_ `GetTokenInfo` assert for pause key of the Token.

### :cactus:&nbsp;Migration

- [x] _(Unit)_ A `MerkleToken` now serializes its pause Key and pause status.
- [x] _(Unit)_ A `MerkleToken` can be deserialized from a prior-version state.
- [x] _(Unit)_ A `MerkleToken` can be deserialized from a current-version state.

### :white_check_mark:&nbsp;Positive functional

- [x] _(EET)_ A `TokenCreate` with a `pauseKey` and `TokenPause` transaction signed by the this `pausekey` will set the token pause status as `Paused`. `tokenGetInfo` asserts this pause status.
- [x] _(EET)_ To the `Paused` token above we perform a `TokenUnpause` transaction signed by the same `pauseKey` which will set the token pause status as `Unpasued`. `tokenGetInfo` asserts this pause status.
- [x] _(EET)_ A newly created Token is asserted for a `Unpaused` token status.

### :x:&nbsp;Negative functional

- [x] _(EET)_ Setup a `Paused` token using `TokenCreate` with a `pauseKey` and perform a `TokenPause` transaction signed by the this `pausekey` which will set the token's pause status as `Paused`. `tokenGetInfo` asserts this pause status.
- [x] _(EET)_ A `TokenCreate` fails with this paused token as denominating token for custom fee.
- [x] _(EET)_ A `TokenUpdate` fails on this paused token.
- [x] _(EET)_ A `TokenAssociate` fails on this paused token.
- [x] _(EET)_ A `TokenDissociate` fails on this paused token.
- [x] _(EET)_ A `TokenDelete` fails on this paused token.
- [x] _(EET)_ A `TokenMint` fails on this paused token.
- [x] _(EET)_ A `TokenWipe` fails on this paused token.
- [x] _(EET)_ A `TokenBurn` fails on this paused token.
- [x] _(EET)_ A `TokenFeeze` fails on this paused token.
- [x] _(EET)_ A `TokenUnfreeze` fails on this paused token.
- [x] _(EET)_ A `TokenGrantKyc` fails on this paused token.
- [x] _(EET)_ A `TokenRevokeKyc` fails on this paused token.
- [x] _(EET)_ A `TokenFeeScheduleUpdate` fails on this paused token.
- [x] _(EET)_ A `CryptoTransfer` involving this paused token will fail.
- [x] _(EET)_ A multi-party `CryptoTransfer` rolls back all side effects if it fails due to a paused Token.

### :receipt:&nbsp;Custom fee interplays

- [x] _(EET)_ A `Paused` token as denominating token of a custom fee will result in a failure to any transaction that triggers this custom fee.
