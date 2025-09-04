# Service Implementation Overview

There are two types of service implementations.
1. `Front-End Services`, which provide functionality in the form of
new `Transaction` and `Query` handling.
2. `Back-End Services` or Headless Services, which perform background work and do
not provide any new `Transaction` or `Query` handling.

`Complex Services` instantiate both `Front-End` and `Back-End` services.

The different service types are bootstrapped in different ways and have
different lifecycle management.

## Front-End Services

A `Front-End Service` is a service that provides either new `Transaction` or
`Query` handling capabilities.

A `Front-End Service` is commonly developed in the following stages:
1. Protobuf Definitions
2. Basic Service and Handle Workflows
3. Service Bindings
4. End-To-End Tests
5. Configuration and Throttles
6. Core Service Development

### Protobuf Definitions

#### hapi/.../module-info.java

protoc and generated class packages
[Export Packages](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hapi/src/main/java/module-info.java#L67-L68)

#### hapi/.../HapiUtils.java

[Transaction Functionality](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hapi/src/main/java/com/hedera/hapi/util/HapiUtils.java#L169)

[Query Declaration](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hapi/src/main/java/com/hedera/hapi/util/HapiUtils.java#L144)

[Query Functionality](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hapi/src/main/java/com/hedera/hapi/util/HapiUtils.java#L169)

#### hedera-protobuf-java-api/.../module-info.java

protoc packages
[Export Packages](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hedera-protobuf-java-api/src/main/java/module-info.java#L3)

[Protobuf Directory](https://github.com/hiero-ledger/hiero-consensus-node/tree/main/hapi/hedera-protobuf-java-api/src/main/proto)
* How to write transaction, query, and service definitions.
* Need to link to the protobuf style guide that is being enforced.
* Need thorough explanation of the anatomy of a transaction, query, and
service definition.

[Extend HederaFunctionality in basic_types.proto](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hedera-protobuf-java-api/src/main/proto/services/basic_types.proto#L1336)

[Extend Response Codes Where Appropriate](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hedera-protobuf-java-api/src/main/proto/services/response_code.proto#L24)

[Extend TransactionBody](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hapi/hedera-protobuf-java-api/src/main/proto/services/transaction.proto#L166)

### Basic Service and Handle Workflows

#### Example Service File Structure

### Service Bindings

#### Hedera.java

[Register New Service](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hedera-node/hedera-app/src/main/java/com/hedera/node/app/Hedera.java#L531)

#### ServiceScopeLookup.java

[Map Transaction To Service](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/ServiceScopeLookup.java#L45-L46)

#### ReadableStoreFactory.java

[Register New Store Entry](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/ReadableStoreFactory.java#L79-L80)

#### WritableStoreFactory.java

[Register New Store Entry](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java#L58-L59)

#### TransactionDispatcher.java

[Map Transaction To Handler](https://github.com/hiero-ledger/hiero-consensus-node/blob/5e109d39d1c36f7be01d221dfaa919ab3fe37826/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java#L138-L140)

#### TransactionHandlers.java

[Set of Transaction Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionHandlers.java#L73)

#### HandleWorkflowModule.java

[Return Service Components](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflowModule.java#L46-L50)

[Module Return Transaction Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflowModule.java#L138)

#### QueryDispatcher.java

[Map Query To Handler](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryDispatcher.java#L41-L43)

#### QueryHandlers.java

[Set of Query Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryHandlers.java#L34)

#### QueryWorkflowInjectionModule.java

[Provide Query Workflows](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryWorkflowInjectionModule.java#L46)

[Return Query Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryWorkflowInjectionModule.java#L130)

#### hedera-app/.../module-info.java

[Export Package Of Service Implementations](https://github.com/hiero-ledger/hiero-consensus-node/blob/452cfef02c8478dfecfd3743f53193489008f963/hedera-node/hedera-app/src/main/java/module-info.java#L65)

### End-To-End Tests
