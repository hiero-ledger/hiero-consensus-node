# Jumbo EthereumTransactions

## Purpose

- Raise the limit on the size of Ethereum transaction data to 128KB, and the limit on the total size of Ethereum transactions from 6KB to 130KB. 
- Introduce a new throttle bucket that represents the max bytes-per-second for accepting "jumbo" transactions larger than 6KB on the network. 
- Introduce non-linear pricing for transactions larger than 6KB to incentivize smaller sized transactions where possible.
Note: Fees are still in discussion and not yet finalized.

## Prerequisite reading

* [HIP-1086](https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1086)
Note: The HIP-1086 is still a PR.


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
@ConfigProperty(defaultValue = "EthereumTransaction") @NodeProperty
List<HederaFunctionality> allowedHederaFunctionalities) {}
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
final var method = (hederaConfig.jumboTransactionIsEnabled() && jumboMethodNames.contains(methodName))
? new TransactionMethod(serviceName, methodName, ingestWorkflow, metrics, hederaConfig.transactionJumboSize())
: new TransactionMethod(serviceName, methodName, ingestWorkflow, metrics, hederaConfig.transactionMaxSize());
```

```java
MethodDescriptor<BufferedData, BufferedData> methodDescriptor;
if (hederaConfig.jumboTransactionIsEnabled() && jumboMethodNames.contains(methodName)) {
    methodDescriptor = MethodDescriptor.<BufferedData, BufferedData>newBuilder()
            .setType(MethodType.UNARY)
            .setFullMethodName(serviceName + "/" + methodName)
            .setRequestMarshaller(JUMBO_MARSHALLER)
            .setResponseMarshaller(JUMBO_MARSHALLER)
            .build();
} else {
    methodDescriptor = MethodDescriptor.<BufferedData, BufferedData>newBuilder()
            .setType(MethodType.UNARY)
            .setFullMethodName(serviceName + "/" + methodName)
            .setRequestMarshaller(MARSHALLER)
            .setResponseMarshaller(MARSHALLER)
            .build();
}

```

### Ingest workflow validations

- Check in `TransactionChecker.parseAndCheck` if the transaction is bigger than `maxSignedTxnSize`
```java
// Fail fast if there are too many transaction bytes
final var maxSize = jumboTxnEnabled? maxJumboTxnSize : maxSignedTxnSize;
if (buffer.length() > maxSize) {
	throw new PreCheckException(TRANSACTION_OVERSIZE);
}
```
- Inside the `check` method, add a couple of additional checks:
    - Check if the feature flag is enabled and the txn is bigger than the `maxSignedTxnSize` then the txn type must be `EthereumTransaction`.

- Additionally, check after Ethereum hydrate is done, if `ethereumCallData` field is up to 128KB

### Throttles

Implement a **byte limit throttle** using a similar structure like the`GasLimitBucketThrottle`e.g.:

```java
public class ByteLimitJumboDeterministicThrottle implements CongestibleThrottle {
    private static final String THROTTLE_NAME = "JumboThrottle";
    private final ByteLimitBucketThrottle delegate;
    private Timestamp lastDecisionTime;
    private final long capacity;
}
```

This throttle should be integrated into `ThrottleAccumulator`, and the `shouldThrottleEthTxn()` method will be extended to enforce throttling based on a configurable **max bytes per second** limit

### Fees(TBD)

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
