# CLPR Prototype Integration Notes

_Last revised: 2025-11-04_

This note captures every code location that was touched to glue the CLPR service
prototype into the node. It is intentionally pragmatic: the goal is to help a
new engineer trace where the prototype hooks into the platform and why each
change exists. Once the production implementation displaces these shortcuts this
document can be retired.

## 1. Protobuf surface

|                                          Path                                          |                                                                  Why it changed                                                                   |
|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `hapi/hedera-protobuf-java-api/src/main/proto/block/stream/output/state_changes.proto` | Added `STATE_ID_CLPR_LEDGER_CONFIGURATIONS` and the corresponding map key/value oneof entries so CLPR state can flow through block-stream output. |

**Checklist for future work:** after editing the proto, regenerate PBJ classes
(`./gradlew assemble`) so `BlockStreamUtils` and the listeners compile.

## 2. Block stream decoding

|                                                Path                                                |                                      Why it changed                                      |
|----------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/blocks/BlockStreamUtils.java` | Maps the new state ID, key, and value so block-stream tooling can render CLPR map edits. |

## 3. Merkle state listeners/tests

|                                                     Path                                                     |                      Why it changed                      |
|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------|
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/ImmediateStateChangeListener.java`     | Emits CLPR map updates when the service writes to state. |
| `hedera-node/hedera-app/src/test/java/com/hedera/node/app/blocks/impl/ImmediateStateChangeListenerTest.java` | Covers the new branch with CLPR-specific assertions.     |

## 4. Workflow bypass for dev mode

|                                              Path                                              |                                                                                           Why it changed                                                                                           |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/ingest/IngestChecker.java` | Skips payer signature expansion for `CLPR_SET_LEDGER_CONFIG` transactions when `clpr.devModeEnabled` is true. This lets the prototype submit bootstrap transactions without real signing material. |

> ⚠️ **Production reminder:** remove this bypass once the final signing story is
> in place.

## 5. Service wiring (Dagger)

|                                                      Path                                                       |                                                  Why it changed                                                  |
|-----------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hiero-clpr-interledger-service/src/main/java/org/hiero/interledger/clpr/client/ClprClient.java`    | Expanded API so the client receives payer and node account IDs.                                                  |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/module-info.java`                                | Declares required modules (`com.hedera.node.app.hapi.utils`) after the client started using signature utilities. |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprModule.java` | Updated provider to match the new `ClprEndpoint` constructor.                                                    |

## 6. CLPR endpoint behaviour

|                                                         Path                                                          |                                                                                                                              Why it changed                                                                                                                              |
|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprEndpoint.java`     | Implements the dev-mode boot/refresh loop. Generates a timestamp, submits the local configuration (using the `ClprClient`), and logs the outcome. The loop deliberately stops after refreshing the local node because gossip plus consensus distributes the transaction. |
| `hedera-node/hiero-clpr-interledger-service-impl/src/test/java/org/hiero/interledger/clpr/impl/ClprEndpointTest.java` | Unit tests that the endpoint bootstraps once, refreshes timestamps, and handles connectivity errors.                                                                                                                                                                     |

## 7. Client implementation

|                                                              Path                                                              |                                                                          Why it changed                                                                           |
|--------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/client/ClprClientImpl.java`     | Builds/sends real gRPC transactions and houses a dev-mode signer that looks for keys in `data/onboard/`. Falls back to an empty signature map if no key is found. |
| `hedera-node/hiero-clpr-interledger-service-impl/src/test/java/org/hiero/interledger/clpr/impl/client/ClprClientImplTest.java` | Verifies the transaction assembly and error-path behaviour.                                                                                                       |

## 8. State proof helpers

|                                                            Path                                                            |                                                             Why it changed                                                             |
|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprStateProofManager.java` | Adds `readLedgerConfiguration` helper, dev-mode fallbacks for empty ledger IDs, and verification shortcuts.                            |
| `hedera-node/hiero-clpr-interledger-service/src/main/java/org/hiero/interledger/clpr/ClprStateProofUtils.java`             | Provides a local `buildLocalClprStateProofWrapper` helper and simplifies extraction logic now that verification is handled separately. |

## 9. Handlers

|                                                                          Path                                                                           |                                                         Why it changed                                                          |
|---------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/handlers/ClprGetLedgerConfigurationHandler.java`         | Differentiates “no configuration yet” (returns `CLPR_LEDGER_CONFIGURATION_NOT_AVAILABLE`) from invalid proofs.                  |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/handlers/ClprSetLedgerConfigurationHandler.java`         | Applies monotonic timestamp checks, respects dev-mode bootstrap shortcuts, and reads existing state via the new manager helper. |
| `hedera-node/hiero-clpr-interledger-service-impl/src/test/java/org/hiero/interledger/clpr/impl/test/handler/ClprSetLedgerConfigurationHandlerTest.java` | Adapts the mocks to cover the new helper calls and validation paths.                                                            |

## 10. Configuration

|                                                            Path                                                             |                                                Why it changed                                                 |
|-----------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `hedera-node/configuration/dev/log4j2.xml` & `hedera-node/log4j2.xml`                                                       | Forwarded `QueryWorkflowImpl`, `ClprEndpoint`, and `ClprClientImpl` logs to the console for easier debugging. |
| `hedera-node/hedera-file-service-impl/src/main/resources/genesis/throttles-dev.json`                                        | Added `ClprGetLedgerConfig` to the dev throttle bucket so local queries succeed out of the box.               |
| `hedera-node/hedera-file-service-impl/src/test/java/com/hedera/node/app/service/file/impl/schemas/V0490FileSchemaTest.java` | Guards against accidental throttle JSON regressions.                                                          |

## 11. End-to-end and exploratory tests

|                                                Path                                                |                                                                    Why it changed                                                                     |
|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/interledger/ClprSuite.java` | Adds a HAPI suite that drives the prototype end to end (bootstraps, refreshes, asserts on logs). This suite is intended for manual/demo runs, not CI. |

## 12. Developer experience assists

|                                   Path                                    |                                  Why it changed                                  |
|---------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| `docs/design/clpr-service-design.md`                                      | Documented the prototype status, the dev-mode bypasses, and linked to this file. |
| `hedera-node/docs/design/clpr-prototype-integration-notes.md` (this file) | Summarises every change in one place so newcomers can orient quickly.            |

---

### Quick-start checklist for local runs

1. Ensure `clpr.devModeEnabled=true` in your configuration (dev profile already sets this).
2. Optional: drop a dev signing key into `data/onboard/` (`devGenesisKeypair.pem` or `StartUpAccount.txt`). Without it the client submits empty signatures.
3. Start a node; the endpoint logs will show `Bootstrapped local ledger …` followed by periodic timestamp refreshes.
4. Use the new HAPI test (`ClprSuite#exchangesConfigurationsViaRealClient`) or `ClprClientImpl` directly to exercise the prototype.

If any of the above fails, check the loggers mentioned in section 10—they are wired to the console specifically for prototype debugging.
