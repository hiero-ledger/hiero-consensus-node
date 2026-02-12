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

### Virtual-map state keys and values (what, where and why)

When your new service defines persistent states (key/value or virtual map states) that the platform will serialize and persist, you must register those state types in the shared `virtual_map_state.proto` so the platform and other services agree on the numeric state id and the key/value PBJ types.

Why this is required
- The Swirlds/Hedera state lifecycle uses a compact numeric "state id" to identify each named state. The generated Java enum for the `StateKey` oneof in `virtual_map_state.proto` exposes the canonical ordinal for that state (via `StateKey.KeyOneOfType.<NAME>.protoOrdinal()`), and your `Schema` classes must use that ordinal when creating `StateDefinition`s. If the proto entry is missing or the ordinal is different, the runtime will not be able to map your Schema's state to the platform's persisted data and compilation/runtime errors will follow.

Where to add the entries
- Edit `hapi/hedera-protobuf-java-api/src/main/proto/platform/state/virtual_map_state.proto` and add two entries:
1. In the `StateKey` message (the oneof named `key`) add an entry that declares the key protobuf type for your state and a unique field number.
2. In the `StateValue` message (the oneof named `value`) add the corresponding entry for the value protobuf type and a unique field number.

Guidelines and ranges
- Follow existing conventions in `virtual_map_state.proto` for choosing field numbers and range allocation. Many services put core states in the low ranges; platform-reserved ranges and queue ranges are documented inside the file.
- Don't reuse an existing field number; instead pick the next free number in the sensible range for your service or coordinate with the maintainers if unsure.

How to wire it up in your Java schema
1. After regenerating the protobuf/ PBJ sources, refer to the generated enum to get the canonical state id:

- Example: `int stateId = StateKey.KeyOneOfType.ClprService_I_CONFIGURATIONS.protoOrdinal();`

2. In your schema class (for example, `V0650ClprSchema.java`) create the `StateDefinition` using the public `StateDefinition.onDisk` overload that accepts the state id first, then the label and the protobuf serializers, for example:

```java
StateDefinition.onDisk(
    stateId,
    "CLPR_LEDGER_CONFIGURATION",
    ClprLedgerId.PROTOBUF,
    ClprLedgerConfiguration.PROTOBUF,
    MAX_ENTRIES);
```

3. Use `StateMetadata.computeLabel(serviceName, stateKey)` if you want a consistent computed label for the state (many services do this).

Checklist for adding a new state
- [ ] Add key and value entries to `virtual_map_state.proto` with unique field numbers.
- [ ] Regenerate protobuf / PBJ Java sources (build process or explicit codegen).
- [ ] Use `StateKey.KeyOneOfType.<NAME>.protoOrdinal()` in your schema to derive the state id.
- [ ] Create the `StateDefinition` with `StateDefinition.onDisk(stateId, label, keyProto, valueProto, maxEntries)`.
- [ ] Run a local build and tests.

Common pitfalls and troubleshooting
- If you see a compiler error about a private `StateDefinition.onDisk` overload or "private access" to `onDisk`, that usually means you called the wrong overload (the internal/private one that doesn't accept the state id) — switch to the public `onDisk(int, String, ...)` overload.
- If the runtime can't find your state, re-check the generated `StateKey.KeyOneOfType` enum to ensure you used the correct constant name and `protoOrdinal()`.
- If you accidentally choose a field number that collides with another service, the generated enum ordinals will mismatch and you'll see state mapping errors; pick a unique number and re-generate sources.

Example quick flow (concrete)
1. Add entries in `virtual_map_state.proto`:
- In `StateKey`: `org.hiero.hapi.interledger.state.clpr.ClprLedgerId ClprService_I_CONFIGURATIONS = 53;`
- In `StateValue`: `org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration ClprService_I_CONFIGURATIONS = <some-unique-number>`
2. Rebuild codegen to produce updated `StateKey` enum.
3. In `V0650ClprSchema.java` use:

```java
final int id = StateKey.KeyOneOfType.ClprService_I_CONFIGURATIONS.protoOrdinal();
StateDefinition.onDisk(id, "CLPR_LEDGER_CONFIGURATION", ClprLedgerId.PROTOBUF, ClprLedgerConfiguration.PROTOBUF, 50_000L);
```

If you'd like, I can add a short example PR that updates `virtual_map_state.proto` and `V0650ClprSchema.java` consistently (I already made the schema call use `onDisk(int,...)` and left a placeholder id in that file). Let me know if you want me to perform the proto change and regenerate PBJ sources in this repo.

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
