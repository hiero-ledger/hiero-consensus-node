# Consensus Service

### Table of Contents

- [Key Features of the Consensus Service](#Key-Features-of-the-Consensus-Service)
- [How Consensus Service Works](#How-Consensus-Service-Works)
- [Architecture Overview](#Architecture-Overview)
- [Transaction and Queries for the Consensus Service](#Transaction-and-Queries-for-the-Consensus-Service)
- [Protobuf Definitions](#Protobuf-Definitions)
  - [Transaction Body's](#Transaction-Bodys)
    - [ConsensusCreateTopicTransactionBody](#ConsensusCreateTopicTransactionBody)
    - [ConsensusUpdateTopicTransactionBody](#ConsensusUpdateTopicTransactionBody)
    - [ConsensusDeleteTopicTransactionBody](#ConsensusDeleteTopicTransactionBody)
    - [ConsensusGetContentsTransactionBody](#ConsensusGetContentsTransactionBody)
    - [ConsensusGetInfoQuery](#ConsensusGetInfoQuery)
    - [ConsensusSubmitMessageTransactionBody](#ConsensusSubmitMessageTransactionBody)
- [Handlers](#Handlers)
  - [ConsensusCreateHandler](#ConsensusCreateHandler)
  - [ConsensusUpdateHandler](#ConsensusUpdateHandler)
  - [ConsensusAppendHandler](#ConsensusAppendHandler)
  - [ConsensusDeleteHandler](#ConsensusDeleteHandler)
  - [ConsensusSystemDeleteHandler](#ConsensusSystemDeleteHandler)
  - [ConsensusSystemUndeleteHandler](#ConsensusSystemUndeleteHandler)
  - [ConsensusGetContentsHandler](#ConsensusGetContentsHandler)
  - [ConsensusGetInfoHandler](#ConsensusGetInfoHandler)
- [Network Response Messages](#Network-Response-Messages)
- [Consensus Service Schema Implementation](#Consensus-Service-Schema-Implementation)

## Key Features of the Consensus Service

The Consensus Service is a service (```ConsensusService extends Service```) that provides a decentralized, secure, and fast way to achieve aBFT consensus
on the order and validity of a series of opaque binary messages submitted to a topic, as well as a consensus timestamp for those messages.

Here’s an overview of what the Hedera Consensus Service is and how it operates

### Decentralized Ordering:

HCS provides a decentralized and verifiable ordering of messages. This ensures that all participants can agree on the order in which messages or transactions occur.

### Timestamping

Each message or transaction is assigned a trusted and accurate timestamp that ensures precise time-ordering of events.

### Immutability

Once a message is submitted and consensus is reached, the record is immutable and cannot be altered. This ensures the integrity and reliability of the data.

### Scalability

HCS is designed to handle high transaction throughput, making it suitable for applications that require rapid processing on large volumes of data.

### Native fee system

HCS is offering an optional fee system for the submission of topic messages.

## How Consensus Service Works

### Message Submission

Applications submit messages to the Hedera network. These messages can represent various types of transactions or events.
If a given topic has a custom fee set, then the fee is paid on message submission. (see [Topic custom fees](topic-custom-fees.md))

### Consensus Process

The messages are processed by the Hedera nodes, which use the hashgraph consensus algorithm to come to an agreement on the order and timestamp of each message.

### Consensus Order and Timestamps

Once consensus is achieved, each message is assigned a consensus timestamp and placed in a specific order. This ordered list of messages is then made available to the submitting application and other interested parties.

Message Retrieval
Applications can retrieve the ordered messages from the Hedera network to ensure that all participants have a consistent view of the sequence and timing of events.

## Architecture Overview

The Consensus Service is designed to handle transactions and queries related to consensus management.
The main components of the Consensus Service are:

1. `Protobuf Definitions`: These are used to define the structure of the transactions and queries. They ensure that the data sent between the client and the server is structured and typed.

2. `Handlers`: These are responsible for executing the transactions and queries.
   Each type of transaction or query has its own handler.
   Handlers interact with the Consensus Stores to perform the required operations.

3. `Topic Stores`: These are interfaces that define methods for interacting with the topic data. There are two types of topic stores: Readable and Writable. The Readable Topic Store is used when retrieving topic contents or information, while the Writable Topic Store is used when creating, updating, or deleting topics.

4. `ConsensusServiceInjectionModule`: This is a Dagger module that provides dependency injection for the Consensus Service. It ensures that the correct implementations of interfaces are used at runtime.

The Consensus Service is designed to be stateless, meaning that it does not store any client-specific data between requests.

## Transactions and Queries for the Consensus Service

Transactions and queries are the primary means of interacting with the
Consensus Service. They define the actions that can be performed on topics,
such as creating a new topic or retrieving the contents of an existing topic.

Each transaction body corresponds to a specific operation,
such as creating a new topic (`ConsensusCreateTopicTransactionBody`),
updating an existing topic (`ConsensusUpdateTopicTransactionBody`),
or deleting a topic (`ConsensusDeleteTopicTransactionBody`).

## Protobuf Definitions

Protobuf, or Protocol Buffers, is a method of serializing structured
data. The Consensus Service uses protobufs to define the structure of our transactions and queries.
Here are some of the Protobuf definitions used in the Consensus Service:

#### ConsensusCreateTopicTransactionBody

`ConsensusCreateTopicTransactionBody` is a message used to create a new topic in a consensus service.
It's a record, providing a concise syntax for immutable data classes and represents the structure for a transaction to create a new topic

The `ConsensusCreateTopicTransactionBody` message includes the following fields:

- `memo`: A short, publicly visible memo about the topic. (UTF-8 encoding, max 100 bytes). There is no guarantee of uniqueness for the memo. It is primarily used for human-readable descriptions or notes about the topic.
- `adminKey`: If specified, this key allows control over updating or deleting the topic.
  If no adminKey is specified, the updateTopic operation can only be used to extend the topic's expiration time, and the deleteTopic operation is disallowed.
- `submitKey`: If specified, only entities with the submitKey can submit messages to the topic.
  If unspecified, no access control is performed on message submissions, meaning all submissions are allowed.
- `autoRenewPeriod`: The initial lifetime of the topic and the time period for automatic renewal. Defines how long the topic will initially exist and how much additional time to add at the topic's expiration time for automatic renewals.
  Auto-renew functionality is dependent on server-side configuration, limited to specific minimum and maximum values.
- `autoRenewAccount`: Optional account used for extending the topic's lifetime at expiration. If specified, the account is used to fund the extension of the topic's lifetime.
  The extension duration is limited to the autoRenewPeriod or the amount that can be covered by the account's funds, whichever is smaller.
  An adminKey must be specified if an autoRenewAccount is provided, and the autoRenewAccount must sign the transaction.
- `feeScheduleKey`: If specified, only this key will have access control for update/delete of custom fees.
- `feeExemptKeyList`: Optional set of keys that are exempt from the topic's custom fees.
- `customFees`: Optional set of custom fee definitions.

#### ConsensusUpdateTopicTransactionBody

`ConsensusUpdateTopicTransactionBody` is a message used for updating a consensus topic.
It's a record, providing a concise syntax for immutable data classes and represents the structure for a transaction to update a topic

The `ConsensusUpdateTopicTransactionBody` message includes the following fields:
- `topicID`: The ID of the topic to update. If null, no change is made to the topic.
- `memo`: A short, publicly visible memo about the topic. (UTF-8 encoding, max 100 bytes). There is no guarantee of uniqueness for the memo. It is primarily used for human-readable descriptions or notes about the topic.
- `expirationTime`: The new expiration time for the topic. The effective consensus timestamp at which all transactions and queries will fail. If null, no change is made to the expiration time.
- `adminKey`: If specified, this key allows control over updating or deleting the topic.
If no adminKey is specified, the updateTopic operation can only be used to extend the topic's expiration time, and the deleteTopic operation is disallowed.
- `submitKey`: If specified, only entities with the submitKey can submit messages to the topic.
If unspecified, no access control is performed on message submissions, meaning all submissions are allowed.
- `autoRenewPeriod`: The initial lifetime of the topic and the time period for automatic renewal. Defines how long the topic will initially exist and how much additional time to add at the topic's expiration time for automatic renewals.
Auto-renew functionality is dependent on server-side configuration, limited to specific minimum and maximum values.
- `autoRenewAccount`: Optional account used for extending the topic's lifetime at expiration. If specified, the account is used to fund the extension of the topic's lifetime.
The extension duration is limited to the autoRenewPeriod or the amount that can be covered by the account's funds, whichever is smaller.
An adminKey must be specified if an autoRenewAccount is provided, and the autoRenewAccount must sign the transaction.
- `feeScheduleKey`: If specified, only this key will have access control for update/delete of custom fees.
- `feeExemptKeyList`: Optional set of keys that are exempt from the topic's custom fees.
- `customFees`: Optional set of custom fee definitions.

#### ConsensusDeleteTopicTransactionBody

`ConsensusDeleteTopicTransactionBody` is a message used for deleting a consensus topic.
It's implemented as a record, providing a concise syntax for immutable data classes and represents the structure for a transaction to delete a topic

The `ConsensusDeleteTopicTransactionBody` message includes the following fields:

- `topicID`: The ID of the topic to delete. If null, no change is made to the topic.

#### ConsensusGetInfoQuery

`ConsensusGetInfoQuery` is a message used for retrieving information about a consensus topic.
It's implemented as a record, providing a concise syntax for immutable data classes and represents the structure for a transaction to query info about a topic

The `ConsensusGetInfoQuery` message includes the following fields:

- `header`: Standard info sent from client to node, including the signed payment, and what kind of response is requested (cost, state proof, both, or neither).
- `topicID`: Represents the identifier of the topic for which information is being requested.

#### ConsensusSubmitMessageTransactionBody

`ConsensusSubmitMessageTransactionBody` is used in a transaction that submits a topic message to the Hedera network. Once the transaction is successfully executed, the receipt of the transaction will include the topic's updated sequence number and topic running hash.
In terms of transaction signing requirements, anyone can submit a message to a public topic, but the submitKey is required to sign the transaction for a private topic.

The `ConsensusSubmitMessageTransactionBody` message includes the following fields:

- `message`: Message to be submitted. Max size of the Transaction (including signatures) is 6KiB.
- `topicID`: Represents the identifier of the topic to submit message to.
- `chunkInfo`: Optional information of the current chunk in a fragmented message.

## Handlers

Handlers are responsible for executing transactions and queries.
Each type of transaction or query has its own handler.
All the Handlers either implement the ```TransactionHandler``` interface and provide implementations of
pureChecks, preHandle, handle, and calculateFees methods; or ultimately implement the ```QueryHandler``` interface
through their inheritance structure. If the latter, they provide an implementation of the ```findResponse``` method.

### pureChecks

The ```pureChecks``` method is responsible for performing checks that are independent
of state or context.
It takes a ```TransactionBody``` as an argument and throws a ```PreCheckException``` if any
of the checks fail.

### preHandle

The ```preHandle``` method is called during the pre-handle workflow.
It determines the signatures needed for operations on a topic. It takes
a ```PreHandleContext``` as an argument, which collects all information,
and throws a ```PreCheckException``` if any issue happens on the pre-handle
level. This method validates the topicID and checks if the
signatures are waived. If not, it validates and adds the required keys.

### handle

The ```handle``` method is responsible for executing the main logic of each Handler.
It takes a ```HandleContext``` as an argument and throws a ```HandleException``` if any issue happens during the handling process.

### calculateFees

The ```calculateFees``` method is responsible for calculating the fees
associated with the topic operations. It takes a ```FeeContext```
as an argument and returns a ```Fees``` object. This method calculates the
fees based on the size of the data being created/updated, etc.

### consensusCreateTopicHandler

The ```consensusCreateTopicHandler``` is responsible for handling the creation of new topics in the consensus service. It validates the transaction, checks the admin and submit keys, ensures the topic limit is not exceeded, sets the topic's properties, and calculates the fees for the creation operation.

### consensusUpdateTopicHandler

The ```consensusUpdateTopicHandler``` is responsible for handling the updates to topics in the consensus service. It validates the transaction, checks the admin key, and applies the requested updates to the topic's properties such as the admin key, submit key, memo, expiration time, auto-renew period, and auto-renew account. It also calculates the fees for the update operation.

### consensusDeleteTopicHandler

The ```consensusDeleteTopicHandler``` is responsible for handling the deletion of topics in the consensus service. It validates the transaction, checks the admin key, and sets the topic's deleted flag to true. It also calculates the fees for the deletion operation.

### consensusGetTopicInfoHandler

The `consensusGetTopicInfoHandler` is responsible for handling the retrieval of information about a specific topic in the consensus service. It validates the transaction, checks if the topic exists and is not deleted, and retrieves the topic's information such as memo, running hash, sequence number, expiration time, admin key, submit key, auto-renew period, and auto-renew account. It also calculates the fees for the get topic info operation.

### consensusSubmitMessageHandler

The `consensusSubmitMessageHandler` is responsible for handling the submission of messages to topics in the consensus service. It validates the transaction, checks the topic existence and its submit key, and updates the running hash and sequence number of the topic. If the topic has any custom fees, it tries to assess the fees. It also calculates the fees for the submit message operation.

## Network Response Messages

Specific network response messages (```ResponseCodeEnum```) are wrapped by ```HandleException``` and the codes relevant to the Consensus
Service are:

- ```OK;```: The transaction or query was successful
- ```UNAUTHORIZED;```: The transaction or query was not authorized
- ```BAD_ENCODING;```: The transaction or query had bad encoding
- ```MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;```: The maximum number of entities allowed in the current price regime has been reached
- ```AUTORENEW_ACCOUNT_NOT_ALLOWED;```: The auto-renew account specified is not allowed to be used for auto-renewal
- ```AUTORENEW_DURATION_NOT_IN_RANGE;```: The auto-renew period specified is not within the allowed range
- ```INVALID_EXPIRATION_TIME;```: The expiration time specified is invalid
- ```INVALID_AUTORENEW_ACCOUNT;```: The auto-renew account specified is invalid
- ```INVALID_RENEWAL_PERIOD;```: The renewal period specified is invalid
- ```INVALID_TOPIC_ID;```: The topic ID specified is invalid
- ```INVALID_CHUNK_NUMBER;```: The chunk number specified is invalid
- ```INVALID_CHUNK_TRANSACTION_ID;```: The chunk transaction ID specified is invalid
- ```INVALID_SUBMIT_KEY;```: The submit key specified is invalid
- ```INVALID_TOPIC_MESSAGE;```: The topic message specified is invalid
- ```INVALID_TRANSACTION;```: The transaction specified is invalid
- ```MESSAGE_SIZE_TOO_LARGE;```: The message size specified is too large
- ```INVALID_TOPIC_ID;```: The topic ID specified is invalid
- ```MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED;```: The provided fee exempt key list size exceeded the limit
- ```FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS;```: The provided fee exempt key list contains duplicated keys
- ```INVALID_KEY_IN_FEE_EXEMPT_KEY_LIST;```: The provided fee exempt key list contains an invalid key
- ```INVALID_FEE_SCHEDULE_KEY;```: The provided fee schedule key contains an invalid key
- ```FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED = 379;```: If a fee schedule key is not set when we create a topic we cannot add it on update
- ```FEE_SCHEDULE_KEY_NOT_SET;```: If the topic's custom fees are updated the topic SHOULD have a fee schedule key
- ```MAX_CUSTOM_FEE_LIMIT_EXCEEDED;```: The fee amount is exceeding the amount that the payer is willing to pay
- ```NO_VALID_MAX_CUSTOM_FEE;```: No provided max custom fee, or there are no corresponding topic fees
- ```DUPLICATE_DENOMINATION_IN_MAX_CUSTOM_FEE_LIST;```: The provided max custom fee list contains fees with duplicate denominations

## Consensus Service Schema Implementation

The current schema class extends the `Schema` class and is responsible for defining the initial schema for the Consensus Service. This class is used during the migration process to set up the initial state of the Consensus Service.

The class contains methods for creating and loading various system files into the state during the genesis (initial) state of the network. These system files include the Address Book, Node Details, Fee Schedule, Exchange Rate, Network Properties, HAPI Permissions, Throttle Definitions, and Software Update Consensus.

The class also contains methods for migrating the state of the
Consensus Service from a previous version to the current version.
During this migration process, the class is responsible for
transforming the state data to match the current schema.

The class is named with a version number (for example, 0.49.0)
to indicate the version of the software that this schema corresponds to.
This allows for multiple versions of the schema to exist,
each corresponding to a different version of the software.
This is particularly useful when migrating from one version of the
software to another, as it allows the migration process to know which
schema to migrate from and to.
