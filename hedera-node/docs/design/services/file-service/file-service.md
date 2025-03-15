# File Service

The File Service is a service (```FileService extends Service```) that handles all
transactions and queries related to the storage and retrieval of opaque binary data.
It provides a set of operations that allow transactions to create, update, append, and delete files. It also
provides a way to retrieve file contents and information.

### Table of Contents

- [Architecture Overview](#Architecture-Overview)
- [Transaction and Queries for the File Service](#Transaction-and-Queries-for-the-File-Service)
- [Protobuf Definitions](#Protobuf-Definitions)
  - [Transaction Body's](#Transaction-Bodys)
    - [FileCreateTransactionBody](#FileCreateTransactionBody)
    - [FileUpdateTransactionBody](#FileUpdateTransactionBody)
    - [FileAppendTransactionBody](#FileAppendTransactionBody)
    - [FileDeleteTransactionBody](#FileDeleteTransactionBody)
    - [SystemDeleteTransactionBody](#SystemDeleteTransactionBody)
    - [SystemUndeleteTransactionBody](#SystemUndeleteTransactionBody)
    - [FileGetContentsTransactionBody](#FileGetContentsTransactionBody)
    - [FileGetInfoQuery](#FileGetInfoQuery)
- [Handlers](#Handlers)
  - [FileCreateHandler](#FileCreateHandler)
  - [FileUpdateHandler](#FileUpdateHandler)
  - [FileAppendHandler](#FileAppendHandler)
  - [FileDeleteHandler](#FileDeleteHandler)
  - [FileSystemDeleteHandler](#FileSystemDeleteHandler)
  - [FileSystemUndeleteHandler](#FileSystemUndeleteHandler)
  - [FileGetContentsHandler](#FileGetContentsHandler)
  - [FileGetInfoHandler](#FileGetInfoHandler)
- [Network Response Messages](#Network-Response-Messages)
- [File Service Schema Implementation](#File-Service-Schema-Implementation)

## Architecture Overview

The File Service is designed to handle transactions and queries related to file management.
It provides a set of operations that allow users to create, update, append, and delete files.
The main components of the File Service are:

1. `Protobuf Definitions`: These are used to define the structure of our transactions and queries. They ensure that the data sent between the client and the server is structured and typed.

2. `Handlers`: These are responsible for executing the transactions and queries
   Each type of transaction or query has its own handler.
   Handlers interact with the File Stores to perform the required operations.

3. `File Stores`: These are interfaces that define methods for interacting with the file data. There are two types of file stores: Readable and Writable. The Readable File Store is used when retrieving file contents or information, while the Writable File Store is used when creating, updating, or deleting files.

4. `FileServiceInjectionModule`: This is a Dagger module that provides dependency injection for the File Service. It ensures that the correct implementations of interfaces are used at runtime.

The File Service is designed to be stateless, meaning that it does not store any client-specific data between requests.

## Transaction and Queries for the File Service

Transactions and queries are the primary means of interacting with the
File Service. They define the actions that can be performed on files,
such as creating a new file or retrieving the contents of an existing file.

## Protobuf Definitions

Protobuf, or Protocol Buffers, is a method of serializing structured
data. The File Service uses it to define the structure of our transactions and queries.
Here are some of the Protobuf definitions used in the File Service:

### Transaction Body's & Queries

These are the specific types of transactions that can be performed on files.
Each transaction body corresponds to a specific operation,
such as creating a new file (`FileCreateTransactionBody`),
updating an existing file (`FileUpdateTransactionBody`),
appending data to a file (`FileAppendTransactionBody`),
or deleting a file (`FileDeleteTransactionBody`).

#### FileCreateTransactionBody

`FileCreateTransactionBody` is a message used to create a new file with the given contents. The file will automatically disappear at the expirationTime, unless its expiration is extended by another transaction before that time. If the file is deleted, then its contents will become empty and it will be marked as deleted until it expires, and then it will cease to exist.

The `FileCreateTransactionBody` message includes the following fields:

- `expirationTime`: The time at which this file should expire.
- `keys`: All keys at the top level of a key list must sign to create or modify the file. Any one of the keys at the top level key list can sign to delete the file.
- `contents`: The bytes that are the contents of the file.
- `shardID`: Shard in which this file is created.
- `realmID`: The Realm in which to the file is created (leave this null to create a new realm).
- `newRealmAdminKey`: If realmID is null, then this the admin key for the new realm that will be created.
- `memo`: The memo associated with the file (UTF-8 encoding max 100 bytes).

#### FileUpdateTransactionBody

`FileUpdateTransactionBody` is a message used to update an existing file. The file will automatically disappear at the expirationTime, unless its expiration is extended by another transaction before that time. If the file is deleted, then its contents will become empty and it will be marked as deleted until it expires, and then it will cease to exist.

The `FileUpdateTransactionBody` message includes the following fields:

- `fileID`: The ID of the file to be updated.
- `expirationTime`: The new time at which this file should expire.
- `keys`: The new keys that must sign to create or modify the file. Any one of the keys at the top level key list can sign to delete the file.
- `contents`: The new bytes that are the contents of the file.
- `memo`: The new memo associated with the file (UTF-8 encoding max 100 bytes).

#### FileAppendTransactionBody

`FileAppendTransactionBody` is a message used to append data to an existing file. The file will automatically disappear at the expirationTime, unless its expiration is extended by another transaction before that time. If the file is deleted, then its contents will become empty and it will be marked as deleted until it expires, and then it will cease to exist.

The `FileAppendTransactionBody` message includes the following fields:

- `fileID`: The ID of the file to which data will be appended.
- `appendContents`: The bytes that are to be appended to the file.

#### FileDeleteTransactionBody

`FileDeleteTransactionBody` is a message used to delete an existing file. The file will be marked as deleted until it expires, and then it will cease to exist.

The `FileDeleteTransactionBody` message includes the following fields:

- `fileID`: The ID of the file to be deleted.

#### SystemDeleteTransactionBody

`SystemDeleteTransactionBody` is a message used to delete an entity (file or contract) in a special way that allows it to be undeleted. This is a system-level operation that requires special permissions to execute.

The `SystemDeleteTransactionBody` message includes the following fields:

- `fileID`: The ID of the file to be deleted. This field is optional and mutually exclusive with `contractID`.
- `contractID`: The ID of the contract to be deleted. This field is optional and mutually exclusive with `fileID`.

#### SystemUndeleteTransactionBody

`SystemUndeleteTransactionBody` is a message used to undelete an entity (file or contract) that was deleted using the SystemDeleteTransaction. This is a system-level operation that requires special permissions to execute.

The `SystemUndeleteTransactionBody` message includes the following fields:

- `fileID`: The ID of the file to be undeleted. This field is optional and mutually exclusive with `contractID`.
- `contractID`: The ID of the contract to be undeleted. This field is optional and mutually exclusive with `fileID`.

#### FileGetContentsTransactionBody

`FileGetContentsTransactionBody` is a message used to retrieve the contents of a file.

The `FileGetContentsTransactionBody` message includes the following fields:

- `fileID`: The ID of the file whose contents are requested.

#### FileGetInfoQuery

`FileGetInfoQuery` is a message used to retrieve information about a file.

The `FileGetInfoQuery` message includes the following fields:

- `header`: Standard info sent from client to node, including the signed payment, and what kind of response is requested (cost, state proof, both, or neither).
- `fileID`: The file ID of the file whose information is requested.

## Handlers

Handlers are responsible for executing the transactions and queries.
Each type of transaction or query has its own handler.
All the Handlers either implement the ```TransactionHandler``` interface and provide implementations of
pureChecks, preHandle, handle, and calculateFees methods; or ultimately implement the ```QueryHandler``` interface
through their inheritance structure. If the latter, they provide an implementation of the ```findResponse``` method.

### pureChecks

The ```pureChecks``` method is responsible for performing checks that are independent
of state or context.
It takes a ```TransactionBody``` as an argument and throws a ```WorkflowException``` if any
of the checks fail. In the context of the FileAppendHandler, this method checks
if the fileID in the FileAppendTransactionBody is null. If it is,
a WorkflowException with the INVALID_FILE_ID response code is thrown.

### preHandle

The ```preHandle``` method is called during the pre-handle workflow.
It determines the signatures needed for appending a file. It takes
a ```PreHandleContext``` as an argument, which collects all information,
and throws a ```WorkflowException``` if any issue happens on the pre-handle
level. This method validates the fileID and checks if the file
signatures are waived. If not, it validates and adds the required keys.

### handle

The ```handle``` method is responsible for executing the main logic of each Handler.
For example with the ```FileAppendHandler```, it takes a ```HandleContext``` as an argument
and throws a ```WorkflowException``` if any issue happens during the handling process.
This method handles the appending of data to a file. It validates the
file and its contents, and then updates the file with the new contents.

### calculateFees

The ```calculateFees``` method is responsible for calculating the fees
associated with the file append operation. It takes a ```FeeContext```
as an argument and returns a ```Fees``` object. This method calculates the
fees based on the size of the data being created/appended, etc. and the effective
lifetime of the file. For special files (software update files),
a different calculation is used.

### FileCreateHandler

The ```FileCreateHandler``` is responsible for creating new files. It validates the transaction,
checks the necessary permissions, creates the file, and calculates the associated fees.

### FileUpdateHandler

The ```FileUpdateHandler``` handler is responsible for updating existing files. It validates the transaction,
checks the necessary permissions, updates the file, and calculates the associated fees.

### FileAppendHandler

The ```FileAppendHandler``` handler is responsible for appending data to existing files. It validates the transaction,
checks the necessary permissions, appends the data, and calculates the associated fees.

### FileDeleteHandler

The ```FileDeleteHandler``` is responsible for deleting existing files. It validates the transaction,
checks the necessary permissions, deletes the file, and calculates the associated fees.

### FileSystemDeleteHandler

The `FileSystemDeleteHandler` is responsible for handling system-level deletion of files. This is a special operation that requires specific permissions to execute. The handler validates the transaction, checks the necessary permissions, marks the file as deleted in a way that allows it to be undeleted later, and calculates the associated fees. The file will be marked as deleted until it expires, and then it will cease to exist.

### FileSystemUndeleteHandler

The `FileSystemUndeleteHandler` is responsible for handling system-level undeletion of files. This operation is used to restore a file that was previously deleted using the `FileSystemDeleteHandler`. This is a special operation that requires specific permissions to execute. The handler validates the transaction, checks the necessary permissions, marks the file as undeleted, and calculates the associated fees.

### FileGetContentsHandler

The `FileGetContentsHandler` is responsible for handling queries that retrieve the contents of a file. The handler validates the query, checks the necessary permissions, retrieves the file contents from the Readable File Store, and returns the contents in the response message. If the file does not exist or the client does not have the necessary permissions, an error is returned.

### FileGetInfoHandler

The `FileGetInfoHandler` is responsible for handling queries that retrieve information about a file. The handler validates the query, checks the necessary permissions, retrieves the file information from the Readable File Store, and returns the information in the response message. The information includes the file's size, expiration time, and keys. If the file does not exist or the client does not have the necessary permissions, an error is returned.

## Network Response Messages

Specific network response messages (```ResponseCodeEnum```) are wrapped by ```WorkflowException``` and the codes relevant to the File
Service are:

- ```FEE_SCHEDULE_FILE_PART_UPLOADED```: Fee Schedule Proto File Part uploaded
- ```FILE_APPEND_WOULD_EXCEED_SIZE_LIMIT```: File Append would exceed the currently allowable limit
- ```FILE_CONTENT_EMPTY```: The contents of file are provided as empty
- ```FILE_DELETED```: The file has been marked as deleted
- ```FILE_SYSTEM_EXCEPTION```: Unexpected exception thrown by file system functions
- ```FILE_UPLOADED_PROTO_INVALID```: Fee Schedule Proto uploaded but not valid (append or update is required)
- ```FILE_UPLOADED_PROTO_NOT_SAVED_TO_DISK```: Fee Schedule Proto uploaded but not valid (append or update is required)
- ```INVALID_EXCHANGE_RATE_FILE```: Failed to update exchange rate file
- ```INVALID_FEE_FILE```: Failed to update fee file
- ```INVALID_FILE_ID```: The file id is invalid or does not exist
- ```INVALID_FILE_WACL```: File WACL keys are invalid
- ```MAX_FILE_SIZE_EXCEEDED```: File size exceeded the currently allowable limit
- ```NO_WACL_KEY```: WriteAccess Control Keys are not provided for the file

## File Service Schema Implementation

The current schema class extends the `Schema` class and is responsible for defining the initial schema for the File Service. This class is used during the migration process to set up the initial state of the File Service.

The class contains methods for creating and loading various system files into the state during the genesis (initial) state of the network. These system files include the Address Book, Node Details, Fee Schedule, Exchange Rate, Network Properties, HAPI Permissions, Throttle Definitions, and Software Update Files.

The class also contains methods for migrating the state of the
File Service from a previous version to the current version.
During this migration process, the class is responsible for
transforming the state data to match the current schema.

The class is named with a version number (in this case, 0.49.0)
to indicate the version of the software that this schema corresponds to.
This allows for multiple versions of the schema to exist,
each corresponding to a different version of the software.
This is particularly useful when migrating from one version of the
software to another, as it allows the migration process to know which
schema to migrate from and to.
