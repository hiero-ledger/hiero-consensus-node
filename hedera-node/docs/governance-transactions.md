# Increased Transaction Size for Governance Accounts

## Purpose

- Allow governance accounts ([`governanceTransactions.accountsRange=2,42-799`](../hedera-node/src/main/resources/bootstrap.properties)) to submit transactions up to 130KB in size.
- Non-governance accounts remain constraint to the standard limit of 6KB transaction size.
- Apply size limits in ingest after parsing the payer account.
- Differentiate from [jumbo transactions](./design/services/smart-contract-service/jumbo-transactions.md) which apply to Ethereum transaction types regardless of payer.

## Prerequisite reading

* [HIP-1300](https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1300)

## Architecture and Implementation

### Configuration

Define new config properties `GovernanceTransactionsConfig` containing\
a feature flag to enable governance transactions and set the max size limit for governance transactions.\

### Changes to the gRPC layer

- To ensure all RPC endpoints can handle large transactions submitted by governance payers:
- Increase the gRPC marshaller buffer to governance size for all RPC endpoints when governance transactions are enabled.
- Set the `DataBufferMarshaller` buffer capacity to `max(governanceMaxTxnSize, jumboMaxTxnSize, standardMaxTxnSize) + 1` for all marshallers.
- This ensures all endpoints can handle transactions up to the maximum size allowed by any enabled feature, with final size validation happening at ingest based on transaction type and payer account.
- The `NettyGrpcServerManager` creates marshallers with appropriate buffer capacities based on feature flags.

### Ingest workflow validations

- In `IngestChecker.runAllChecks` use the size limit from the configuration and pass it to `TransactionChecker.parseAndCheck` to validate the transaction size.
- Fail fast if there are too many transaction bytes

```java
if (buffer.length() > maxSize) {
    throw new PreCheckException(TRANSACTION_OVERSIZE);
}
```

### Transaction size validation

A two-phased validation approach is used for optimal performance and early failure

#### Phase 1: Basic size validation (before payer is known)

- Inside the `TransactionChecker.checkParsed` method, validate the size limit.
- The performed validations are based on\
  two feature flags: `jumboTransactions` and `governanceTransactions`
- For more context how jumbo transactions work, refer to: [Jumbo Transactions](./design/services/smart-contract-service/jumbo-transactions.md)
- Check the transaction size during preliminary checks.

```java
void checkTransactionSize(TransactionInfo txInfo);
```

- Introduce an util method which defines the maximum allowed transaction size based on all related feature flags.

```java
int getMaxAllowedTransactionSize(
        @NonNull final HederaFunctionality functionality, @Nullable final AccountID payerAccountId);
```

#### Phase 2: Payer-specific size validation (after payer is known)

- Additionally, check the transaction size limit based on the payer account in `TransactionChecker.checkTransactionSizeLimitBasedOnPayer`
- When governance transactions are enabled, allow governance payers ([`governanceTransactions.accountsRange=2,42-799`](../hedera-node/src/main/resources/bootstrap.properties)) up to governance limits (130KB) for any transaction type
- Non-governance payers remain limited to standard limits (6KB), except for allowed jumbo Ethereum transactions

```java
void checkTransactionSizeLimitBasedOnPayer(
        @NonNull final TransactionInfo txInfo, @NonNull final com.hedera.hapi.node.base.AccountID payerAccountId);
```

## Acceptance Criteria

#### Positive test cases

- Transactions up to 130KB in size submitted by a governance payer should be accepted

#### Negative test cases

- Transactions more than 6KB in size submitted by a non-governance payer should be rejected
- Transactions more than 130KB in size submitted by a governance payer should be rejected.

#### Important notes

Any configurations updated during runtime of the consensus node and used in the grpc layer of the system **will not** be reflected and applied to the grpc layer until the node is restarted.

**NEXT: [Privileged Transactions](privileged-transactions.md)**
