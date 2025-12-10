# System entities

The Hedera network reserves the first
[`ledger.numReservedSystemEntities=1000`](../hedera-mono-service/src/main/resources/bootstrap.properties)
entity numbers for its own uses.

An account with a number in the reserved range is called a **system account**.
A file with a number in the reserved range is called a **system file**.

The entity numbers for system accounts and files are fixed, for the network,
in [`bootstrap.properties`](../hedera-mono-service/src/main/resources/bootstrap.properties).

See also [`privileged-transactions.md`](privileged-transactions.md) for the semantics of these system entities.

## System accounts

| Account   | Name                            |
|:----------|:--------------------------------|
| `0.0.2`   | `accounts.treasury`             |
| `0.0.50`  | `accounts.systemAdmin`          |
| `0.0.55`  | `accounts.addressBookAdmin`     |
| `0.0.57`  | `accounts.exchangeRatesAdmin`   |
| `0.0.58`  | `accounts.freezeAdmin`          |
| `0.0.59`  | `accounts.systemDeleteAdmin`    |
| `0.0.60`  | `accounts.systemUndeleteAdmin`  |
| `0.0.800` | `accounts.stakingRewardAccount` |
| `0.0.801` | `accounts.nodeRewardAccount`    |
| `0.0.802` | `accounts.feeCollectionAccount` |

## System files

| File                 | Name                                       |
|:---------------------|:-------------------------------------------|
| `0.0.101`            | `files.addressBook`                        |
| `0.0.102`            | `files.nodeDetails`                        |
| `0.0.111`            | `files.feeSchedules`                       |
| `0.0.112`            | `files.exchangeRates`                      |
| `0.0.121`            | `files.networkProperties`                  |
| `0.0.122`            | `files.hapiPermissions`                    |
| `0.0.123`            | `files.throttleDefinitions`                |
| `0.0.150`..`0.0.159` | `files.softwareUpdate` (range of 10 files) |

## System contract addresses

| Address₁₆ | Address₁₀ | Name                 |
|:----------|:----------|:---------------------|
| `0x167`   | `359`     | Hedera Token Service |
| `0x168`   | `360`     | Exchange Rate        |
| `0x169`   | `361`     | PRNG                 |
