# System accounts and files

The Hedera network reserves the first
[`ledger.numReservedSystemEntities=1000`](../hedera-mono-service/src/main/resources/bootstrap.properties)
entity numbers for its own uses.
An account with a number in the reserved range is called a **system account**.
A file with a number in the reserved range is called a **system file**.

## System account roles

Certain system accounts have predefined roles in the network.

For the purposes of this document, we care about the the following:
- The **treasury**, which upon network creation receives all minted ℏ except those
explicitly designated for a network node account.
- The **address book admin**, used to manage metadata on network nodes
such as their id numbers, IP addresses, TLS certificate information,
and signing keys. May also change the network throttles.
- The **fee schedules admin**, used to set the prices of resources consumed
by HAPI operations.
- The **exchange rates admin**, used to set the network's active conversion
ratio between USD and ℏ. May also change the network throttles.
- The **freeze admin**, used to schedule maintenance periods during which the
network stops accepting new transactions. In the future the freeze admin will
also be able to trigger an update to the network software or files.
- The **system delete admin**, used to delete files or contracts which may
have been created on the network with illicit storage contents. (Note that crypto
accounts, topics, and tokens are untouchable.)
- The **system undelete admin**, used to reverse provisional actions of the
system delete admin account under certain conditions.
- The **system admin**, used primarily to manage the keys of the above admin accounts;
or substitute for them in circumstances where they have been compromised or rendered
unusable.
- The **software update admin**, responsible for managing the contents of the software update file (`0.0.150`). Updating `0.0.150` itself does not update the network code; the network code is only updated by executing a successful freeze/upgrade process, initiated and signed by `0.0.58` (the freeze admin). Note that this update will not occur unless the hash in the freeze/upgrade transaction(s) matches the hash of the file stored in `0.0.150` at time of the freeze/upgrade submission.

The account number that plays each role is set, once, on network startup, by consulting
the [_bootstrap.properties_](../hedera-node/src/main/resources/bootstrap.properties)
resource. For example, using the mainnet configuration, the treasury account is account
`0.0.2` because we have `accounts.treasury=2` in the _bootstrap.properties_.

# Privileged transactions

