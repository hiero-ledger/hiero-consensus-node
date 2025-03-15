# Network and Freeze Service

The Network and Freeze Services are responsible for managing network operations and freeze
transactions respectively. When the Hedera network is entering a maintenance window for a software upgrade
the Freeze service is utilized to facilitate the upgrade process.

### Table of Contents

- [Architecture Overview](#Architecture-Overview)
- [Protobuf Definitions](#Protobuf-Definitions)
  - [Network Service](#Network-Service)
    - [NetworkGetVersionInfoQuery](#NetworkGetVersionInfoQuery)
    - [NetworkGetExecutionTimeQuery](#NetworkGetExecutionTimeQuery)
    - [GetAccountDetailsQuery](#GetAccountDetailsQuery)
    - [TransactionGetReceiptQuery](#TransactionGetReceiptQuery)
    - [TransactionGetRecordQuery](#TransactionGetRecordQuery)
  - [Freeze Service](#Freeze-Service)
    - [FreezeTransactionBody](#FreezeTransactionBody)
    - [FreezeType](#FreezeType)
- [Handlers](#Handlers)
  - [Network Service Handlers](#Network-Service-Handlers)
    - [NetworkGetAccountDetailsHandler](#NetworkGetAccountDetailsHandler)
    - [NetworkGetExecutionTimeHandler](#NetworkGetExecutionTimeHandler)
    - [NetworkGetVersionInfoHandler](#NetworkGetVersionInfoHandler)
    - [NetworkTransactionGetReceiptHandler](#NetworkTransactionGetReceiptHandler)
    - [NetworkTransactionGetRecordHandler](#NetworkTransactionGetRecordHandler)
  - [Freeze Service Handlers](#Freeze-Service-Handlers)
    - [FreezeHandler](#FreezeHandler)
- [Network Response Messages](#Network-Response-Messages)

## Architecture Overview

The Network and Freeze Services are designed to handle transactions and queries related to network operations and
freeze transactions respectively. They provide a set of operations that allow users to retrieve network information,
execute network operations, and manage freeze transactions. The main components of the Network and Freeze Services are:

1. `Protobuf Definitions`: These are used to define the structure of our transactions and queries. They ensure that
   the data sent between the client and the server is structured and typed.

2. `Handlers`: These are responsible for executing the transactions and queries. Each type of transaction or query
   has its own handler. Handlers interact with the Network and Freeze Services to perform the required operations.

3. `Network and Freeze Services`: These are interfaces that define methods for interacting with the network and
   managing freeze transactions. They provide the functionality for the various operations that can be performed.

4. `NetworkAdminInjectionModule`: This is a Dagger module that provides dependency injection for the Network and
   Freeze Services. It ensures that the correct implementations of interfaces are used at runtime.

The Network and Freeze Services are designed to be stateless, meaning that they do not store any
client-specific data between requests.

## Protobuf Definitions

Protobuf, or Protocol Buffers, is a method of serializing structured
data. Here are some of the Protobuf definitions used in the Network & Freeze Service's:

### Network Service

The Network Service is defined in the `network_service.proto` file. It includes the following RPC methods:

- `getVersionInfo`: Retrieves the active versions of Hedera Services and HAPI proto.
- `getExecutionTime`: Retrieves the time in nanoseconds spent in `handleTransaction` for one or more TransactionIDs.
- `uncheckedSubmit`: Submits a "wrapped" transaction to the network, skipping its standard prechecks.
- `getAccountDetails`: Get all the information about an account, including balance and allowances.

#### NetworkGetVersionInfoQuery

`NetworkGetVersionInfoQuery` is a protobuf message type used to retrieve the active versions of Hedera Services
and HAPI proto. It is defined in the `network_service.proto` file. This query does not require any specific
fields to be set as it is a request for information.

The `NetworkGetVersionInfoQuery` message has the following field:

- `header`: This field is used to set the standard info sent from client to node including the signed payment,
  and what kind of response is requested (cost, state proof, both, or neither).

Please note that this query is used to retrieve the active versions of Hedera Services and HAPI proto.
The response to this query will contain the version information.

#### NetworkGetExecutionTimeQuery

`NetworkGetExecutionTimeQuery` is a protobuf message type defined in the
`network_get_execution_time.proto` file. It is used to retrieve the time in nanoseconds spent
in `handleTransaction` for one or more TransactionIDs. This information is useful for performance
analysis and debugging purposes.

The `NetworkGetExecutionTimeQuery` message has the following fields:

- `header`: This field is used to set the standard info sent from client to node including the signed payment,
  and what kind of response is requested (cost, state proof, both, or neither).

- `transaction_ids`: This field is used to set one or more TransactionIDs for which the execution time is to be
  retrieved. It is a required field.

Please note that the execution times are kept in-memory and are available only for a limited number of recent
transactions. The number of execution times that are kept in-memory depends on the value of the
node-local property `stats.executionTimesToTrack`.

#### GetAccountDetailsQuery

`GetAccountDetailsQuery` is a protobuf message type defined in the `network_service.proto` file.
It is used to retrieve all the information about an account, including balance and allowances.

The `GetAccountDetailsQuery` message has the following fields:

- `header`: This field is used to set the standard info sent from client to node including the signed
  payment, and what kind of response is requested (cost, state proof, both, or neither).

- `account_id`: This field is used to set the ID of the account for which the details are to be
  retrieved. It is a required field.

#### TransactionGetReceiptQuery

`TransactionGetReceiptQuery` is a protobuf message type defined in the `transaction_receipt.proto` file.
It is used to retrieve the receipt of a transaction, which includes the status of the transaction,
whether it succeeded or failed, and additional information depending on the transaction type.

The `TransactionGetReceiptQuery` message has the following fields:

- `header`: This field is used to set the standard info sent from client to node including the signed payment,
  and what kind of response is requested (cost, state proof, both, or neither).

- `transactionID`: This field is used to set the ID of the transaction for which the receipt is to be retrieved.
  It is a required field.

Please note that the receipt of a transaction is only available after the transaction has reached consensus.
The receipt includes the status of the transaction, which indicates whether the transaction succeeded or failed.
For some types of transactions, the receipt may also include additional information.

#### TransactionGetRecordQuery

`TransactionGetRecordQuery` is a protobuf message type defined in the `transaction_record.proto` file. It is used to
retrieve the record of a transaction, which includes the receipt of the transaction, the consensus timestamp, the
transaction hash, and other information.

The `TransactionGetRecordQuery` message has the following fields:

- `header`: This field is used to set the standard info sent from client to node including the signed payment, and
  what kind of response is requested (cost, state proof, both, or neither).

- `transactionID`: This field is used to set the ID of the transaction for which the record is to be retrieved. It
  is a required field.

Please note that the record of a transaction is only available after the transaction has reached consensus. The record
includes the receipt of the transaction, which indicates whether the transaction succeeded or failed, the consensus
timestamp at which the transaction was processed, the transaction hash, and other information. For some types of
transactions, the record may also include additional information.

### Freeze Service

The Freeze Service is defined in the `freeze_service.proto` file. It includes the following RPC method:

- `freeze`: Freezes the nodes by submitting the transaction.

### FreezeTransactionBody

`FreezeTransactionBody` is a protobuf message type defined in the `freeze.proto` file. It is used to set the consensus time at which the Hedera network should stop creating events and accepting transactions, thereby entering a maintenance window.

The `FreezeTransactionBody` message has the following fields:

- `startHour` and `startMin`: These fields are deprecated and rejected by nodes. They were previously used to specify the start hour and minute (in UTC time) for the maintenance window.

- `endHour` and `endMin`: These fields are also deprecated and rejected by nodes. They were previously used to specify the end hour and minute (in UTC time) for the maintenance window.

- `update_file`: This optional field, if set, specifies the file whose contents should be used for a network software update during the maintenance window.

- `file_hash`: This optional field, if set, specifies the expected hash of the contents of the update file. This is used to verify the update.

- `start_time`: This field is used to set the consensus time at which the maintenance window should begin. It must reference a future time.

- `freeze_type`: This field is used to specify the type of network freeze or upgrade operation to perform. It is an enum of type `FreezeType`.

Please note that the `startHour`, `startMin`, `endHour`, and `endMin` fields are deprecated and any values
specified for these fields will be ignored. The `start_time` field must be provided and must reference a
future time. The `update_file` and `file_hash` fields are optional and are used for network software updates
during the maintenance window.

#### FreezeType

`FreezeType` is a protobuf enum defined in the `freeze_type.proto` file. It is used to specify the type of network freeze or upgrade operation to be performed. It has several values:

- `UNKNOWN_FREEZE_TYPE`: An (invalid) default value for this enum, to ensure the client explicitly sets the intended type of freeze transaction.
- `FREEZE_ONLY`: Freezes the network at the specified time. The start_time field must be provided and must reference a future time.
- `PREPARE_UPGRADE`: A non-freezing operation that initiates network wide preparation in advance of a scheduled freeze upgrade.
- `FREEZE_UPGRADE`: Freezes the network at the specified time and performs the previously prepared automatic upgrade across the entire network.
- `FREEZE_ABORT`: Aborts a pending network freeze operation.
- `TELEMETRY_UPGRADE`: Performs an immediate upgrade on auxiliary services and containers providing telemetry/metrics. Does not impact network operations.

## Handlers

Handlers are responsible for executing the transactions and queries.
Each type of transaction or query has its own handler.
All the Handlers either implement the ```TransactionHandler``` interface and provide implementations of
pureChecks, preHandle, handle, and calculateFees methods; or ultimately implement the ```QueryHandler``` interface
through their inheritance structure. If the latter, they provide an implementation of the ```findResponse``` method.

### Network Service Handlers

Network Service Handlers are responsible for executing the transactions and queries
related to the Network Service. Each type of transaction or query has its own
handler.

#### NetworkGetAccountDetailsHandler

`NetworkGetAccountDetailsHandler` is responsible for handling `GetAccountDetailsQuery`. It retrieves all the
information about an account, including balance and allowances.

#### NetworkGetExecutionTimeHandler

`NetworkGetExecutionTimeHandler` is responsible for handling `NetworkGetExecutionTimeQuery`. It retrieves the time in
nanoseconds spent in `handleTransaction` for one or more TransactionIDs.

#### NetworkGetVersionInfoHandler

`NetworkGetVersionInfoHandler` is responsible for handling `NetworkGetVersionInfoQuery`. It retrieves the active
versions of Hedera Services and HAPI proto.

#### NetworkTransactionGetReceiptHandler

`NetworkTransactionGetReceiptHandler` is responsible for handling `TransactionGetReceiptQuery`. It retrieves the
receipt of a transaction, which includes the status of the transaction, whether it succeeded or failed, and
additional information depending on the transaction type.

#### NetworkTransactionGetRecordHandler

`NetworkTransactionGetRecordHandler` is responsible for handling `TransactionGetRecordQuery`. It retrieves the
record of a transaction, which includes the receipt of the transaction, the consensus timestamp, the transaction
hash, and other information.

### Freeze Service Handlers

Freeze Service Handlers are responsible for executing the transactions related to the Freeze Service.
Each type of transaction has its own handler.

#### FreezeHandler

`FreezeHandler` is a class responsible for handling `FreezeTransactionBody`.
It may set the consensus time at which the Hedera network should stop creating events and accepting transactions,
thereby entering a maintenance window. This class is defined in the `FreezeHandler.java` file.

The `handle` method in `FreezeHandler` is responsible for executing the logic based on the `FreezeType` specified in the `FreezeTransactionBody`. The `FreezeType` is an enum that specifies the type of network freeze or upgrade operation to be performed.

The `handle` method contains a switch statement that executes different logic based on the `FreezeType`:

- `PREPARE_UPGRADE`: If the `FreezeType` is `PREPARE_UPGRADE`, the method prepares for a network upgrade. It logs the preparation process and extracts the upgrade file.

- `FREEZE_UPGRADE`: If the `FreezeType` is `FREEZE_UPGRADE`, the method schedules a freeze upgrade at the specified start time.

- `FREEZE_ABORT`: If the `FreezeType` is `FREEZE_ABORT`, the method logs the abort process.

- `TELEMETRY_UPGRADE`: If the `FreezeType` is `TELEMETRY_UPGRADE`, the method extracts the telemetry upgrade and schedules it at the specified start time.

- `FREEZE_ONLY`: If the `FreezeType` is `FREEZE_ONLY`, the method schedules a freeze at the specified start time.

- `UNKNOWN_FREEZE_TYPE`: If the `FreezeType` is `UNKNOWN_FREEZE_TYPE`, the method throws a `WorkflowException` with `ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY`.

## Network Response Messages

Specific network response messages (```ResponseCodeEnum```) are wrapped by ```WorkflowException```
and the codes relevant to the Network and Freeze Service are:

- `INVALID_FREEZE_TRANSACTION_BODY`: FreezeTransactionBody is invalid
- `FREEZE_TRANSACTION_BODY_NOT_FOUND`: FreezeTransactionBody does not exist
- `FREEZE_UPDATE_FILE_DOES_NOT_EXIST`: The update file in a freeze transaction body must exist.
- `FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH`: The hash of the update file in a freeze transaction body must match the in-memory hash.
- `NO_FREEZE_IS_SCHEDULED`: A FREEZE_ABORT transaction was handled with no scheduled freeze.
- `UPDATE_FILE_HASH_CHANGED_SINCE_PREPARE_UPGRADE`: The update file hash when handling a FREEZE_UPGRADE transaction differs from the file hash at the time of handling the PREPARE_UPGRADE transaction.
- `FREEZE_START_TIME_MUST_BE_FUTURE`: The given freeze start time was in the (consensus) past.
- `PREPARED_UPDATE_FILE_IS_IMMUTABLE`: The prepared update file cannot be updated or appended until either the upgrade has been completed, or a FREEZE_ABORT has been handled.
- `FREEZE_ALREADY_SCHEDULED`: Once a freeze is scheduled, it must be aborted before any other type of freeze can be performed.
- `FREEZE_UPGRADE_IN_PROGRESS`: If an NMT upgrade has been prepared, the following operation must be a FREEZE_UPGRADE. (To issue a FREEZE_ONLY, submit a FREEZE_ABORT first.)
- `UPDATE_FILE_ID_DOES_NOT_MATCH_PREPARED`: If an NMT upgrade has been prepared, the subsequent FREEZE_UPGRADE transaction must confirm the id of the file to be used in the upgrade.
- `UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED`: If an NMT upgrade has been prepared, the subsequent FREEZE_UPGRADE transaction must confirm the hash of the file to be used in the upgrade.
- `NO_PREPARED_UPGRADE_EXISTS`: If an NMT upgrade has been prepared, the subsequent FREEZE_UPGRADE transaction must confirm the id of the file to be used in the upgrade.
