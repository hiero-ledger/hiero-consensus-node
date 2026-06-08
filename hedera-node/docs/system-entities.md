# System entities

The current default IDs for system accounts and files are defined in
[`AccountsConfig`](../hedera-config/src/main/java/com/hedera/node/config/data/AccountsConfig.java),
[`FilesConfig`](../hedera-config/src/main/java/com/hedera/node/config/data/FilesConfig.java),
[`HederaConfig`](../hedera-config/src/main/java/com/hedera/node/config/data/HederaConfig.java), and
[`LedgerConfig`](../hedera-config/src/main/java/com/hedera/node/config/data/LedgerConfig.java).
Bootstrap-time configuration is assembled by
[`BootstrapConfigProviderImpl`](../hedera-app/src/main/java/com/hedera/node/app/config/BootstrapConfigProviderImpl.java).

An account with one of these reserved numbers is called a **system account**.
A file with one of these reserved numbers is called a **system file**.

The tables below capture the current built-in defaults.

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

| File                 | Name                        |
|:---------------------|:----------------------------|
| `0.0.101`            | `files.addressBook`         |
| `0.0.102`            | `files.nodeDetails`         |
| `0.0.111`            | `files.feeSchedules`        |
| `0.0.112`            | `files.exchangeRates`       |
| `0.0.113`            | `files.simpleFeesSchedules` |
| `0.0.121`            | `files.networkProperties`   |
| `0.0.122`            | `files.hapiPermissions`     |
| `0.0.123`            | `files.throttleDefinitions` |
| `0.0.150`..`0.0.159` | `files.softwareUpdateRange` |

## System contract addresses

| Address₁₆ | Address₁₀ | Name                 |
|:----------|:----------|:---------------------|
| `0x167`   | `359`     | Hedera Token Service |
| `0x168`   | `360`     | Exchange Rate        |
| `0x169`   | `361`     | PRNG                 |
