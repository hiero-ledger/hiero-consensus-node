# Schedule Service System Contract

## Purpose

[HIP-755](https://hips.hedera.com/hip/hip-755) defines a new system contract that allows accounts and contracts to interact with a new schedule service system contract.
This document will define the architecture and implementation of this functionality.

## Goals

Describe the implementation of the schedule service system contract functionality in the Hedera Smart Contract Service in
sufficient detail to allow for the implementation of the feature by any engineer on the smart contracts team.

## Non Goals

A companion [HIP-756](https://hips.hedera.com/hip/hip-756) which describes how schedule signed transactions can be created
via the smart contract service will be discussed in a separate document.

## Architecture and Implementation

The architechture for the schedule service system contract follows the existing framework defined for handling calls to the Hedera Token Service system contract.
A new system contract address, `0x16b`, will be added to the system contract address map and during transaction processing in
the `CustomMessageCallProcessor` class, if the recipient address of the current call frame is determined to be the contract address for the ScheduleService, processing
will be redirected to a new class called `HSSSystemContract`.

`HSSSystemContract` will share as many of the super classes and interfaces with `HTSSystemContract` as possible to reduce code duplication.
In the same way that system contract calls are processed in the `HTSSystemContract` class, the `HSSSystemContract` class will create an instance of the `HssCallAttempt` class from the input bytes and the current message frame to encapsulate the call.
It will then look through a map of `Translator` classes to determine which translator will be responsible for processing the call by attempting to match the call's function signature to a signature known by the translator.
If there is a matching function selector, the request will be routed to the `HssCall` class which will dispatch the transaction to the Schedule Service Module for processing
or in the case of view functions will look up the information needed from the apprioprate store and return the results.

### Proxy Contract

The Schedule Service System Contract will have an associated proxy contract as some of the functions will be callable
directly from an EOA.  A new descendant of the `HederaEvmAccount` called `ScheduleEvmAccount` will be created to supply
contract byte code for the proxy contract if the target of the top level call is a schedule transaction id and if the function selector
matches a supported function.

### Supported Functions

The supported functions callable from contracts are as follows:

|     Hash     |                               Selector                               |                                                                 Description                                                                  |
|--------------|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `0xf0637961` | `authorizeSchedule(address) external returns (int64 responseCode)`   | Sign the schedule transaction whose id is `address` with a contract key containing the calling contract id                                   |
| `0x358eeb03` | `signSchedule(address, bytes) external returns (int64 responseCode)` | Sign the schedule transaction whose id is `address` with the keys derived from signatures encoded as a protobuf signatureMap give by `bytes` |

The supported functions callable from an EOA are as follows:

|     Hash     |                        Selector                        |                                                                                                                 Description                                                                                                                 |
|--------------|--------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0x06d15889` | `signSchedule() external returns (int64 responseCode)` | Signs the targeted schedule transaction with the sender's keys.  For `EthereumTransactions` the embedded ECDSA key will be used.  For `ContractCall` transactions, the keys derived from the signature map of the transaction will be used. |

In order to validate the signatures in signature map for the `signSchedule(address, bytes)` function call,
a message has to be agreed upon.  The most logical message would be the schedule transaction ID.

The HIP mentions two additional functions `getScheduledTransactionInfo(address)` and `getScheduledTransactionInfo()` which are not included in the list above.
It has been decided that these will only be implemented at a later time if necessary as the only useful information
is the `scheduleID` which is needed in order to make the calls in the first place.

### Phased Implementation

0. The implementation of the infrastructure to support the Schedule Service System Contract must be implemented before any support for the functions enumerated above can be added.
   This includes the code for the `HSSSystemContract` class, the `HssCallAttempt` class, the `HssCall` classes, the `ScheduleServiceEntity` class, and the `Translator` class.
   In addition all of the code to wire the processing of the transaction by the new system contract must be added.

The functions of the new system contract can be implemented in a phased approach in order to prioritize unblocking the implementation of the most common [HIP-756](https://hips.hedera.com/hip/hip-756) scenarios.
The following the use cases can be implemented in the following order:
1. The ability to sign a schedule transaction from an EOA using the `signSchedule` function via a `ContractCall` transaction.
2. The ability to sign a schedule transaction from an EOA using the `signSchedule` function via an `EthereumTransactions` transaction.
3. The ability to sign a schedule transaction from a contract using the `authorizeSchedule` function.
4. The ability to sign a schedule transaction from a contract using the `signSchedule` function.

### Dispatching to the Schedule Service

In order to provide the key for authorizing to a schedule transaction, the `HandleContext` must contain a `KeyVerifier` which
returns a set of keys to be used for authorization.  Currently, there is no mechanism in dispatching a child transaction that
allow for the provision of this set of Keys.  Such a mechanism will need to be added to the App service `DispatchHandleContext` implementation class
as a prerequisite before dispatching to the Schedule Service can be implemented.

### Error Handling

As with other system contract calls, errors will result in a descriptive response code to indicate the issue.
The following response codes wills be utilized:

### Gas Calculation

As with other system contract calls, gas will be calculated by converting the canonical fee for a SCHEDULE_SIGN transaction
multiplied by the number of signatures to sign and adding a 20% markup.

### Infinite Recursion

A special case to consider is the possibility of infinite recursion when a contract calls the Schedule Service which in turn calls the same contract.
There should already exist protection from such a scenario because schedule transactions cannot be a smart contract transaction.  In addition, there is
protection via the maximum allowed child transaction depth.  Nevertheless, test cases to ensure that infinite recursion is not possible should be added.

## Testing

### Positive Tests

The base cases to be tested will be based on the sequence diagram found in [HIP-755](https://hips.hedera.com/hip/hip-755).
In each of these cases, the key provided will trigger the execution of the schedule service.
The diagram contains the following four cases:

1. A contract calls the Schedule Service to authorize a schedule transaction by the contract.
2. A contract calls the Schedule Service to sign a schedule transaction with the keys derived from the signatures passed
   as a protobuf signatureMap.
3. An EOA calls the Schedule Service to sign a schedule transaction using `EthereumTransactions`.
4. An EOA calls the Schedule Service to sign a schedule transaction using `ContractCall` transactions.

### Negative Tests

1. Test the same use cases as above but with a signature that does not fulfill the requirement to trigger the schedule transaction.
2. Test to ensure that the Schedule Service does not allow for infinite recursion.
3. Test to ensure that invalid signatures are not accepted as keys to sign schedule transaction.
