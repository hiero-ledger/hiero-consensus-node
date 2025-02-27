# Services

## Overview

This document provides an overview of the core services that power the Hedera
network. Each service plays a crucial role in ensuring the efficient, secure,
and scalable operation of the Hedera public ledger. Below, you'll find a
description of each service along with its key functions and how it fits into
the overall architecture of Hedera.

`hedera-app-spi` defines the SPI (service provider interface) for service
modules. This section gives a brief outline of what each of the different APIs
are intended for.

### Table of Contents

1. [Hedera Consensus Service (HCS)](#hedera-consensus-service-hcs)
2. [Hedera Token Service (HTS)](#hedera-token-service-hts)
3. [Hedera Smart Contract Service](#hedera-smart-contract-service)
4. [Hedera File Service](#hedera-file-service)
5. [Hedera Network Service](#hedera-network-service)
6. [Hedera Schedule Service](#hedera-schedule-service)
7. [Hedera Util Service](#hedera-util-service)
8. [Address Book Service](#address-book-service)

## The Service Provider Interface (SPI)

The `Service` interface is implemented by each service module, for each
conceptual "service" it provides. Typically, a service module has a single
implementation of `Service`, but the `hedera-token-service` is more complicated
and may have several services. For example, it may have a `TokenService` and a
`CryptoService`. For simplicity in mapping concepts to code, we have a `Service`
subtype for each different *service* in our protobuf schema definitions.

The actual implementation class for a service is in the corresponding
implementation. For example, the `hedera-token-service-api` module defines an
`CryptoService` interface that extends from `Service`, while the
`hedera-token-service` implementation module defines an implementation class
such as `CryptoServiceImpl`.

The `hedera-app` module has access to all modules, including implementation
modules, and uses this ability to create, at construction time, instances of
each service implementation. We could have instead used a reflection-based
approach with `ServiceLoader` to try to load services dynamically, but we found
the resulting code to be difficult to understand.
By design, we strive for simple code that we can easily debug with stack traces
that are short and obviously meaningful.
The downside to this design is that it requires changes to code to add or remove
new service module implementations.
We accept this downside for the time being. A future revision may institute a *
*simple** DI solution that does not depend on reflection, outside from what the
`ServiceLoader` does.

Each `Service` implementation takes in its constructor a `StateRegistry` which
is used for setting up the service state in the merkle tree. The `Service`
implementation also acts as a factory for `TransactionHandler`s,
`PreTransactionHandler`s, and `QueryHandler`s, and the main entrypoint into all
API provided by the service module.

See also the [Service Modules](service-modules.md) document for more information
on how to create a new service module.

## Hedera Consensus Service (HCS)

### Overview

The Hedera Consensus Service (HCS) provides decentralized consensus ordering
with high throughput and low-latency finality. It enables the creation of
verifiable and auditable event logs, distributed ledgers, and fair, fast, and
secure transaction ordering.

### Key Functions

- **Decentralized Consensus:** Offers a tamper-proof log of messages, ensuring
  transparency and trust.
- **Message Ordering:** Provides a fair and trusted timestamp for each
  transaction.
- **Event Streaming:** Allows subscribers to listen to the ordered stream of
  messages in real-time.

### Use Cases

- **Supply Chain Tracking**
- **Decentralized Applications (dApps)**
- **Audit Logs**

**Full Documentation:
** [Consensus Service](consensus-service/consensus-service.md)

## Hedera Token Service (HTS)

### Overview

The Hedera Token Service (HTS) enables the creation, management, and transfer of
native tokens on the Hedera network. It supports a wide variety of use cases,
including fungible and non-fungible tokens (NFTs).

### Key Functions

- **Token Creation:** Supports the creation of tokens with customizable
  properties such as supply, decimal places, and freeze, wipe, and KYC status.
- **Transfers and Ownership:** Securely manage token transfers and ownership,
  including bulk transfers.
- **Minting and Burning:** Efficiently mint and burn tokens as needed.

### Use Cases

- **Asset Tokenization**
- **Loyalty Programs**
- **NFTs and Digital Collectibles**

**Full Documentation:** [Token Service](token-service)

## Hedera Smart Contract Service

### Overview

The Hedera Smart Contract Service allows developers to deploy and interact with
smart contracts on the Hedera network, enabling decentralized applications (
dApps) with complex logic and automated processes.

### Key Functions

- **Contract Deployment:** Deploy smart contracts written in Solidity.
- **Contract Execution:** Execute contract functions with predictable and secure
  results.
- **Interoperability:** Integrate with other services like HTS and HCS for
  enhanced functionality.

### Use Cases

- **Decentralized Finance (DeFi)**
- **Automated Workflows**
- **Tokenized Assets**

**Full Documentation:** [Smart Contract Service](smart-contract-service)

## Hedera File Service

### Overview

The Hedera File Service (HFS) provides a decentralized file storage solution,
allowing users to create, update, and delete files on the Hedera network. It's
often used in conjunction with other services to store data such as contract
bytecode or token metadata.

### Key Functions

- **File Management:** Create, update, and delete files on the network.
- **Storage of Smart Contracts:** Store smart contract bytecode and other
  associated data.
- **Data Availability:** Ensure data is available and tamper-proof across the
  network.

### Use Cases

- **Document Management**
- **Data Storage for dApps**
- **Smart Contract Storage**

**Full Documentation:** [File Service](file-service/file-service.md)

## Hedera Network Service

### Overview

The Hedera Network Service encompasses the foundational components that ensure
the security, scalability, and reliability of the Hedera network. It includes
the mechanisms for managing accounts, processing transactions, and ensuring
network integrity.

### Key Functions

- **Account Querying:** Allows querying of user accounts, but not modification
  or deletion.
- **Transaction Processing:** Handle the validation and processing of
  transactions across the network.
- **Network Security:** Ensure the overall security and stability of the network
  through various cryptographic and consensus mechanisms.

### Use Cases

- **User Account Management**
- **Secure Transactions**
- **Network Governance**

**Full Documentation:
** [Network Service](network-admin-service/network-admin-service.md)

## Hedera Schedule Service

### Overview

The Hedera Schedule Service allows users to schedule transactions to be executed
at a later time or once certain conditions are met. This enables more complex
workflows and deferred operations in decentralized applications.

### Key Functions

- **Transaction Scheduling:** Schedule transactions to be executed in the future
  or upon the fulfillment of specific conditions.
- **Conditional Execution:** Define conditions under which scheduled
  transactions are executed, providing flexibility and control.
- **Execution Guarantees:** Ensure that scheduled transactions are executed as
  per the defined conditions, adding reliability to deferred operations.

### Use Cases

- **Recurring Payments**
- **Conditional Smart Contract Execution**
- **Automated Workflows**

**Full Documentation:** [Schedule Service](schedule-service/schedule-service.md)

## Hedera Util Service

### Overview

The Util Service in Hedera provides various utility functions that support the
core operations of other services. This includes common tasks such as logging,
validation, and data manipulation that are essential across different components
of the Hedera network.

### Key Functions

- **Logging and Monitoring:** Facilitates detailed logging and monitoring of
  operations across different services.
- **Data Validation:** Ensures data integrity by performing validation checks.
- **Common Utilities:** Provides a set of shared utilities that simplify the
  development and maintenance of other services.

### Use Cases

- **Service Support Functions**
- **Data Integrity Checks**
- **Operational Monitoring**

**Full Documentation:**  [Util Service](util-service/util-service.md)

## Address Book Service

### Overview

The AddressBook Service manages the network’s address book, which contains
details about the nodes that participate in the Hedera network. This service
ensures that the address book is consistently updated and accessible for network
operations.

### Key Functions

- **Node Information Management:** Maintains up-to-date information about all
  nodes in the network, including their public keys and IP addresses.
- **Address Book Updates:** Handles updates to the address book as nodes are
  added, removed, or changed.
- **Network Communication:** Facilitates communication between nodes by
  providing the necessary connection information.

### Use Cases

- **Node Discovery and Management**
- **Network Configuration**
- **Secure Node Communication**

**Full Documentation:
** [Address Book Service](address-book-service/address-book-service.md)

---
