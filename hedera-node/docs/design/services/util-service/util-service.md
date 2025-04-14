# Util Service

The Util Service is a service that handles utility operations. This service provides functionality
to generate pseudorandom numbers and to execute a group of transactions together as an atomic batch.

### Table of Contents

- [Util Service](#Util-Service)
- [Protobuf Definitions](#Protobuf-Definitions)
  - [UtilPrngTransactionBody](#UtilPrngTransactionBody)
  - [AtomicBatchTransactionBody](#AtomicBatchTransactionBody)
- [Handlers](#Handlers)
- [Configuration](#Configuration)

## Protobuf Definitions

Protobuf, or Protocol Buffers, is a method of serializing structured data.
The Util Service uses it to define the structure of our transactions. Here are some of
the Protobuf definitions used in the Util Service:

- ```util_service.proto```: This file defines the UtilService which includes the prng RPC
  for generating pseudorandom numbers.
- ```util_prng.proto```: This file defines the UtilPrngTransactionBody message which is
  used in the prng RPC. It includes a range field which, if provided and is positive,
  returns a 32-bit pseudorandom number from the given range in the transaction record.
  If not set or set to zero, it will return a 384-bit pseudorandom number in the record.
- ```transaction.proto```: In addition to other definitions, this file defines the
  AtomicBatchTransactionBody message which is used in the atomicBatch RPC.
  AtomicBatchTransactionBody includes a list of transactions that are grouped together
  into an atomic batch.

### UtilPrngTransactionBody

The `UtilPrngTransactionBody` message is a crucial part of the `prng` RPC in the Util Service.
It is defined in the `util_prng.proto` file and is used to specify the range for the pseudorandom
number generation.

```
message UtilPrngTransactionBody {
    int32 range = 1;
}
```

The `UtilPrngTransactionBody` message contains a single field, `range`. This field is used to determine the range of the pseudorandom number that will be generated.

- If `range` is provided and is positive, the UtilPrng Service will return a 32-bit pseudorandom number from the given range in the transaction record.
- If `range` is not set or set to zero, the UtilPrng Service will return a 384-bit pseudorandom number in the transaction record.

This message is used in the `prng` RPC of the `UtilService` defined in `util_service.proto`.
The `prng` RPC is responsible for generating pseudorandom numbers, and
the `UtilPrngTransactionBody` message provides the necessary input for this operation.

### AtomicBatchTransactionBody

The `AtomicBatchTransactionBody` message is used to group transactions together into an atomic batch.
It is defined in the `transaction.proto` file and contains a list of transactions that are to be executed
together. The batch of transactions
is either executed successfully or not executed at all, ensuring that the batch passes the
ACID test (Atomicity, Consistency, Isolation, Durability).

```
message AtomicBatchTransactionBody {
    repeated Transaction transactions = 1;
}
```

The `AtomicBatchTransactionBody` message contains a single field, `transactions`.
This field is a list of transactions that are to be executed
together as part of the atomic batch.
The transactions are executed in the order they appear in the list.
If an inner transaction fails, preceding transactions that succeeded
will still incur fees, even though their effects are not committed.
Each inner transaction has its own TransactionBody, is individually signed and has its own payer.

## Handlers

Handlers are responsible for executing the transactions. Each type of transaction has its
own handler. All the Handlers implement the TransactionHandler interface and provide
implementations of pureChecks, preHandle, handle, and calculateFees methods.

- ```UtilHandlers.java```: This class includes a prngHandler which is an instance of
  UtilPrngHandler and an atomicBatchHandler which is an instance of AtomicBatchHandler.

- ```UtilPrngHandler.java```: This class is a TransactionHandler for handling UTIL_PRNG
  transactions. It uses the n-3 running hash to generate a pseudo-random number.
  The n-3 running hash is updated and maintained by the application, based on the
  record files generated based on preceding transactions. In this way, the number is
  both essentially unpredictable and deterministic.

- ```AtomicBatchHandler.java```: This class is a TransactionHandler for handling ATOMIC_BATCH
  transactions, which contain a list of inner transactions. All transactions in the batch are
  either executed successfully or not executed at all.

The AtomicBatchHandler is responsible for ensuring that the following conditions are met:
- The list of inner transactions is not empty.
- The list of inner transactions does not contain duplicates. Duplicates are defined as transactions
with the same transactionId.
- The number of inner transactions in the batch does not exceed the limit as configured in the network.
- The type of each inner transaction in the batch is not in the blacklist as configured in the network.
- The batchKey field is set to a valid key for each inner transaction in the batch.
- The nodeAccountId is set to 0.0.0 for each inner transaction in the batch.
- The batchKey field is not set for the AtomicBatch outer transaction.
- The AtomicBatch outer transaction is signed by each inner transaction's batchKey,
i.e. if inner transaction X sets `batchKey` X and inner transaction Y sets `batchKey` Y
then their containing AtomicBatch transaction must be signed by both `batchKey` X and `batchKey` Y private keys.

## Configuration

- ```AtomicBatchConfig``` is a configuration class used in the Hedera Hashgraph network.
  This class is used to configure the behavior of the ```AtomicBatchHandler```. The ```AtomicBatchConfig```
  class is a record that contains three properties:
  - ```isEnabled```: This property is a boolean that determines whether the ```AtomicBatchHandler```
    allows atomic batch transactions to proceed. If ```isEnabled``` is set to true, the ```AtomicBatchHandler```
    will allow batch transactions. If ```isEnabled``` is set to false, the ```AtomicBatchHandler```
    will not allow them.
  - ```maxNumberOfTransactions``` This number is a long that determines the maximum number of transactions
    allowed in an atomic batch. If the number of transactions in a batch exceeds this limit, the batch will be rejected.
  - ```blacklist``` This property is a HederaFunctionalitySet that contains a list of transaction types
    that are not allowed in an atomic batch. If an inner transaction type is in the blacklist, the batch will be rejected.

## Network Response Messages

Specific network response messages (```ResponseCodeEnum```) are wrapped by ```HandleException``` or ```PreCheckException```.
The response codes relevant to the Util Service are:
- `BATCH_LIST_EMPTY`: The list of batch transactions is empty.
- `BATCH_LIST_CONTAINS_DUPLICATES`: The list of batch transactions contains duplicated transactions.
- `BATCH_SIZE_LIMIT_EXCEEDED`: The number of transactions in the batch exceeds the limit as configured in ```maxNumberOfTransactions```
- `BATCH_TRANSACTION_IN_BLACKLIST`: The type of one or more transactions in the batch is in the configuration blacklist.
- `INNER_TRANSACTION_FAILED`: An atomic batch inner transaction failed.
- `INVALID_BATCH_KEY`: An inner transaction in an atomic batch has an invalid batch key.
- `INVALID_NODE_ACCOUNT_ID`: An inner transaction in an atomic batch has any account ID other than 0.0.0.
- `INVALID_PRNG_RANGE`: The range provided in the UtilPrngTransactionBody is negative.
- `MISSING_BATCH_KEY`: An inner transaction in an atomic batch is missing a batch key.
- `NOT_SUPPORTED`: The requested operation is not supported by the Util Service.
