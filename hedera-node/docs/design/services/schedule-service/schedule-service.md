# Schedule Service

The Schedule Service processes transactions that create a schedulable entity on the network.
CryptoTransfer,ConsensusSubmitMessage,TokenBurn,TokenMint, and CryptoApproveAllowance transactions
are the transaction types that can be scheduled.
Additional schedulable transactions will be added in future releases.

### Table of Contents

- [Schedule Service](#Schedule-Service)
- [Table of Contents](#Table-of-Contents)
- [Architecture Overview](#Architecture-Overview)
- [Transaction and Queries for the Schedule Service](#Transaction-and-Queries-for-the-Schedule-Service)
- [Protobuf Definitions](#Protobuf-Definitions)
  - [Schedule ID](#Schedule-ID)
  - [Transaction Body's](#Transaction-Bodys)
    - [SchedulableTransactionBody](#SchedulableTransactionBody)
    - [ScheduleCreateTransactionBody](#ScheduleCreateTransactionBody)
    - [ScheduleSignTransactionBody](#ScheduleSignTransactionBody)
    - [ScheduleDeleteTransactionBody](#ScheduleDeleteTransactionBody)
    - [ScheduleGetInfoQuery](#ScheduleGetInfoQuery)
- [Handlers](#Handlers)
  - [pureChecks](#pureChecks)
  - [preHandle](#preHandle)
  - [handle](#handle)
  - [calculateFees](#calculateFees)
- [Network Response Messages](#Network-Response-Messages)

## Architecture Overview

The Schedule Service is designed to handle transactions and queries related to schedulable entities on the
network. It consists of several components that work together to create, sign, delete, and retrieve information
about schedules. The key components of the Schedule Service are:

1. `Protobuf Definitions`: These are used to define the structure of our transactions and queries. They ensure that the
   data sent between the client and the server is structured and typed.

2. `Handlers`: These are responsible for executing the transactions and queries
   Each type of transaction or query has its own handler.
   Handlers interact with the Schedule Readable and Writeable Stores to perform the required operations.

3. `Schedule Stores`: These are responsible for storing data related to the schedulable entities.
   The ```ReadableScheduleStore``` and  ```ReadableScheduleStore``` are interfaces that defines the operations
   that can be performed on the schedules regarding state.

4. `ScheduleServiceInjectionModule`: This is a Dagger module that provides dependency injection for the
   Schedule Service. It ensures that the correct implementations of interfaces are used at runtime.

The Schedule Service is designed to be stateless, meaning that it does not store any client-specific
data between requests.

## Transaction and Queries for the Schedule Service

Transactions and queries are the means of interacting with the
Schedule Service. They define the actions that can be performed on scheduable entities,
such as creating a new schedule, signing a schedule, deleting a schedule,
or retrieving information about a schedule.

## Protobuf Definitions

Protobuf, or Protocol Buffers, is a method of serializing structured
data. The Schedule Service uses it to define the structure of our transactions and queries.
Here are some of the Protobuf definitions used in the Schedule Service:

### Schedule ID

The entity ID of a schedule transaction.

A ```ScheduleId``` is composed of a <shardNum>.<realmNum>.<scheduleNum> (eg. 0.0.10).

<tt>shardNum</tt> represents the shard number (shardId). It will default to 0 today, as Hedera only performs in one shard.

<tt>realmNum</tt> represents the realm number (realmId). It will default to 0 today, as realms are not yet supported.

<tt>scheduleNum</tt> represents the schedule number (scheduleId)

Together these values make up your ScheduleId. When a ScheduleId is requested in a field, be sure enter all three values.

### Transaction Body's & Queries

These are the specific types of transactions that can be performed on schedulable entities.
Each transaction body corresponds to a specific operation,
such as creating a new schedule (```ScheduleCreateTransactionBody```),
signing a schedule (```ScheduleSignTransactionBody```),
deleting a schedule (```ScheduleDeleteTransactionBody```),
or retrieving information about a schedule (```ScheduleGetInfoQuery```).

#### SchedulableTransactionBody

```SchedulableTransactionBody``` is a protobuf message used in the
Schedule Service to define a transaction that can be scheduled for
execution. This message is part of the Schedule Service which
processes transactions that can be scheduled. Note that the global/dynamic
system property
* <tt>scheduling.whitelist</tt> controls which transaction types may be
scheduled.

Here are the key fields of ```SchedulableTransactionBody```:
- ```transactionFee``` : This field is of type uint64 and represents the maximum transaction fee the client is willing to pay.
- ```memo```: This is an optional field of type string that can hold a UTF-8 encoded
string of no more than 100 bytes which does not contain the zero byte.
- ```data```: This field is a oneof type which means it can hold one of many types of
transactions. The types of transactions that can be scheduled are arranged by
service in roughly lexicographical order. The field ordinals are non-sequential,
and a result of the historical order of implementation. The choices here include
various types of transactions such as ContractCallTransactionBody,
ContractCreateTransactionBody, etc.

#### ScheduleCreateTransactionBody

```ScheduleCreateTransactionBody``` is a message used to create a new schedule
entity in the network's action queue. Upon ```SUCCESS```, the receipt contains the
```ScheduleID``` of the created schedule.
A schedule entity includes a scheduledTransactionBody to be executed.
When the schedule has collected enough signing Ed25519 keys to satisfy the
schedule's signing requirements, the schedule can be executed.

The ```ScheduleCreateTransactionBody``` message includes the following fields:
- ```scheduledTransactionBody```: The scheduled transaction.
- ```memo```: An optional memo with a UTF-8 encoding of no more than 100 bytes which does not contain the zero byte.
- ```adminKey```: An optional key which can be used to sign a ScheduleDelete and remove the schedule.
- ```payerAccountID```: An optional id of the account to be charged the service fee for the scheduled transaction at the consensus time that it executes (if ever); defaults to the ScheduleCreate payer if not given.
- ```expiration_time```: An optional timestamp for specifying when the transaction should be evaluated for execution and then expire. Defaults to 30 minutes after the transaction's consensus timestamp.
- ```wait_for_expiry```: When set to true, the transaction will be evaluated for execution at expiration_time instead of when all required signatures are received. Defaults to false.

The ```ScheduleCreateTransactionBody``` message is used in the
```ScheduleCreateHandler``` to handle the creation of a new schedule.
The handler is responsible for executing the transaction and interacts with the
ScheduleStore to perform the required operations.

#### ScheduleSignTransactionBody

```ScheduleSignTransactionBody``` is a message used to sign a schedule entity in the network's action queue. The signing
of a schedule is a crucial step in the lifecycle of a schedule as it determines whether the scheduled transaction
can be executed or not.

The ```ScheduleSignTransactionBody``` message includes the following fields:
- ```scheduleID```: The ID of the Scheduled Entity to be signed.

- The ```ScheduleSignTransactionBody``` message is used in the ```ScheduleSignHandler``` to handle the
  signing of a schedule. The handler is responsible for executing the transaction and interacts with the ScheduleStore to
  perform the required operations.

#### ScheduleDeleteTransactionBody

```ScheduleDeleteTransactionBody``` is a message used to delete a schedule entity in the network's action queue.

The ```ScheduleDeleteTransactionBody``` message includes the following fields:

- ```scheduleID```: The ID of the Scheduled Entity to be deleted.
  -
- The ```ScheduleDeleteTransactionBody``` message is used in the ```ScheduleDeleteHandler``` to handle the
- deletion of a schedule. The handler is responsible for executing the transaction and interacts with the
- ScheduleStore to perform the required operations.

#### ScheduleGetInfoQuery

```ScheduleGetInfoQuery``` is a message used to retrieve information about a schedule entity in the network's
action queue. This query allows users to fetch details about a specific scheduled transaction using its
ScheduleID.

The ```ScheduleGetInfoQuery``` message includes the following fields:
- ```header```: Standard info sent from client to node, including the signed payment, and what kind of response
is requested (cost, state proof, both, or neither).
- ```scheduleID```: The ID of the schedule entity whose information is requested.

The ```ScheduleGetInfoQuery``` message is used in the ```ScheduleGetInfoQueryHandler``` to handle the retrieval of schedule information.
The handler is responsible for executing the query and interacts with the ScheduleStore to perform the required
operations. The handler retrieves the schedule information from the ScheduleStore and returns the information
in the response message. The information includes the schedule's creator, payer, creation time,
transaction body, signatories, and whether it has been executed or deleted. If the schedule does not exist
or the client does not have the necessary permissions, an error is returned.

## Handlers

Handlers are responsible for executing the transactions and queries.
Each type of transaction or query has its own handler.
All the Handlers either implement the ```TransactionHandler``` interface and provide implementations of
pureChecks, preHandle, handle, and calculateFees methods; or ultimately implement the ```QueryHandler``` interface
through their inheritance structure. If the latter, they provide an implementation of the ```findResponse``` method.

### pureChecks

The ```pureChecks``` method is responsible for performing checks that are independent of state or context.
It takes a TransactionBody as an argument and throws a WorkflowException if any of the checks fail.
In the context of ```ScheduleCreateHandler```, this method checks if the transaction ID is valid and if the
scheduled transaction is valid for long term scheduling.

### preHandle

The ```preHandle``` method in ```ScheduleCreateHandler``` and other handlers is called during the pre-handle workflow.
It determines the signatures needed for creating a schedule. It takes a PreHandleContext as an argument,
which collects all information, and throws a ```WorkflowException``` if any issue happens on the pre-handle
level. This method validates the scheduleID and checks if the schedule signatures are waived.
If not, it validates and adds the required keys.

### handle

The ```handle``` method in ```ScheduleCreateHandler``` and other handlers is responsible for executing the main logic of each Handler.
For ScheduleCreateHandler, it takes a HandleContext as an argument and throws a WorkflowException if any
issue happens during the handling process. This method handles the creation of a new schedule. It validates
the schedule and its contents, and then updates the schedule with the new contents.

### calculateFees

The ```calculateFees``` method in ```ScheduleCreateHandler``` and other handlers is responsible for calculating the
fees associated with the schedule operation. It takes a FeeContext as an argument and returns a Fees object. In the
context of ```ScheduleCreateHandler``` This method calculates the fees based on the size of the data being created and
the effective lifetime of the schedule.

## Network Response Messages

Specific network response messages (```ResponseCodeEnum```) are wrapped by ```WorkflowException``` and the codes relevant to the Schedule Service are:

- ```INVALID_SCHEDULE_ID```: The Scheduled entity does not exist; or has now expired, been deleted, or been executed
- ```SCHEDULE_IS_IMMUTABLE```: The Scheduled entity cannot be modified. Admin key was not set during the creation of the Scheduled entity.
- ```INVALID_SCHEDULE_PAYER_ID```: The provided Scheduled Payer does not exist
- ```INVALID_SCHEDULE_ACCOUNT_ID```: The Schedule Create Transaction TransactionID account does not exist
- ```NO_NEW_VALID_SIGNATURES```: The provided sig map did not contain any new valid signatures from required signers of the scheduled transaction
- ```UNRESOLVABLE_REQUIRED_SIGNERS```: The required signers for a scheduled transaction cannot be resolved, for example because they do not exist or have been deleted
- ```UNPARSEABLE_SCHEDULED_TRANSACTION```: The bytes allegedly representing a transaction to be scheduled could not be parsed
- ```UNSCHEDULABLE_TRANSACTION```: ScheduleCreate and ScheduleSign transactions cannot be scheduled
- ```SOME_SIGNATURES_WERE_INVALID```: At least one of the signatures in the provided sig map did not represent a valid signature for any required signer
- ```TRANSACTION_ID_FIELD_NOT_ALLOWED```: The scheduled and nonce fields in the TransactionID may not be set in a top-level transaction
- ```IDENTICAL_SCHEDULE_ALREADY_CREATED```: A schedule already exists with the same identifying fields of an attempted ScheduleCreate (that is, all fields other than scheduledPayerAccountID)
- ```SCHEDULE_ALREADY_DELETED```: A schedule being signed or deleted has already been deleted
- ```SCHEDULE_PENDING_EXPIRATION```: A schedule being signed or deleted has passed it's expiration date and is pending execution if needed and then expiration
- ```SCHEDULE_FUTURE_GAS_LIMIT_EXCEEDED```: The scheduled transaction could not be created because it would cause the gas limit to be violated on the specified expiration time
- ```SCHEDULE_FUTURE_THROTTLE_EXCEEDED```: The scheduled transaction could not be created because it would cause throttles to be violated on the specified expiration time
- ```SCHEDULE_EXPIRATION_TIME_MUST_BE_HIGHER_THAN_CONSENSUS_TIME```: The scheduled transaction could not be created because it's expiration_time was less than or equal to the consensus time
- ```SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE```: The scheduled transaction could not be created because it's expiration time was too far in the future
