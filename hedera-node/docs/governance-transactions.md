# Increased Transaction Size for Governance Accounts

## Purpose

- Allow privileged accounts ([`accounts.governanceTransactions=2,42-799`](../hedera-node/src/main/resources/bootstrap.properties)) to submit transactions up to 130KB in size.
- Non-privileged accounts remain constraint to the standard limit of 6KB transaction size.
- Apply size limits in ingest after parsing the payer account.
- Differentiate from [jumbo transactions](./design/services/smart-contract-service/jumbo-transactions.md) which apply to Ethereum transaction types regardless of payer.

## Prerequisite reading

* [HIP-1300](https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1300)

## Architecture and Implementation

### Configuration

Define new config properties `GovernanceTransactionsConfig` containing\
a feature flag to enable governance transactions and set the max size limit for governance transactions.\
The default values are:

```java
@ConfigData("governanceTransactions")
public record GovernanceTransactionsConfig(
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean isEnabled,
        @ConfigProperty(defaultValue = "133120") @NetworkProperty int maxTxnSize) {}

```

### Changes to the gRPC layer

- To ensure all RPC endpoints can handle large transactions submitted by privileged payers:
- Increase the gRPC marshaller buffer to governance size for all RPC endpoints when governance transactions are enabled.
- Set the `DataBufferMarshaller` buffer capacity to `max(governanceMaxTxnSize, jumboMaxTxnSize, standardMaxTxnSize) + 1` for all marshallers.
- This ensures all endpoints can handle transactions up to the maximum size allowed by any enabled feature, with final size validation happening at ingest based on transaction type and payer account.
- The `NettyGrpcServerManager` creates marshallers with appropriate buffer capacities based on feature flags.

```java
final boolean isGovTxnEnabled = configProvider
        .getConfiguration()
        .getConfigData(GovernanceTransactionsConfig.class)
        .isEnabled();

final int govMaxTxnSize = isGovTxnEnabled
        ? configProvider
                .getConfiguration()
                .getConfigData(GovernanceTransactionsConfig.class)
                .maxTxnSize()
        : standardMaxTxnSize;

// set buffer capacity to be big enough to hold the largest transaction
final int bufferCapacity = Math.max(Math.max(govMaxTxnSize, jumboMaxTxnSize), standardMaxTxnSize) + 1;

// set capacity and max transaction size for both normal and jumbo transactions
final var dataBufferMarshaller = new DataBufferMarshaller(bufferCapacity, govMaxTxnSize);
final var jumboBufferMarshaller = new DataBufferMarshaller(bufferCapacity, jumboMaxTxnSize);
```

### Ingest workflow validations

- In `IngestChecker.runAllChecks` use the size limit from the configuration and pass it to `TransactionChecker.parseAndCheck` to validate the transaction size.

```java
private static int maxIngestParseSize(Configuration configuration) {
    final boolean jumboTxnEnabled =
            configuration.getConfigData(JumboTransactionsConfig.class).isEnabled();
    final int jumboMaxTxnSize =
            configuration.getConfigData(JumboTransactionsConfig.class).maxTxnSize();
    final int transactionMaxBytes =
            configuration.getConfigData(HederaConfig.class).transactionMaxBytes();
    final boolean governanceTxnEnabled =
            configuration.getConfigData(GovernanceTransactionsConfig.class).isEnabled();
    final int governanceTxnSize =
            configuration.getConfigData(GovernanceTransactionsConfig.class).maxTxnSize();
    return governanceTxnEnabled ? governanceTxnSize : jumboTxnEnabled ? jumboMaxTxnSize : transactionMaxBytes;
}
```

- Fail fast if there are too many transaction bytes

```java
if (buffer.length() > maxSize) {
    throw new PreCheckException(TRANSACTION_OVERSIZE);
}
```

### Transaction size validation

A two-phased validation approach is used for optimal performance and early failure

#### Phase 1: Basic size validation

- Inside the `TransactionChecker.checkParsed` method, validate the size limit.
- The performed validations are based on\
  two feature flags: `jumboTransactions` and `governanceTransactions`
- For more context how jumbo transactions work, refer to: [Jumbo Transactions](./design/services/smart-contract-service/jumbo-transactions.md)

```java
void checkTransactionSize(TransactionInfo txInfo) throws PreCheckException {
    final int txSize = txInfo.signedTx().protobufSize();
    final HederaFunctionality functionality = txInfo.functionality();
    final boolean isJumboTxnEnabled = jumboTransactionsConfig.isEnabled();
    final boolean isGovernanceTxnEnabled = governanceTransactionsConfig.isEnabled();
    boolean exceedsLimit;

    if (!isJumboTxnEnabled && !isGovernanceTxnEnabled) {
        exceedsLimit = txSize > hederaConfig.transactionMaxBytes()
                && !NON_JUMBO_TRANSACTIONS_BIGGER_THAN_6_KB.contains(functionality);
    }
    else if (isJumboTxnEnabled && !isGovernanceTxnEnabled) {
        exceedsLimit = checkJumboRequirements(txInfo);
    }
    else {
        exceedsLimit = txSize > governanceTransactionsConfig.maxTxnSize();
    }

    if (exceedsLimit) throw new PreCheckException(TRANSACTION_OVERSIZE);
}
```

#### Phase 2: Payer-specific size validation (after payer is known)

- Additionally, check the transaction size limit based on the payer account in `TransactionChecker.checkTransactionSizeLimitBasedOnPayer`
- When governance transactions are enabled, allow privileged payers ([`accounts.governanceTransactions=2,42-799`](../hedera-node/src/main/resources/bootstrap.properties)) up to governance limits (130KB) for any transaction type
- Non-privileged payers remain limited to standard limits (6KB), except for allowed jumbo Ethereum transactions

```java
void checkTransactionSizeLimitBasedOnPayer(
        @NonNull final TransactionInfo txInfo, @NonNull final com.hedera.hapi.node.base.AccountID payerAccountId)
        throws PreCheckException {

    final int txSize = txInfo.signedTx().protobufSize();
    final boolean isPrivilegedPayer = accountsConfig.isSuperuser(payerAccountId);
    boolean exceedsLimit;

    if (isPrivilegedPayer) {
        exceedsLimit = txSize > governanceTransactionsConfig.maxTxnSize();
    } else {
        if (jumboTransactionsConfig.isEnabled()) {
            exceedsLimit = checkJumboRequirements(txInfo);
        } else {
            exceedsLimit = txSize > hederaConfig.transactionMaxBytes()
                    && !NON_JUMBO_TRANSACTIONS_BIGGER_THAN_6_KB.contains(txInfo.functionality());
        }
    }

    if (exceedsLimit) {
        if (!isPrivilegedPayer) {
            nonPrivilegedOversizedTransactionsCounter.increment();
        }
        throw new PreCheckException(TRANSACTION_OVERSIZE);
    }
}
```

## Acceptance Criteria

#### Positive test cases

- Transactions up to 130KB in size submitted by a privileged payer should be accepted

#### Negative test cases

- Transactions more than 6KB in size submitted by a non-privileged payer should be rejected
- Transactions more than 130KB in size submitted by a privileged payer should be rejected.

#### Important notes

Any configurations updated during runtime of the consensus node and used in the grpc layer of the system **will not** be reflected and applied to the grpc layer until the node is restarted.

**NEXT: [Privileged Transactions](privileged-transactions.md)**