When a system account is the designated payer for a transaction, there
are cases in which the network grants special **privileges** to the transaction.
(It is crucial to understand that the relevant system account must be the _payer_ of
the transaction for any privileges to be granted; that is, it must be the `AccountID`
designated in the transaction's `TransactionID`.)

There are two kinds of privileges,
1. _Authorization_ - some transaction types, such as `Freeze`, require authorization to submit to the network. All such transactions will be rejected with the status `UNAUTHORIZED` unless they are privileged.
2. _Waived signing requirements_ - all unprivileged `CryptoUpdate` and `FileUpdate` transactions must be signed with the target entity's key, or they will fail with status `INVALID_SIGNATURE`. The network waives this requirement for certain privileged updates.

This document lists the privileged transactions recognized by the Hedera network.

## Authorization privileges

First we consider the four transaction types that always require authorization to execute. These include:
1. The `Freeze` transaction that schedules a maintenance window in which the network
will stop accepting transactions and possibly perform a software update.
2. The `SystemDelete` transaction that deletes a file or contract (even an immutable
file or contract), without requiring the target entity's key to sign the transaction.
3. The `SystemUndelete` transaction that reverses the action of a `SystemDelete`
transaction, if within the window during which such a reversal is possible.
4. The `UncheckedSubmit` transaction that submits a transaction to the network
without enforcing standard prechecks. (The only real use cases for `UncheckedSubmit`
are in development environments, where it can be invaluable for testing.)

### Authorization privileges for special transactions

|                                            Payer                                            | `Freeze` | `SystemDelete` | `SystemUndelete` | `UncheckedSubmit` |
|---------------------------------------------------------------------------------------------|:--------:|:--------------:|:----------------:|:-----------------:|
| [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties)             |    X     |       X        |        X         |         X         |
| [`accounts.systemAdmin=50`](../hedera-node/src/main/resources/bootstrap.properties)         |    X     |       X        |        X         |         X         |
| [`accounts.freezeAdmin=58`](../hedera-node/src/main/resources/bootstrap.properties)         |    X     |                |                  |                   |
| [`accounts.systemDeleteAdmin=59`](../hedera-node/src/main/resources/bootstrap.properties)   |          |       X        |                  |                   |
| [`accounts.systemUndeleteAdmin=60`](../hedera-node/src/main/resources/bootstrap.properties) |          |                |        X         |                   |

### Authorization privileges for file updates and appends

Next we consider `FileUpdate` and `FileAppend` transactions when targeting one of the system files.

|                                            Payer                                            | [`files.addressBook=101`](../hedera-node/src/main/resources/bootstrap.properties)/[`files.nodeDetails=102`](../hedera-node/src/main/resources/bootstrap.properties) | [`files.networkProperties=121`](../hedera-node/src/main/resources/bootstrap.properties)/[`files.hapiPermissions=122`](../hedera-node/src/main/resources/bootstrap.properties) | [`files.feeSchedules=111`](../hedera-node/src/main/resources/bootstrap.properties) | [`files.exchangeRates=112`](../hedera-node/src/main/resources/bootstrap.properties) | [`files.softwareUpdateZip=150`](../hedera-node/src/main/resources/bootstrap.properties) | [`files.throttleDefinitions=123`](../hedera-node/src/main/resources/bootstrap.properties) |
|---------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:----------------------------------------------------------------------------------:|:-----------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------:|:-----------------------------------------------------------------------------------------:|
| [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties)             |                                                                                  X                                                                                  |                                                                                       X                                                                                       |                                         X                                          |                                          X                                          |                                            X                                            |                                             X                                             |
| [`accounts.systemAdmin=50`](../hedera-node/src/main/resources/bootstrap.properties)         |                                                                                  X                                                                                  |                                                                                       X                                                                                       |                                         X                                          |                                          X                                          |                                            X                                            |                                             X                                             |
| [`accounts.softwareUpdateAdmin=54`](../hedera-node/src/main/resources/bootstrap.properties) |                                                                                                                                                                     |                                                                                                                                                                               |                                                                                    |                                                                                     |                                            X                                            |                                                                                           |
| [`accounts.addressBookAdmin=55`](../hedera-node/src/main/resources/bootstrap.properties)    |                                                                                  X                                                                                  |                                                                                       X                                                                                       |                                                                                    |                                                                                     |                                                                                         |                                             X                                             |
| [`accounts.feeSchedulesAdmin=56`](../hedera-node/src/main/resources/bootstrap.properties)   |                                                                                                                                                                     |                                                                                                                                                                               |                                         X                                          |                                                                                     |                                                                                         |                                                                                           |
| [`accounts.exchangeRatesAdmin=57`](../hedera-node/src/main/resources/bootstrap.properties)  |                                                                                                                                                                     |                                                                                       X                                                                                       |                                                                                    |                                          X                                          |                                                                                         |                                             X                                             |
| [`accounts.freezeAdmin=58`](../hedera-node/src/main/resources/bootstrap.properties)         |                                                                                                                                                                     |                                                                                                                                                                               |                                                                                    |                                                                                     |                                            X                                            |                                                                                           |

### Authorization for crypto updates

For the `CryptoUpdate` transaction, we have the minimal table below. The _only_ target account which
requires an authorized payer is account number [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties).
(Note that before release 0.10.0, a `CryptoUpdate` targeting _any_ system account required an
authorized payer. Since 0.10.0 it has been possible to, for example, update `0.0.88` with
`0.0.12345` as the payer, as long as the key for `0.0.88` signs the transaction.)

|                                      Payer                                      | [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties) |
|---------------------------------------------------------------------------------|:-------------------------------------------------------------------------------:|
| [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties) |                                        X                                        |

## Waived signing requirements

The next class of privileges apply to certain `CryptoUpdate` and `FileUpdate`/`FileAppend`
operations whose target is a system account or file. These privileges waive the normal
requirement that the key associated to an entity sign any transaction that updates it.
When the system entity's key is being updated, they _also_ waive the requirement that the
entity's new key sign the update transaction.

The network grants these privileges so the admin accounts can never be "locked out" of
performing their system roles. For example, even if we lose the key to the exchange rates
file, the exchange rates admin can still issue a `FileUpdate` transaction to change this
file.

### Waived signing requirements for file updates

The waived signature privileges for `FileUpdate` and `FileAppend` are identical to
the corresponding authorization privileges in the tables above.

:tipping_hand_person:&nbps; This means that the keys attached to system files are
purely ornamental, since only authorized payers can update these files---but all
signing requirements are waived for authorized payers.

### Waived signing requirements for crypto updates

The waived signature privileges for `CryptoUpdate` only apply to two authorized payers,
as below.

:warning:&nbsp; Notice that no signing requirements are waived for updates to the
treasury account. In particular, a `CryptoUpdate` that changes the key on the
treasury account always requires the new key to sign.

|                                        Payer                                        | Accounts after [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties) and up to [`ledger.numReservedSystemEntities=1000`](../hedera-node/src/main/resources/bootstrap.properties) |
|-------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| [`accounts.treasury=2`](../hedera-node/src/main/resources/bootstrap.properties)     |                                                                                                     X                                                                                                      |
| [`accounts.systemAdmin=50`](../hedera-node/src/main/resources/bootstrap.properties) |                                                                                                     X                                                                                                      |

# Miscellanea

- When privileges are granted based on the payer, the network charges no fees.
