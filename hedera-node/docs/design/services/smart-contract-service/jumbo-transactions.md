# Jumbo EthereumTransactions

## Purpose
- Raise the limit on the size of Ethereum transaction data to 128KB, and the limit on the total size of Ethereum transactions from 6KB to 130KB. 
- Introduce a new throttle bucket that represents the max bytes-per-second for accepting "jumbo" transactions larger than 6KB on the network. 
- Introduce non-linear pricing for transactions larger than 6KB to incentivize smaller sized transactions where possible.
Note: Fees are still in discussion and not yet finalized.

## Prerequisite reading
* [HIP-1086](https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1086)


## Architecture and Implementation

### Configuration
Define new config properties `JumboTransactionsConfig` containing a feature flag to enable jumbo transactions,
set the max size for Ethereum transactions and list of transactions that support jumbo size.
The default values are:
```java
@ConfigData("jumboTransactions")
public record JumboTransactionsConfig(
    @ConfigProperty(defaultValue = "false") @NetworkProperty boolean isEnabled,
    @ConfigProperty(defaultValue = "133120") @NetworkProperty int maxTxnSize,
    @ConfigProperty(defaultValue = "131072") @NetworkProperty int ethereumMaxCallDataSize,
    @ConfigProperty(defaultValue = "callEthereum") @NodeProperty List<String> grpcMethodNames,
    @ConfigProperty(defaultValue = "EthereumTransaction") @NodeProperty List<HederaFunctionality> allowedHederaFunctionalities) {}
```

### gRPC modification
Instead of increasing the buffer size of all incoming requests or performing complex bytes comparisons to identify ethereum transactions (before parsing):
- Increase the buffer size only for the requests that are using the jumbo transaction endpoints (From config `grpcMethodNames`, default `callEthereum`).
  This way, the total size of allocated buffers will be relatively small, and there will be no additional changes needed, if transactions protobufs are modified in the future.
  Note: Setting size limits on the `ethereumCall` endpoint does not mean only ethereum transactions can pass through this endpoint, so **there must be an additional check in ingest** to fail any non-ethereum transactions bigger than 6kb.
- Modify `GrpcServiceBuilder.java`, `MethodBase.java`, and `DataBufferMarshaller.java` so the request buffer size can be read from configuration.
  In `GrpcServiceBuilder`, when building service definition, add a condition, based on the feature flag and service/method names.
```java
// check if method should be a jumbo transaction
if (jumboTxnIsEnabled && jumboTxnConfig.grpcMethodNames().contains(methodName)) {
    // add jumbo transaction methods
    method = new TransactionMethod(serviceName, methodName, ingestWorkflow, metrics, jumboTxnMaxSize);
    addMethod(builder, serviceName, methodName, method, jumboMarshaller);
} else {
    // add regular transaction methods
    method = new TransactionMethod(serviceName, methodName, ingestWorkflow, metrics, messageMaxSize);
    addMethod(builder, serviceName, methodName, method, marshaller);
}
```

### Ingest workflow validations
- In `IngestChecker.runAllChecks` use size limit from the configuration and pass it to `TransactionChecker.parseAndCheck` to validate the transaction size. 
```java
private static int maxIngestParseSize(Configuration configuration) {
    final var jumboTxnEnabled =
            configuration.getConfigData(JumboTransactionsConfig.class).isEnabled();
    final var jumboMaxTxnSize =
            configuration.getConfigData(JumboTransactionsConfig.class).maxTxnSize();
    final var transactionMaxBytes =
            configuration.getConfigData(HederaConfig.class).transactionMaxBytes();
    return jumboTxnEnabled ? jumboMaxTxnSize : transactionMaxBytes;
}
```
```java
// Fail fast if there are too many transaction bytes
if (buffer.length() > maxSize) {
	throw new PreCheckException(TRANSACTION_OVERSIZE);
}
```

