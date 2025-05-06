# Addressbook Service

The Address Book Service is responsible for configuring the list of nodes on the network.
Nodes can be added, updated or removed via the Address Book Service and changes take effect once a freeze upgrade
transaction is sent and the network is restarted. This allows the Hedera council to add and remove nodes, and node operators
to update nodes on the network.

### Table of Contents

- [Addressbook Service](#Addressbook-Service)
- [Table of Contents](#Table-of-Contents)
- [Architecture Overview](#Architecture-Overview)
- [Protobuf Definitions](#Protobuf-Definitions)
  - [Transactions for the Addressbook Service](#Transactions-for-the-Addressbook-Service)
  - [NodeCreateTransactionBody](#NodeCreateTransactionBody)
  - [NodeUpdateTransactionBody](#NodeUpdateTransactionBody)
  - [NodeDeleteTransactionBody](#NodeDeleteTransactionBody)
- [Addressbook Service Handlers](#Addressbook-Service-Handlers)
- [Network Response Messages](#Network-Response-Messages)

## Key Features of the Addressbook Service

The Addressbook Service is a service (```AddressBookService extends RpcService```) that provides a decentralized and secure
way to update the node addressbook to configure nodes on the Hedera network. It allows the Hedera council to add and
remove nodes, and node operators to configure nodes on the network. Changes are saved to state. When the network receives
a freeze transactions, new files `config.txt` and `s-public-alias.pem` are created on each node of the network to
reflect the changes. Changes take effect once the network is restarted.

## Architecture Overview

The Addressbook Service is designed to handle transactions related to the dynamic addressbook that specifies
nodes on the Hedera network. It provides a set of operations that allow users to add, delete and update node
information. The main components of the Addressbook Service are:

1. `Protobuf Definitions`: These are used to define the structure of transactions. They ensure that
   the data sent between the client and the server is structured and typed.

2. `Handlers`: These are responsible for executing the transactions. Each type of transaction has its own handler.
   Handlers interact with the Addressbook Service to perform the required operations.

3. `Addressbook Service`: These are interfaces that define methods for interacting with the dynamic addressbook.
   They provide the functionality for the various operations that can be performed.

4. `Node Stores`: These are responsible for storing data related to the node entities.
   The ```ReadableNodeStore``` and  ```WritableNodeStore``` are interfaces that defines the operations
   that can be performed on the node regarding state. ```ReadableNodeStore``` is used when retrieving node data,
   while the ```WritableNodeStore``` is used when creating, updating, or deleting node data.

5. `AddressBookServiceInjectionModule`: This is a Dagger module that provides dependency injection for the
   Addressbook Service. It ensures that the correct implementations of interfaces are used at runtime.

## Protobuf Definitions

Protobuf, or Protocol Buffers, is a method of serializing structured
data. Here are some of the Protobuf definitions used in the Addressbook Service:

### Transactions for the Addressbook Service

The Addressbook Service is defined in the `address_book_service.proto` file. It includes the following RPC methods:

- `createNode`: Adds a new consensus node to the network address book. This is a privileged transaction, requiring council signatures.
- `deleteNode`: Removes a consensus node from the network address book. This is a privileged transaction, requiring council signatures.
- `updateNode`: Modifies address book node attributes. This transaction must be signed by the node operator admin key set during the createNode operation.

### NodeCreateTransactionBody

`NodeCreateTransactionBody` is a protobuf message type defined in the `node_create.proto` file.
It is used to add a new consensus node to the network address book.

The `NodeCreateTransactionBody` message has the following fields:
- `account_id`: A Node account identifier. This account identifier must be in the "account number" form and must not use the alias field. If the identified account does not exist, this transaction will fail. Multiple nodes may share the same node account.
- `description`: An optional short description of the node. This value, if set, must not exceed 100 bytes when encoded as UTF-8.
- `gossip_endpoint`: A list of service endpoints for gossip. These endpoints represent the published endpoints to which other consensus nodes may gossip transactions. The list must not be empty and must not contain more than 10 entries. The first two entries in this list are the endpoints published to all consensus nodes. All other entries are reserved for future use.
- `service_endpoint`: A list of service endpoints for gRPC calls. These endpoints represent the published gRPC endpoints to which clients may submit transactions. The list must not be empty and must not contain more than 8 entries.
- `gossip_ca_certificate`: A certificate used to sign gossip events. This value must be a certificate of a type permitted for gossip signatures and must be the DER encoding of the certificate presented.
- `grpc_certificate_hash`: A hash of the node gRPC TLS certificate. This value may be used to verify the certificate presented by the node during TLS negotiation for gRPC. This value must be a SHA-384 hash.
- `admin_key`: An administrative key controlled by the node operator. This key must sign this transaction and each transaction to update this node. This field must contain a valid `Key` value and must not be set to an empty `KeyList`.
- `grpc_proxy_endpoint`: Service endpoint for gRPC proxy calls. This endpoint represent the published gRPC proxy to which clients may submit transactions.

### NodeUpdateTransactionBody

`NodeUpdateTransactionBody` is a protobuf message type defined in the `node_update.proto` file.
It is used to modify address book node attributes.

The `NodeUpdateTransactionBody` message has the following fields:
- `node_id`: A consensus node identifier in the network state. The node identified must exist in the network address book and must not be deleted.
- `account_id`: An account identifier. If set, this will replace the node account identifier. This transaction must be signed by the active key for both the current node account and the identified new node account.
- `description`: A short description of the node. If set, this value will replace the previous value.
- `gossip_endpoint`: A list of service endpoints for gossip. If set, this list will replace the existing list.
- `service_endpoint`: A list of service endpoints for gRPC calls. If set, this list will replace the existing list.
- `gossip_ca_certificate`: A certificate used to sign gossip events. If set, the new value will replace the existing bytes value.
- `grpc_certificate_hash`: A hash of the node gRPC TLS certificate. If set, the new value will replace the existing hash value.
- `admin_key`: An administrative key controlled by the node operator. If set, this key must sign this transaction and each subsequent transaction to update this node. If set, this field must contain a valid `Key` value and must not be set to an empty `KeyList`.
- `grpc_proxy_endpoint`: Service endpoint for gRPC proxy calls. This endpoint represent the published gRPC proxy to which clients may submit transactions.

### NodeDeleteTransactionBody

`NodeDeleteTransactionBody` is a protobuf message type defined in the `node_delete.proto` file.
It is used to remove a consensus node from the network address book.

The `NodeDeleteTransactionBody` message has the following field:
- `node_id`: A consensus node identifier in the network state. The node identified must exist in the network address book and must not be deleted.

## Addressbook Service Handlers

Handlers are responsible for executing addressbook transactions.
Each type of transaction has its own handler.
All the Handlers implement the ```TransactionHandler``` interface and provide implementations of
pureChecks, preHandle, handle, and calculateFees methods.

### NodeCreateHandler

`NodeCreateHandler` is a class responsible for handling `NodeCreateTransactionBody`. It contains all workflow-related
functionality regarding createNode. It is a privileged transaction, requiring council signatures.

### NodeUpdateHandler

`NodeUpdateHandler` is a class responsible for handling `NodeUpdateTransactionBody`. It contains all workflow-related
functionality regarding updateNode. It must be signed by the admin key set during the createNode operation.

### NodeDeleteHandler

`NodeDeleteHandler` is a class responsible for handling `NodeDeleteTransactionBody`. It contains all workflow-related
functionality regarding deleteNode. It is a privileged transaction, requiring council signatures.

## Network Response Messages

Specific network response messages (```ResponseCodeEnum```) are wrapped by ```HandleException``` or ```PreCheckException```.
The response codes relevant to the Addressbook Service are:

- `INVALID_ADMIN_KEY`: A provided admin key was invalid. Verify the bytes for an Ed25519 public key are exactly 32 bytes; and the bytes for a compressed ECDSA(secp256k1) key are exactly 33 bytes, with the first byte either 0x02 or 0x03.
- `INVALID_GOSSIP_CA_CERTIFICATE`: A transaction failed because the TLS certificate provided for the node is missing or invalid.
- `INVALID_GOSSIP_ENDPOINT`: A transaction failed because one or more entries in the list of service endpoints for the `gossip_endpoint` field is invalid.
- `INVALID_NODE_ACCOUNT_ID`: A transaction failed because the node account identifier provided does not exist or is not valid.
- `INVALID_SERVICE_ENDPOINT`: A transaction failed because one or more entries in the list of service endpoints for the `service_endpoint` field is invalid.
- `MAX_NODES_CREATED`: The maximum number of nodes allowed in the address book have been created.
- `INVALID_NODE_ID`: A transaction failed because the consensus node identified is not valid or does not exist in state.
- `UPDATE_NODE_ACCOUNT_NOT_ALLOWED`: The node account is not allowed to be updated.
- `INVALID_NODE_ACCOUNT`: Node Account provided does not match the node account of the node the transaction was submitted to.
- `NODE_DELETED`: A transaction failed because the consensus node identified is deleted from the address book.
- `FQDN_SIZE_TOO_LARGE`: In ServiceEndpoint, domain_name size too large.
- `GOSSIP_ENDPOINTS_EXCEEDED_LIMIT`: The number of gossip endpoints exceeds the limit.
- `GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN`: Fully qualified domain name is not allowed in gossip_endpoint.
- `INVALID_ENDPOINT`: ServiceEndpoint is invalid.
- `INVALID_IPV4_ADDRESS`: The IPv4 address is invalid.
- `INVALID_NODE_DESCRIPTION`: A transaction failed because the description field cannot be encoded as UTF-8 or is more than 100 bytes when encoded.
- `IP_FQDN_CANNOT_BE_SET_FOR_SAME_ENDPOINT`: In ServiceEndpoint, domain_name and ipAddressV4 are mutually exclusive.
- `KEY_REQUIRED`: Key not provided in the transaction body.
- `SERVICE_ENDPOINTS_EXCEEDED_LIMIT`: The number of service endpoints exceeds the limit.
