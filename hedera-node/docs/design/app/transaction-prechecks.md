# Transaction prechecks

When a gRPC client submits a [`Transaction`](https://hashgraph.github.io/hedera-protobufs/#Transaction.proto)
to a Hedera Services node, the node does not---in turn---immediately
submit the `Transaction` to the Platform (where it would be assigned
a consensus timestamp, and then handled at that consensus time).

Rather, the node first performs a "precheck" on the `Transaction` and
_only_ submits it to the Platform if the precheck passes. Precheck
may be broken into five stages, as follows:
1. :building_construction:&nbsp;&nbsp;**Structural checks** test
if the top-level `bytes` fields in the `Transaction` are set
correctly, are within size limits, and contain a parseable
gRPC [`TransactionBody`](https://hashgraph.github.io/hedera-protobufs/#TransactionBody.proto)
that requests exactly one function supported by the network.

2. :memo:&nbsp;&nbsp; **Syntax checks** confirm that the parsed
   `TransactionBody` has all necessary fields set, including
   a feasible valid start time and duration; and has a
   `TransactionID` that is believed to be unique for this node.

3. :dart:&nbsp;&nbsp; **Semantic checks** test if the specific HAPI
   function requested by the `Transaction` is well-formed; note
   that these tests are always specific to the requested function,
   and are repeated at consensus.

4. :moneybag:&nbsp;&nbsp; **Solvency checks** determine if the payer
   account set in the `TransactionID` is expected to be both
   willing and able to pay the node and network fees.

5. :shield:&nbsp;&nbsp; **System checks** test if the network can be
   expected to handle the given `TransactionBody` if it does reach
   consensus---that is, if the requested HAPI function is enabled
   on the network, the payer
   [has the required privileges](../../privileged-transactions.md) to use it,
   and its [throttle bucket(s)](../../throttle-design.md) has capacity.

The node only performs later checks if the earlier checks pass; and
it performs no checks at all if the Platform is in maintenance mode,
instead immediately responding to the gRPC request with a status
code of `PLATFORM_NOT_ACTIVE`.

In this document, we cover all the response codes that a gRPC client
can receive due to a failure in any of the stages of precheck, _except_
the third. We do not cover failed semantic prechecks for two reasons.
First, they are usually specific enough to be self-explanatory (e.g.,
`ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS`); and, second, they are likely to
be removed from precheck in future releases as Hedera client libraries
are enhanced to perform these checks before any network interaction occurs.
(Of course the checks will always be enforced at consensus! :guard:)

## A comment on queries

It is important to understand that the header of any non-free `Query`
includes a `CryptoTransfer` transaction that will be submitted to the
network to pay the node that answers the query.

This means all the response codes in this document are _also_
possible for any non-free query, since they could arise from the
precheck performed on the query's enclosed `CryptoTransfer`.

## :building_construction:&nbsp; Failed structural checks

- `INVALID_TRANSACTION`
  * The `Transaction` used both a deprecated top-level field (that is,
    either `bodyBytes` or `sigMap`) _and_ the `signedTransactionBytes` field.
- `INVALID_TRANSACTION_BODY`
  * No `TransactionBody` could be parsed from the top-level `Transaction`; or,
  * The parsed `TransactionBody` did not have any function-specific body set.
- `TRANSACTION_OVERSIZE`
  * The serialized size of the `Transaction` exceeded 6144 bytes,
    which is the maximum allowed by the Platform.
- `TRANSACTION_TOO_MANY_LAYERS`
  * The `Transaction` nested protobuf messages more deeply than
    `hedera.transaction.maxProtoMessageDepth` (default 50) allows, so strict parsing at
    ingest rejected it before consensus. A nested key adds ~2 message frames per
    `KeyList` level and ~3 per `ThresholdKey` level, so a key nested past ~24 `KeyList`
    (or ~16 `ThresholdKey`) levels trips this. Keys that parse but exceed the 15-level
    semantic cap are instead rejected during handling with the operation's own key code
    (e.g. `BAD_ENCODING` for `CryptoCreate`).

## :memo:&nbsp; Failed syntax checks

- `INVALID_TRANSACTION_ID`
  * The `TransactionBody` was missing a `TransactionID` field.
- `TRANSACTION_ID_FIELD_NOT_ALLOWED`
  * The `TransactionID` field set the `scheduled` flag to `true`.
- `DUPLICATE_TRANSACTION`
  * Another transaction with the given `TransactionID` has already
    been submitted by this node.
- `INVALID_NODE_ACCOUNT`
  * There was no node `AccountID` given, or the given node
    account didn't match the account of the node receiving the
    top-level `Transaction`.
- `MEMO_TOO_LONG`
  * The [`TransactionBody` memo](https://hashgraph.github.io/hedera-protobufs/#TransactionBody.proto)
    had a UTF-8 encoding of more than 100 bytes.
- `INVALID_ZERO_BYTE_IN_STRING`
  * The memo in the `TransactionBody` included the `NUL` code point
    (UTF-8 encoding of `0`, prohibited due to its common usage as a
    string delimiter in database internals).
- `INVALID_TRANSACTION_DURATION`
  * The given `transactionValidDuration` fell outside the range set
    by the [`hedera.transaction.minValidDuration` and
    `hedera.transaction.maxValidDuration` properties](../hedera-node/src/main/resources/bootstrap.properties),
    which default to 15 and 120 seconds, respectively.
- `INVALID_TRANSACTION_START`
  * Even allowing for a delay of up to
    [`hedera.transaction.minValidityBufferSecs`](../hedera-node/src/main/resources/bootstrap.properties)
    seconds to reach consensus (default is 10 seconds), the given
    `transactionValidStart` is likely to fall _after_ any assigned
    consensus timestamp, preventing the transaction from being handled
    at consensus.
- `TRANSACTION_EXPIRED`
  * After allowing for a delay of up to
    [`hedera.transaction.minValidityBufferSecs`](../hedera-node/src/main/resources/bootstrap.properties)
    seconds to reach consensus (default is 10 seconds), the window
    implied by the given `transactionValidStart` and
    `transactionValidDuration` is likely to end _before_ any
    assigned consensus timestamp, preventing the transaction
    from being handled at consensus.

## :moneybag:&nbsp; Failed solvency checks

- `INSUFFICIENT_TX_FEE`
  * No positive [`transactionFee`](https://hashgraph.github.io/hedera-protobufs/#proto.TransactionBody)
    was offered, or a positive fee _was_ offered, but was insufficient
    to cover the network and node fees for the transaction.
- `PAYER_ACCOUNT_NOT_FOUND`
  * There was no payer `AccountID` given, or a payer _was_ given,
    but the given account was deleted or missing.
- `KEY_PREFIX_MISMATCH`
  * More than one [`pubKeyPrefix`](https://hashgraph.github.io/hedera-protobufs/#proto.SignaturePair)
    in the resolved [`SignatureMap`](https://hashgraph.github.io/hedera-protobufs/#proto.SignatureMap)
    for the transaction was a prefix of the same Ed25519 key in the
    payer's Hedera key.
- `INVALID_ACCOUNT_ID`
  * _(Query precheck only)_ The query payment [`transfers`](https://hashgraph.github.io/hedera-protobufs/#proto.TransferList)
    list included a non-existent or deleted `AccountID`.
- `INVALID_SIGNATURE`
  * The resolved `SignatureMap` did not contain valid signatures
    for enough Ed25519 keys to activate the payer's Hedera key; or,
  * _(Query precheck only)_ Same as above, but for a non-payer key
    required to sign the payment `CryptoTransfer`.
- `INSUFFICIENT_PAYER_BALANCE`
  * Although the offered `transactionFee` was sufficient, the balance
    of the payer account was not expected to be able to cover it.
- `FAIL_FEE`
  * Something highly unusual prevented the node from calculating the
    required fee. We would appreciate a [bug report](https://github.com/hashgraph/hedera-services/issues/new?assignees=&labels=bug&template=1-bug-report.md&title=)
    if you receive this error code!

## :shield:&nbsp; Failed system checks

- `NOT_SUPPORTED`
  * The requested HAPI function was either not enabled, or is reserved
    for privileged system accounts.
- `AUTHORIZATION_FAILED`
  * The payer used for the transaction did not [have the required privileges](../../privileged-transactions.md)
    to perform the HAPI function against the referenced entity; for
    example, a non-privileged payer cannot update a system file.
- `ENTITY_NOT_ALLOWED_TO_DELETE`
  * The requested HAPI operation tried to delete a system entity.
- `BUSY`
  * The network does not have available capacity in one or
    more of the [throttle bucket(s)](../../throttle-design.md) to
    which the requested function is assigned.