- Inside the `TransactionChecker.checkParsed` method validate the size limit and the functionality of the transaction.
```java
void checkJumboTransactionBody(TransactionInfo txInfo) throws PreCheckException {
    final var jumboTxnEnabled = jumboTransactionsConfig.isEnabled();
    final var allowedJumboHederaFunctionalities = jumboTransactionsConfig.allowedHederaFunctionalities();
    final var maxJumboEthereumCallDataSize = jumboTransactionsConfig.ethereumMaxCallDataSize();

    if (jumboTxnEnabled
            && txInfo.serializedTransaction().length() > hederaConfig.transactionMaxBytes()
            && !allowedJumboHederaFunctionalities.contains(fromPbj(txInfo.functionality()))) {
        throw new PreCheckException(TRANSACTION_OVERSIZE);
    }

    if (txInfo.txBody() != null
            && txInfo.txBody().hasEthereumTransaction()
            && txInfo.txBody().ethereumTransaction().ethereumData().length() > maxJumboEthereumCallDataSize) {
        throw new PreCheckException(TRANSACTION_OVERSIZE);
    }
}
```

- Additionally, check after Ethereum hydrate is done, if `ethereumCallData` field is up to 128KB

### Throttles

Implement a **byte limit throttle** using a similar structure like the`GasLimitBucketThrottle`e.g.:
- Rename `GasLimitButcketThrottle` to more generic `LeakyBucketDeterministicThrottle.java` so it can be used for both gas and bytes. 
```java
public class LeakyBucketDeterministicThrottle implements CongestibleThrottle {
  private final String throttleName;
  private final LeakyBucketThrottle delegate;
  private Timestamp lastDecisionTime;
  private final long capacity;
```
- Initialize the byte throttle in `ThrottleAccumulator`:
```java
public void applyBytesConfig() {
    final var configuration = configSupplier.get();
    final var jumboConfig = configuration.getConfigData(JumboTransactionsConfig.class);
    final var bytesPerSec = jumboConfig.maxBytesPerSec();
    if (jumboConfig.isEnabled() && bytesPerSec == 0) {
        log.warn("{} jumbo transactions are enabled, but limited to 0 bytes/sec", throttleType.name());
    }
    bytesThrottle = new LeakyBucketDeterministicThrottle(bytesPerSec, "Bytes");
    if (throttleMetrics != null) {
        throttleMetrics.setupBytesThrottleMetric(bytesThrottle, configuration);
    }
    if (verbose == Verbose.YES) {
        log.info(
                "Resolved {} bytes throttle -\n {} bytes/sec (throttling {})",
                throttleType.name(),
                bytesThrottle.capacity(),
                (jumboConfig.isEnabled() ? "ON" : "OFF"));
    }
}
```
- `shouldThrottleTxn()` method should be extended to enforce throttling based on a configurable **max bytes per second** limit
```java
if (isJumboTransactionsEnabled) {
  final var allowedHederaFunctionalities =
          configuration.getConfigData(JumboTransactionsConfig.class).allowedHederaFunctionalities();
  if (allowedHederaFunctionalities.contains(fromPbj(txnInfo.functionality()))) {
      final var bytesUsage = txnInfo.transaction().protobufSize();
      final var maxRegularTxnSize =
              configuration.getConfigData(HederaConfig.class).transactionMaxBytes();
  
      final var excessBytes = bytesUsage > maxRegularTxnSize ? bytesUsage - maxRegularTxnSize : 0;
      if (shouldThrottleBasedExcessBytes(excessBytes, now)) {
          return true;
      }
  }
}
```
```java
private boolean shouldThrottleBasedExcessBytes(final long bytesUsed, @NonNull final Instant now) {
        // If the bucket doesn't allow the txn enforce the throttle
        return bytesThrottle != null && !bytesThrottle.allow(now, bytesUsed);
    }
```

### Fees (TBD)

We are calculating the fees in `getEthereumTransactionFeeMatrices` method. There, we are calculating the fees based on the size of the transaction:
`bpt = txBodySize + getEthereumTransactionBodyTxSize(txBody) + sigValObj.getSignatureSize();`
This is a linear fee calculation based on the size of the body and the Ethereum data.

We can change this logic when we decide how we want the fees to be implemented.

## Acceptance Tests

#### Positive Tests

- validate that 
- validate that 
- validate that 

#### Negative Tests

- validate that 
- validate that 
- validate that 
