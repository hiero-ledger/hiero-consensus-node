# Service Implementation Overview

There are two types of service implementations:
1. **Front-End Services**: Provide new `Transaction` and/or `Query` handling.
2. **Back-End (Headless) Services**: Perform background work, no new `Transaction` or `Query` handling.

`Complex Services` instantiate both.

The different service types are bootstrapped in different ways and have different lifecycle management.

## Front-End Services

A `Front-End Service` is commonly developed in the following stages:
1. Protobuf Definitions
2. Basic Service and Handle Workflows
3. Service Bindings
4. End-To-End Tests
5. Configuration and Throttles
6. Core Service Development

### 1. Protobuf Definitions

- **Define new transactions, queries, and service messages:**
  - Edit or add `.proto` files in `hapi/hedera-protobuf-java-api/src/main/proto/services/`.
  - Extend `basic_types.proto` for new `HederaFunctionality` ([example](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/basic_types.proto#L1336)).
  - Extend `response_code.proto` for new response codes ([example](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/response_code.proto#L24)).
  - Extend `transaction.proto` for new transaction bodies ([example](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/services/transaction.proto#L166)).
- **Update module exports:**
  - `hapi/hapi/src/main/java/module-info.java` ([L67-L68](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hapi/src/main/java/module-info.java#L67-L68))
  - `hapi/hedera-protobuf-java-api/src/main/java/module-info.java` ([L3](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/java/module-info.java#L3))
- **Update utility mappings:**
  - `hapi/hapi/src/main/java/com/hedera/hapi/util/HapiUtils.java` ([Transaction Functionality](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hapi/src/main/java/com/hedera/hapi/util/HapiUtils.java#L169), [Query Declaration](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hapi/src/main/java/com/hedera/hapi/util/HapiUtils.java#L144))
- **Follow the enforced protobuf style guide.**

### 2. Basic Service and Handle Workflows

- **Create service implementation classes:**
  - Place in `com.hedera.node.app.service.<yourservice>`.
  - Implement transaction and query handlers as needed.
  - Follow the example service file structure for consistency.

### 3. Service Bindings

- **Register and bind your service:**
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/Hedera.java` ([Register New Service](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/Hedera.java#L531))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/ServiceScopeLookup.java` ([Map Transaction To Service](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/ServiceScopeLookup.java#L45-L46))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/ReadableStoreFactory.java` ([Register New Store Entry](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/ReadableStoreFactory.java#L79-L80))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java` ([Register New Store Entry](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java#L58-L59))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java` ([Map Transaction To Handler](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java#L138-L140))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionHandlers.java` ([Set of Transaction Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionHandlers.java#L73))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflowModule.java` ([Return Service Components](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflowModule.java#L46-L50), [Return Transaction Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflowModule.java#L138))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryDispatcher.java` ([Map Query To Handler](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryDispatcher.java#L41-L43))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryHandlers.java` ([Set of Query Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryHandlers.java#L34))
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryWorkflowInjectionModule.java` ([Provide Query Workflows](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryWorkflowInjectionModule.java#L46), [Return Query Handlers](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/query/QueryWorkflowInjectionModule.java#L130))
  - `hedera-node/hedera-app/src/main/java/module-info.java` ([Export Service Implementation Package](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hedera-node/hedera-app/src/main/java/module-info.java#L65))

### 4. End-To-End Tests

- **Add integration and unit tests** for your new service in the appropriate test source directories.

---

## Summary Table

|     Step     |                                                            File(s) to Modify                                                             |           What to Add/Change           |
|--------------|------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|
| Protobuf     | `.proto` files, `module-info.java`, `HapiUtils.java`                                                                                     | New messages, enums, exports, mappings |
| Service Impl | New Java/Kotlin classes in `service.<yourservice>`                                                                                       | Service logic, handlers                |
| Bindings     | `Hedera.java`, `ServiceScopeLookup.java`, `*StoreFactory.java`, `*Dispatcher.java`, `*Handlers.java`, `*Module.java`, `module-info.java` | Registration, mapping, exports         |
| Tests        | Test sources                                                                                                                             | End-to-end and unit tests              |

---

**Note:**
- Always follow the enforced protobuf style guide.
- Ensure all new functionality is properly mapped and exported.
- For back-end services, document and register lifecycle hooks as needed.
