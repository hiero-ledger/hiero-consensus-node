# CLPR Hiero-to-Hiero E2E Integration Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two Hiero-to-Hiero CLPR integration tests (one-way delivery + full round-trip) that spin up two real Hiero subprocess networks and exercise the full pipeline via node-driven bundle sync.

**Architecture:** Port `@MultiNetworkHapiTest` annotation + `MultiNetworkExtension` from the prototype branch. Fix the `sendMessage` EVM ABI to accept `bytes connectorId` (not `address`). Add `getConnectionQueueState` to the CLPR system contract for polling. Write `ClprHieroToHieroSuite` using the annotation pattern. Tests use `ClprPassThroughVerifier` for connections and `PassThroughAuth` for connectors.

**Tech Stack:** Java 21, JUnit 5 `@TestFactory`, Headlong ABI codec, Ed25519 (`net.i2p.crypto.eddsa` + `org.hiero.base.crypto.Ed25519`), Keccak-256 (Bouncy Castle), `SubProcessNetwork`, existing HAPI builders.

---

## File Map

**hedera-smart-contract-service-impl — fix sendMessage + add getConnectionQueueState:**
- Modify: `src/main/java/.../clpr/sendmessage/SendMessageTranslator.java`
- Modify: `src/main/java/.../clpr/sendmessage/SendMessageCall.java`
- Create: `src/main/java/.../clpr/getconnection/GetConnectionTranslator.java`
- Create: `src/main/java/.../clpr/getconnection/GetConnectionCall.java`
- Modify: `src/main/java/.../processors/ClprTranslatorsModule.java`
- Modify (test resources): `src/main/resources/contract/contracts/ClprSystemContract/IClprSystemContract.sol`
- Modify (test resources): `src/main/resources/contract/contracts/ClprSystemContract/ClprSystemContract.sol`

**test-clients — infrastructure:**
- Modify: `src/main/java/.../junit/hedera/subprocess/SubProcessNetwork.java` (add `newIsolatedNetwork`)
- Create: `src/main/java/.../junit/MultiNetworkHapiTest.java`
- Create: `src/main/java/.../junit/extensions/MultiNetworkExtension.java`
- Modify: `src/main/java/.../spec/HapiSpec.java` (add `networkHapiTest`)
- Modify: `src/main/java/.../junit/TestTags.java` (add `MULTINETWORK`)
- Create: `src/main/java/.../spec/transactions/clpr/HapiClprCompleteConnector.java`
- Modify: `src/main/java/.../spec/transactions/TxnVerbs.java` (add `clprCompleteConnector()`)
- Modify: `src/main/java/.../suites/clpr/ClprSendMessageSuite.java` (update ABI call)
- Create: `src/main/java/.../suites/clpr/ClprHieroToHieroSuite.java`

All paths under `hedera-node/test-clients/` unless noted.

---

## Task 1: Fix sendMessage ABI — change `address` to `bytes`

The current `sendMessage(bytes32,address,bytes,bytes)` uses `address connectorContract` (20 bytes). Connectors are now keyed by a 32-byte `connectorId`. Fix to `sendMessage(bytes32,bytes,bytes,bytes)`.

**Files:**
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/sendmessage/SendMessageTranslator.java`
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/sendmessage/SendMessageCall.java`
- Modify: `hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/IClprSystemContract.sol`
- Modify: `hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/ClprSystemContract.sol`
- Modify: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/ClprSendMessageSuite.java`

- [ ] **Step 1: Update IClprSystemContract.sol**

Replace the `sendMessage` function:

```solidity
/// Enqueue a cross-ledger message on the specified connection.
/// @param connectionId 32-byte connection identifier
/// @param connectorId 32-byte connector identifier (keccak256(connectionId || pubKey || salt))
/// @param targetApplication destination application address bytes on the peer ledger
/// @param messageData opaque application payload
/// @return messageId the assigned message sequence number
function sendMessage(
    bytes32 connectionId,
    bytes calldata connectorId,
    bytes calldata targetApplication,
    bytes calldata messageData
) external returns (uint64 messageId);
```

- [ ] **Step 2: Update ClprSystemContract.sol**

Replace the `sendMessage` wrapper:

```solidity
function sendMessage(
    bytes32 connectionId,
    bytes calldata connectorId,
    bytes calldata targetApplication,
    bytes calldata messageData
) external returns (uint64 messageId) {
    (bool success, bytes memory result) = CLPR_PRECOMPILE.call(
        abi.encodeWithSelector(
            IClprSystemContract.sendMessage.selector,
            connectionId,
            connectorId,
            targetApplication,
            messageData
        )
    );
    require(success, "CLPR sendMessage failed");
    messageId = abi.decode(result, (uint64));
}
```

- [ ] **Step 3: Update SendMessageTranslator.java**

Change the method declaration from `address` to `bytes`:

```java
public static final SystemContractMethod SEND_MESSAGE = SystemContractMethod.declare(
                "sendMessage(bytes32,bytes,bytes,bytes)", "(uint64)")
        .withCategories(Category.CLPR);
```

Change `CONNECTOR_CONTRACT_INDEX` parsing note in the class Javadoc.

- [ ] **Step 4: Update SendMessageCall.java**

Change `connectorContract` field from `BigInteger` to `byte[]`, update constructor, and update parsing in `SendMessageTranslator.callFrom`:

In `SendMessageCall.java` constructor and fields:

```java
private final byte[] connectorId;  // was: BigInteger connectorContract

public SendMessageCall(
        @NonNull final HederaWorldUpdater.Enhancement enhancement,
        @NonNull final SystemContractGasCalculator gasCalculator,
        @NonNull final AccountID senderId,
        @NonNull final Address senderAddress,
        @NonNull final byte[] connectionId,
        @NonNull final byte[] connectorId,  // was: BigInteger connectorContract
        @NonNull final byte[] targetApplication,
        @NonNull final byte[] messageData) {
    ...
    this.connectorId = requireNonNull(connectorId);
    ...
}
```

In `execute()`, replace:

```java
// Old:
final var connectorAddress = addressToBytes(connectorContract);
final var assignedMessageId = clprApi.sendMessage(
        Bytes.wrap(connectionId), connectorAddress, ...);

// New:
final var assignedMessageId = clprApi.sendMessage(
        Bytes.wrap(connectionId), Bytes.wrap(connectorId), ...);
```

Remove the `addressToBytes` helper method entirely.

In `SendMessageTranslator.callFrom`:

```java
// Old:
final var connectorContract =
        ((com.esaulpaugh.headlong.abi.Address) call.get(CONNECTOR_CONTRACT_INDEX)).value();

// New:
final var connectorId = (byte[]) call.get(CONNECTOR_CONTRACT_INDEX);
```

Rename `CONNECTOR_CONTRACT_INDEX` constant to `CONNECTOR_ID_INDEX` throughout.

- [ ] **Step 5: Update ClprSendMessageSuite.java**

All `contractCall(CLPR_CONTRACT, SEND_MESSAGE, ..., asHeadlongAddress(connectorAddress), ...)` calls need the connector address replaced with the connector ID bytes. Since the existing suite still uses old-style connector registration (`sourceConnectorAddress`), and the connector store key is now `(connectionId, connectorId)`, the simplest fix is to make the connector ID the same as the old address padded to 32 bytes, or just skip the ABI-level sendMessage tests in the existing suite (they test error cases that don't require a registered connector).

For tests that use `contractCall(CLPR_CONTRACT, SEND_MESSAGE, ..., asHeadlongAddress(connectorAddress), ...)`:
- Change to `contractCall(CLPR_CONTRACT, SEND_MESSAGE, ..., connectorId, ...)` where `connectorId` is `byte[]` (32 bytes)
- The headlong ABI now expects `bytes` not `address`, so pass the raw `byte[]` directly

Example fix in `sendMessageOnActiveConnectionSucceeds()`:

```java
// Old:
contractCall(CLPR_CONTRACT, SEND_MESSAGE,
        crypto.connectionId(),
        asHeadlongAddress(connectorAddress),  // remove
        new byte[] {10, 20, 30},
        new byte[] {1, 2, 3, 4, 5})

// New:
contractCall(CLPR_CONTRACT, SEND_MESSAGE,
        crypto.connectionId(),
        crypto.connectorId(),  // 32-byte connectorId
        new byte[] {10, 20, 30},
        new byte[] {1, 2, 3, 4, 5})
```

Update `ConnectionCrypto` to also compute `connectorId` and use `HapiClprCompleteConnector` for registration (Task 6 adds this builder; update `ClprSendMessageSuite` after Task 6 is done).

- [ ] **Step 6: Build to confirm no compile errors**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :hedera-smart-contract-service-impl:compileJava :test-clients:compileJava 2>&1 | grep -E "error:|BUILD" | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/sendmessage/ \
        hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/ \
        hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/ClprSendMessageSuite.java
git commit -m "fix(clpr): change sendMessage ABI from address to bytes for connectorId"
```

---

## Task 2: Add getConnectionQueueState to CLPR system contract

Adds `getConnectionQueueState(bytes32) returns (uint64 receivedMessageId, uint64 ackedMessageId)` to let tests poll delivery state.

**Files:**
- Modify: `hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/IClprSystemContract.sol`
- Modify: `hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/ClprSystemContract.sol`
- Create: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/getconnection/GetConnectionCall.java`
- Create: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/getconnection/GetConnectionTranslator.java`
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/processors/ClprTranslatorsModule.java`

- [ ] **Step 1: Add to IClprSystemContract.sol**

```solidity
/// Returns queue state for a connection.
/// @param connectionId 32-byte connection identifier
/// @return receivedMessageId total messages received (bundle delivery counter)
/// @return ackedMessageId total messages acknowledged (response delivery counter)
function getConnectionQueueState(bytes32 connectionId)
    external
    returns (uint64 receivedMessageId, uint64 ackedMessageId);
```

- [ ] **Step 2: Add to ClprSystemContract.sol**

```solidity
function getConnectionQueueState(bytes32 connectionId)
    external
    returns (uint64 receivedMessageId, uint64 ackedMessageId)
{
    (bool success, bytes memory result) = CLPR_PRECOMPILE.staticcall(
        abi.encodeWithSelector(
            IClprSystemContract.getConnectionQueueState.selector,
            connectionId
        )
    );
    require(success, "CLPR getConnectionQueueState failed");
    (receivedMessageId, ackedMessageId) = abi.decode(result, (uint64, uint64));
}
```

- [ ] **Step 3: Create GetConnectionCall.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getconnection;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTION_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.clpr.ReadableConnectionStore;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code getConnectionQueueState(bytes32 connectionId) returns (uint64, uint64)}.
 */
public class GetConnectionCall extends AbstractCall {
    private static final long GAS_REQUIREMENT = 5_000L;

    private final byte[] connectionId;

    public GetConnectionCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] connectionId) {
        super(gasCalculator, enhancement, true);
        this.connectionId = requireNonNull(connectionId);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        final var connectionStore = nativeOperations()
                .storeFactory()
                .readableStore(ReadableConnectionStore.class);
        final var connection = connectionStore.getConnection(Bytes.wrap(connectionId));
        if (connection == null) {
            return gasOnly(
                    FullResult.ordinalRevertResult(CLPR_CONNECTION_NOT_FOUND, GAS_REQUIREMENT),
                    CLPR_CONNECTION_NOT_FOUND,
                    false);
        }
        return gasOnly(
                successResult(
                        GetConnectionTranslator.GET_CONNECTION_QUEUE_STATE
                                .getOutputs()
                                .encode(Tuple.of(
                                        BigInteger.valueOf(connection.receivedMessageId()),
                                        BigInteger.valueOf(connection.ackedMessageId()))),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }
}
```

- [ ] **Step 4: Create GetConnectionTranslator.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getconnection;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class GetConnectionTranslator extends AbstractCallTranslator<ClprCallAttempt> {

    static final int CONNECTION_ID_INDEX = 0;

    public static final SystemContractMethod GET_CONNECTION_QUEUE_STATE = SystemContractMethod.declare(
                    "getConnectionQueueState(bytes32)", "(uint64,uint64)")
            .withCategories(Category.CLPR);

    @Inject
    public GetConnectionTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(GET_CONNECTION_QUEUE_STATE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final ClprCallAttempt attempt) {
        return attempt.isMethod(GET_CONNECTION_QUEUE_STATE);
    }

    @Override
    public Call callFrom(@NonNull final ClprCallAttempt attempt) {
        final var call = GET_CONNECTION_QUEUE_STATE.decodeCall(attempt.inputBytes());
        final var connectionId = (byte[]) call.get(CONNECTION_ID_INDEX);
        return new GetConnectionCall(
                attempt.enhancement(), attempt.systemContractGasCalculator(), connectionId);
    }
}
```

- [ ] **Step 5: Register in ClprTranslatorsModule.java**

Add after the `provideSendMessageTranslator` method:

```java
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getconnection.GetConnectionTranslator;

@Provides
@Singleton
@IntoSet
@Named("ClprTranslators")
static CallTranslator<ClprCallAttempt> provideGetConnectionTranslator(
        @NonNull final GetConnectionTranslator translator) {
    return translator;
}
```

- [ ] **Step 6: Build**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :hedera-smart-contract-service-impl:compileJava 2>&1 | grep -E "error:|BUILD" | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/getconnection/ \
        hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/processors/ClprTranslatorsModule.java \
        hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/
git commit -m "feat(clpr): add getConnectionQueueState to CLPR system contract"
```

---

## Task 3: Add newIsolatedNetwork to SubProcessNetwork

The existing `liveNetwork` is private and `newSharedNetwork` enforces a single-network guard. Add a public factory for independent isolated networks.

**Files:**
- Modify: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java`

- [ ] **Step 1: Add newIsolatedNetwork static method**

Add after `newSharedNetwork` (around line 203):

```java
/**
 * Creates a new isolated subprocess network with the given name, size, and starting gRPC port.
 * Unlike {@link #newSharedNetwork}, multiple isolated networks may be created in the same
 * launcher session. Used by {@link com.hedera.services.bdd.junit.extensions.MultiNetworkExtension}.
 *
 * @param name         unique network name (used for working directory isolation)
 * @param size         number of nodes
 * @param shard        shard number
 * @param realm        realm number
 * @param firstGrpcPort starting gRPC port; pass -1 to auto-allocate
 * @return the new (not yet started) network
 */
public static synchronized SubProcessNetwork newIsolatedNetwork(
        @NonNull final String name,
        final int size,
        final long shard,
        final long realm,
        final int firstGrpcPort) {
    if (firstGrpcPort > 0) {
        initializeNextPortsForNetwork(size, firstGrpcPort);
    } else {
        initializeNextPortsForNetwork(size);
    }
    final var network = new SubProcessNetwork(
            name,
            IntStream.range(0, size)
                    .mapToObj(nodeId -> new SubProcessNode(
                            classicMetadataFor(
                                    nodeId,
                                    name,
                                    SUBPROCESS_HOST,
                                    name,
                                    nextGrpcPort,
                                    nextNodeOperatorPort,
                                    nextInternalGossipPort,
                                    nextExternalGossipPort,
                                    nextPrometheusPort,
                                    shard,
                                    realm),
                            GRPC_PINGER,
                            PROMETHEUS_CLIENT))
                    .toList(),
            shard,
            realm);
    Runtime.getRuntime().addShutdownHook(new Thread(network::terminate));
    return network;
}
```

- [ ] **Step 2: Build**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :test-clients:compileJava 2>&1 | grep -E "error:|BUILD" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java
git commit -m "feat(test): add SubProcessNetwork.newIsolatedNetwork for multi-network tests"
```

---

## Task 4: Port MultiNetworkHapiTest annotation and MultiNetworkExtension

**Files:**
- Create: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/MultiNetworkHapiTest.java`
- Create: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/extensions/MultiNetworkExtension.java`
- Modify: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/TestTags.java`

- [ ] **Step 1: Create MultiNetworkHapiTest.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ;

import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Marks a HAPI test factory that provisions multiple isolated subprocess networks and injects them
 * as {@code SubProcessNetwork} parameters in declaration order.
 *
 * <p>This annotation replaces {@link HapiTest} for multi-network scenarios; do not combine them.
 * Networks are started before and terminated after each test method.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({MultiNetworkExtension.class, SpecNamingExtension.class})
@ResourceLock(value = "NETWORK", mode = READ)
public @interface MultiNetworkHapiTest {
    Network[] networks() default {
        @Network(name = "PRIMARY"), @Network(name = "PEER"),
    };

    @interface Network {
        String name();

        int size() default 1;

        long shard() default -1;

        long realm() default -1;

        /** Starting gRPC port for this network. Pass -1 to auto-allocate (risk of collision). */
        int firstGrpcPort() default -1;

        ConfigOverride[] setupOverrides() default {};
    }
}
```

- [ ] **Step 2: Create MultiNetworkExtension.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Provisions and injects one or more subprocess networks for {@link MultiNetworkHapiTest}-annotated
 * methods. Networks are injected as {@code SubProcessNetwork} (or {@code HederaNetwork}) parameters
 * in declaration order. Each network is started before the test and terminated after.
 */
public class MultiNetworkExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final Logger log = LogManager.getLogger(MultiNetworkExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(MultiNetworkExtension.class);
    private static final String RESOURCES_KEY = "multiNetworks";
    private static final String PARAM_INDEXES_KEY = "networkParameterIndexes";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        findAnnotation(extensionContext).ifPresent(annotation -> {
            final var networks = startNetworks(annotation.networks());
            store(extensionContext).put(RESOURCES_KEY, networks);
            store(extensionContext)
                    .put(
                            PARAM_INDEXES_KEY,
                            networkParameterIndexes(
                                    extensionContext.getRequiredTestMethod().getParameters().length,
                                    extensionContext.getRequiredTestMethod().getParameters(),
                                    networks.length));
        });
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).remove(RESOURCES_KEY, SubProcessNetwork[].class);
        if (networks != null) {
            for (final var network : networks) {
                safeTerminate(network);
            }
        }
        store(extensionContext).remove(PARAM_INDEXES_KEY);
    }

    @Override
    public boolean supportsParameter(
            @NonNull final ParameterContext parameterContext,
            @NonNull final ExtensionContext extensionContext) {
        final var type = parameterContext.getParameter().getType();
        return (HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type))
                && findAnnotation(extensionContext).isPresent();
    }

    @Override
    public Object resolveParameter(
            @NonNull final ParameterContext parameterContext,
            @NonNull final ExtensionContext extensionContext) {
        final var networks = store(extensionContext).get(RESOURCES_KEY, SubProcessNetwork[].class);
        final var indexes = store(extensionContext).get(PARAM_INDEXES_KEY, List.class);
        if (networks == null || indexes == null) {
            throw new IllegalStateException("Multi networks have not been initialized for this test");
        }
        final var networkPosition = indexes.indexOf(parameterContext.getIndex());
        if (networkPosition < 0 || networkPosition >= networks.length) {
            throw new IllegalArgumentException(
                    "Parameter at index " + parameterContext.getIndex() + " is not mapped to a network");
        }
        return networks[networkPosition];
    }

    private SubProcessNetwork[] startNetworks(@NonNull final Network[] networkConfigs) {
        // Validate unique names
        final var dupes = Arrays.stream(networkConfigs)
                .collect(Collectors.groupingBy(Network::name, Collectors.counting()))
                .entrySet()
                .stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!dupes.isEmpty()) {
            throw new IllegalArgumentException("Network names must be unique, duplicates: " + dupes);
        }

        final List<SubProcessNetwork> networks = new ArrayList<>();
        for (final var config : networkConfigs) {
            final long shard = config.shard() >= 0 ? config.shard() : getConfigShard();
            final long realm = config.realm() >= 0 ? config.realm() : getConfigRealm();
            final var network =
                    SubProcessNetwork.newIsolatedNetwork(config.name(), config.size(), shard, realm, config.firstGrpcPort());
            // Apply application.properties overrides to every node
            if (config.setupOverrides().length > 0) {
                final List<String> flat = new ArrayList<>();
                for (final var override : config.setupOverrides()) {
                    flat.add(override.key());
                    flat.add(override.value());
                }
                for (long nodeId = 0; nodeId < config.size(); nodeId++) {
                    network.getApplicationPropertyOverrides().put(nodeId, List.copyOf(flat));
                }
            }
            networks.add(network);
        }

        try {
            for (final var network : networks) {
                network.start();
                network.awaitReady(STARTUP_TIMEOUT);
            }
            return networks.toArray(SubProcessNetwork[]::new);
        } catch (final Throwable t) {
            log.warn("Failed to start multi-network set; terminating started networks", t);
            networks.forEach(this::safeTerminate);
            throw new RuntimeException("Failed to start multi-network set", t);
        }
    }

    private void safeTerminate(final SubProcessNetwork network) {
        if (network == null) return;
        try {
            network.terminate();
        } catch (final Throwable t) {
            log.warn("Best-effort cleanup failed for network '{}'", network.name(), t);
        }
    }

    private List<Integer> networkParameterIndexes(
            final int paramCount,
            final java.lang.reflect.Parameter[] params,
            final int expectedNetworks) {
        final List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < paramCount; i++) {
            final var type = params[i].getType();
            if (HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type)) {
                indexes.add(i);
            }
        }
        if (indexes.size() != expectedNetworks) {
            throw new IllegalStateException(
                    "Expected " + expectedNetworks + " HederaNetwork parameters, found " + indexes.size());
        }
        return indexes;
    }

    private Optional<MultiNetworkHapiTest> findAnnotation(@NonNull final ExtensionContext ctx) {
        return ctx.getTestMethod()
                .map(m -> m.getAnnotation(MultiNetworkHapiTest.class))
                .or(() -> ctx.getTestClass().map(c -> c.getAnnotation(MultiNetworkHapiTest.class)));
    }

    private ExtensionContext.Store store(@NonNull final ExtensionContext ctx) {
        return ctx.getStore(NAMESPACE);
    }
}
```

- [ ] **Step 3: Add MULTINETWORK to TestTags.java**

Find the `CLPR` constant (line 56) and add below it:

```java
public static final String MULTINETWORK = "MULTINETWORK";
```

- [ ] **Step 4: Build**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :test-clients:compileJava 2>&1 | grep -E "error:|BUILD" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/MultiNetworkHapiTest.java \
        hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/extensions/MultiNetworkExtension.java \
        hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/TestTags.java
git commit -m "feat(test): add MultiNetworkHapiTest annotation and MultiNetworkExtension for two-network CLPR tests"
```

---

## Task 5: Add HapiSpec.networkHapiTest and HapiClprCompleteConnector

**Files:**
- Modify: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/HapiSpec.java`
- Create: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/transactions/clpr/HapiClprCompleteConnector.java`
- Modify: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/transactions/TxnVerbs.java`

- [ ] **Step 1: Add networkHapiTest to HapiSpec.java**

Add after the `customizedHapiTest` method (around line 1327):

```java
/**
 * Creates a dynamic test targeting a specific network, bypassing the thread-local target.
 * Used by multi-network tests that need to direct operations at an explicitly named network.
 *
 * @param targetNetwork the network to direct all operations to
 * @param ops           the operations to run
 * @return a {@link Stream} of {@link DynamicTest}s
 */
public static Stream<DynamicTest> networkHapiTest(
        @NonNull final HederaNetwork targetNetwork, @NonNull final SpecOperation... ops) {
    requireNonNull(targetNetwork);
    final var specName = SPEC_NAME.get();
    final var baseName = specName != null ? specName.substring(specName.lastIndexOf('.') + 1) : "spec";
    final var displayName = baseName + "@" + targetNetwork.name();
    final var spec = new HapiSpec(
            displayName,
            HapiSpecSetup.setupFrom(HapiSpecSetup.getDefaultPropertySource()),
            new SpecOperation[0],
            new SpecOperation[0],
            ops,
            java.util.Collections.emptyList());
    doTargetSpec(spec, targetNetwork);
    return Stream.of(DynamicTest.dynamicTest(displayName, spec));
}
```

Add import if needed:

```java
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
```

- [ ] **Step 2: Create HapiClprCompleteConnector.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprCompleteConnector;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprCompleteConnectorTransactionBody;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprCompleteConnector} (Phase 2: Reveal) transactions.
 */
public class HapiClprCompleteConnector extends HapiTxnOp<HapiClprCompleteConnector> {

    private byte[] connectorId;
    private byte[] publicKey;
    private byte[] signature;
    private ClprSignatureScheme signatureScheme = ClprSignatureScheme.ED25519;
    private byte[] salt;
    private byte[] connectionId;
    private Optional<String> connectorContractName = Optional.empty();
    private Optional<ContractID> connectorContractId = Optional.empty();
    private Optional<Key> adminKeyValue = Optional.empty();
    private Optional<String> adminKeyName = Optional.empty();
    private long lockedStake;

    public HapiClprCompleteConnector() {}

    public HapiClprCompleteConnector connectorId(final byte[] connectorId) {
        this.connectorId = connectorId;
        return this;
    }

    public HapiClprCompleteConnector publicKey(final byte[] publicKey) {
        this.publicKey = publicKey;
        return this;
    }

    public HapiClprCompleteConnector signature(final byte[] signature) {
        this.signature = signature;
        return this;
    }

    public HapiClprCompleteConnector signatureScheme(final ClprSignatureScheme scheme) {
        this.signatureScheme = scheme;
        return this;
    }

    public HapiClprCompleteConnector salt(final byte[] salt) {
        this.salt = salt;
        return this;
    }

    public HapiClprCompleteConnector connectionId(final byte[] connectionId) {
        this.connectionId = connectionId;
        return this;
    }

    public HapiClprCompleteConnector connectorContract(final String contractName) {
        this.connectorContractName = Optional.of(contractName);
        return this;
    }

    public HapiClprCompleteConnector connectorContractId(final ContractID contractId) {
        this.connectorContractId = Optional.of(contractId);
        return this;
    }

    public HapiClprCompleteConnector adminKey(final Key key) {
        this.adminKeyValue = Optional.of(key);
        return this;
    }

    public HapiClprCompleteConnector adminKeyName(final String keyName) {
        this.adminKeyName = Optional.of(keyName);
        return this;
    }

    public HapiClprCompleteConnector lockedStake(final long lockedStake) {
        this.lockedStake = lockedStake;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprCompleteConnector;
    }

    @Override
    protected HapiClprCompleteConnector self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprCompleteConnectorTransactionBody opBody = spec.txns()
                .<ClprCompleteConnectorTransactionBody, ClprCompleteConnectorTransactionBody.Builder>body(
                        ClprCompleteConnectorTransactionBody.class, b -> {
                            if (connectorId != null) b.setConnectorId(ByteString.copyFrom(connectorId));
                            if (publicKey != null) b.setPublicKey(ByteString.copyFrom(publicKey));
                            if (signature != null) b.setSignature(ByteString.copyFrom(signature));
                            b.setSignatureScheme(signatureScheme);
                            if (salt != null) b.setSalt(ByteString.copyFrom(salt));
                            if (connectionId != null) b.setConnectionId(ByteString.copyFrom(connectionId));
                            connectorContractId.ifPresent(b::setConnectorContract);
                            if (connectorContractName.isPresent()) {
                                b.setConnectorContract(spec.registry().getContractId(connectorContractName.get()));
                            }
                            adminKeyValue.ifPresent(b::setAdminKey);
                            if (adminKeyName.isPresent()) {
                                b.setAdminKey(spec.registry().getKey(adminKeyName.get()));
                            }
                            b.setLockedStake(lockedStake);
                        });
        return b -> b.setClprCompleteConnector(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return 0;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
```

- [ ] **Step 3: Add factory method to TxnVerbs.java**

Add after `clprRegisterConnector()` (around line 259):

```java
public static HapiClprCompleteConnector clprCompleteConnector() {
    return new HapiClprCompleteConnector();
}
```

Add import at the top of `TxnVerbs.java`:

```java
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprCompleteConnector;
```

- [ ] **Step 4: Build**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :test-clients:compileJava 2>&1 | grep -E "error:|BUILD" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/HapiSpec.java \
        hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/transactions/clpr/HapiClprCompleteConnector.java \
        hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/transactions/TxnVerbs.java
git commit -m "feat(test): add HapiSpec.networkHapiTest and HapiClprCompleteConnector builder"
```

---

## Task 6: Write ClprHieroToHieroSuite

**Files:**
- Create: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/ClprHieroToHieroSuite.java`

- [ ] **Step 1: Create the test suite**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnection;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnection;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.hiero.base.crypto.Ed25519;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Two-network Hiero-to-Hiero CLPR integration tests.
 *
 * <p>Spins up two real single-node Hiero subprocess networks with CLPR enabled and different
 * chain IDs. Uses the node-driven sync ({@code ClprConnectionManager}) to deliver bundles
 * between ledgers.
 *
 * <p>Prerequisites built into this test:
 * <ul>
 *   <li>Both networks configured to discover each other via {@code seedEndpoints}</li>
 *   <li>A {@code ClprPassThroughVerifier} deployed on each network</li>
 *   <li>A connection (commit-reveal) registered on both sides with the same {@code connectionId}</li>
 *   <li>A connector (commit-reveal) registered on both sides with the same {@code connectorId}</li>
 * </ul>
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroSuite {

    private static final String VERIFIER = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final String GET_QUEUE_STATE = "getConnectionQueueState";
    private static final String SEND_MESSAGE = "sendMessage";
    private static final long GAS = 500_000L;
    private static final long MIN_STAKE = 100L;
    private static final Duration DELIVERY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    @MultiNetworkHapiTest(networks = {
        @MultiNetworkHapiTest.Network(
                name = "ledgerA",
                size = 1,
                firstGrpcPort = 35400,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.enabled",  value = "true"),
                    @ConfigOverride(key = "clpr.chainId",  value = "hiero:298"),
                    @ConfigOverride(key = "clpr.minLockedStake", value = "100")
                }),
        @MultiNetworkHapiTest.Network(
                name = "ledgerB",
                size = 1,
                firstGrpcPort = 36400,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.enabled",  value = "true"),
                    @ConfigOverride(key = "clpr.chainId",  value = "hiero:299"),
                    @ConfigOverride(key = "clpr.minLockedStake", value = "100")
                })
    })
    @org.junit.jupiter.api.DisplayName("One-way: message from ledger A arrives on ledger B")
    Stream<DynamicTest> oneWayDelivery(
            final SubProcessNetwork ledgerA,
            final SubProcessNetwork ledgerB) {

        final var crypto = new ClprCrypto();
        final int portB = ledgerB.nodes().get(0).getGrpcPort();
        final int portA = ledgerA.nodes().get(0).getGrpcPort();
        final byte[] targetApp = new byte[20]; // zero address — any app failure still generates a response

        return Stream.of(
            // ── Setup ledger A ──────────────────────────────────────────────────
            networkHapiTest(ledgerA,
                clprUpdateLedgerConfiguration()
                    .configuration(ledgerConfig("hiero:298", portB))
                    .payingWith(GENESIS),
                uploadInitCode(VERIFIER), contractCreate(VERIFIER),
                uploadInitCode(CONNECTOR_CONTRACT), contractCreate(CONNECTOR_CONTRACT),
                uploadInitCode(CLPR_CONTRACT), contractCreate(CLPR_CONTRACT),
                cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                clprRegisterConnection().ownershipCommitment(crypto.connectionCommitment).payingWith(GENESIS),
                clprCompleteConnection()
                    .connectionId(crypto.connectionId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectionSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContract(VERIFIER)
                    .configProofBytes(ledgerConfig("hiero:299", portA).toByteArray())
                    .payingWith(GENESIS),
                clprRegisterConnector().commitment(crypto.connectorCommitment).payingWith(GENESIS),
                clprCompleteConnector()
                    .connectorId(crypto.connectorId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectorSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .salt(crypto.connectorSalt)
                    .connectionId(crypto.connectionId)
                    .connectorContract(CONNECTOR_CONTRACT)
                    .adminKeyName(GENESIS)
                    .lockedStake(MIN_STAKE)
                    .payingWith(GENESIS)
            ).findFirst().orElseThrow(),

            // ── Setup ledger B ──────────────────────────────────────────────────
            networkHapiTest(ledgerB,
                clprUpdateLedgerConfiguration()
                    .configuration(ledgerConfig("hiero:299", portA))
                    .payingWith(GENESIS),
                uploadInitCode(VERIFIER), contractCreate(VERIFIER),
                uploadInitCode(CONNECTOR_CONTRACT), contractCreate(CONNECTOR_CONTRACT),
                uploadInitCode(CLPR_CONTRACT), contractCreate(CLPR_CONTRACT),
                clprRegisterConnection().ownershipCommitment(crypto.connectionCommitment).payingWith(GENESIS),
                clprCompleteConnection()
                    .connectionId(crypto.connectionId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectionSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContract(VERIFIER)
                    .configProofBytes(ledgerConfig("hiero:298", portB).toByteArray())
                    .payingWith(GENESIS),
                clprRegisterConnector().commitment(crypto.connectorCommitment).payingWith(GENESIS),
                clprCompleteConnector()
                    .connectorId(crypto.connectorId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectorSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .salt(crypto.connectorSalt)
                    .connectionId(crypto.connectionId)
                    .connectorContract(CONNECTOR_CONTRACT)
                    .adminKeyName(GENESIS)
                    .lockedStake(MIN_STAKE)
                    .payingWith(GENESIS)
            ).findFirst().orElseThrow(),

            // ── Send from A ──────────────────────────────────────────────────────
            networkHapiTest(ledgerA,
                contractCall(CLPR_CONTRACT, SEND_MESSAGE,
                        crypto.connectionId,
                        crypto.connectorId,
                        targetApp,
                        "hello-clpr".getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith("callerA")
            ).findFirst().orElseThrow(),

            // ── Poll B for delivery ──────────────────────────────────────────────
            networkHapiTest(ledgerB,
                withOpContext(spec -> {
                    final var deadline = Instant.now().plus(DELIVERY_TIMEOUT);
                    while (Instant.now().isBefore(deadline)) {
                        try {
                            // contractCallLocal returns (uint64 receivedMessageId, uint64 ackedMessageId)
                            // We use a simple loop — if getConnectionQueueState reverts, receivedMessageId == 0
                            final var result = new java.util.concurrent.atomic.AtomicLong(0);
                            allRunFor(spec,
                                withOpContext(inner -> {
                                    // Use contractCall (not local) to avoid static-frame restrictions on subprocess
                                    // Check via getConnectionQueueState
                                    try {
                                        allRunFor(inner,
                                            contractCall(CLPR_CONTRACT, GET_QUEUE_STATE, crypto.connectionId)
                                                .gas(GAS)
                                                .payingWith(GENESIS)
                                                .via("pollB"));
                                    } catch (final Exception ignored) {
                                        // connection may not exist yet; keep polling
                                    }
                                }));
                            // If we got here without exception, check result from transaction record
                            // For simplicity: if the call succeeded, receivedMessageId >= 1 means delivered
                            // We use a polling approach: retry until delivered or timeout
                            break; // simplified: assume first successful call means delivered
                        } catch (final Exception ignored) {
                            Thread.sleep(POLL_INTERVAL.toMillis());
                        }
                    }
                    // Assert by querying the record
                }),
                // Final assertion via a read
                withOpContext(spec -> {
                    // We assert by calling getConnectionQueueState and checking that receivedMessageId >= 1
                    // The assertion is done in the spec operation below
                }),
                assertReceivedMessageCount(ledgerB, crypto.connectionId, 1)
            ).findFirst().orElseThrow()
        );
    }

    @MultiNetworkHapiTest(networks = {
        @MultiNetworkHapiTest.Network(
                name = "ledgerA",
                size = 1,
                firstGrpcPort = 35500,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.enabled",  value = "true"),
                    @ConfigOverride(key = "clpr.chainId",  value = "hiero:298"),
                    @ConfigOverride(key = "clpr.minLockedStake", value = "100")
                }),
        @MultiNetworkHapiTest.Network(
                name = "ledgerB",
                size = 1,
                firstGrpcPort = 36500,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.enabled",  value = "true"),
                    @ConfigOverride(key = "clpr.chainId",  value = "hiero:299"),
                    @ConfigOverride(key = "clpr.minLockedStake", value = "100")
                })
    })
    @org.junit.jupiter.api.DisplayName("Round-trip: response from ledger B arrives back on ledger A")
    Stream<DynamicTest> fullRoundTrip(
            final SubProcessNetwork ledgerA,
            final SubProcessNetwork ledgerB) {

        final var crypto = new ClprCrypto();
        final int portB = ledgerB.nodes().get(0).getGrpcPort();
        final int portA = ledgerA.nodes().get(0).getGrpcPort();
        final byte[] targetApp = new byte[20];

        return Stream.of(
            // ── Setup ledger A (identical to oneWayDelivery) ─────────────────────
            networkHapiTest(ledgerA,
                clprUpdateLedgerConfiguration()
                    .configuration(ledgerConfig("hiero:298", portB))
                    .payingWith(GENESIS),
                uploadInitCode(VERIFIER), contractCreate(VERIFIER),
                uploadInitCode(CONNECTOR_CONTRACT), contractCreate(CONNECTOR_CONTRACT),
                uploadInitCode(CLPR_CONTRACT), contractCreate(CLPR_CONTRACT),
                cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                clprRegisterConnection().ownershipCommitment(crypto.connectionCommitment).payingWith(GENESIS),
                clprCompleteConnection()
                    .connectionId(crypto.connectionId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectionSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContract(VERIFIER)
                    .configProofBytes(ledgerConfig("hiero:299", portA).toByteArray())
                    .payingWith(GENESIS),
                clprRegisterConnector().commitment(crypto.connectorCommitment).payingWith(GENESIS),
                clprCompleteConnector()
                    .connectorId(crypto.connectorId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectorSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .salt(crypto.connectorSalt)
                    .connectionId(crypto.connectionId)
                    .connectorContract(CONNECTOR_CONTRACT)
                    .adminKeyName(GENESIS)
                    .lockedStake(MIN_STAKE)
                    .payingWith(GENESIS)
            ).findFirst().orElseThrow(),

            // ── Setup ledger B ──────────────────────────────────────────────────
            networkHapiTest(ledgerB,
                clprUpdateLedgerConfiguration()
                    .configuration(ledgerConfig("hiero:299", portA))
                    .payingWith(GENESIS),
                uploadInitCode(VERIFIER), contractCreate(VERIFIER),
                uploadInitCode(CONNECTOR_CONTRACT), contractCreate(CONNECTOR_CONTRACT),
                uploadInitCode(CLPR_CONTRACT), contractCreate(CLPR_CONTRACT),
                clprRegisterConnection().ownershipCommitment(crypto.connectionCommitment).payingWith(GENESIS),
                clprCompleteConnection()
                    .connectionId(crypto.connectionId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectionSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContract(VERIFIER)
                    .configProofBytes(ledgerConfig("hiero:298", portB).toByteArray())
                    .payingWith(GENESIS),
                clprRegisterConnector().commitment(crypto.connectorCommitment).payingWith(GENESIS),
                clprCompleteConnector()
                    .connectorId(crypto.connectorId)
                    .publicKey(crypto.publicKey)
                    .signature(crypto.connectorSignature)
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .salt(crypto.connectorSalt)
                    .connectionId(crypto.connectionId)
                    .connectorContract(CONNECTOR_CONTRACT)
                    .adminKeyName(GENESIS)
                    .lockedStake(MIN_STAKE)
                    .payingWith(GENESIS)
            ).findFirst().orElseThrow(),

            // ── Send from A ──────────────────────────────────────────────────────
            networkHapiTest(ledgerA,
                contractCall(CLPR_CONTRACT, SEND_MESSAGE,
                        crypto.connectionId,
                        crypto.connectorId,
                        targetApp,
                        "hello-round-trip".getBytes(StandardCharsets.UTF_8))
                    .gas(GAS)
                    .payingWith("callerA")
            ).findFirst().orElseThrow(),

            // ── Wait for B to receive (confirms delivery) ────────────────────────
            networkHapiTest(ledgerB,
                assertReceivedMessageCount(ledgerB, crypto.connectionId, 1)
            ).findFirst().orElseThrow(),

            // ── Wait for A to receive ack (confirms round-trip) ──────────────────
            networkHapiTest(ledgerA,
                assertAckedMessageCount(ledgerA, crypto.connectionId, 1)
            ).findFirst().orElseThrow()
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static com.hedera.services.bdd.spec.utilops.UtilOp assertReceivedMessageCount(
            final SubProcessNetwork network,
            final byte[] connectionId,
            final long expectedMin) {
        return withOpContext(spec -> {
            final var deadline = Instant.now().plus(DELIVERY_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                final var receivedCount = queryReceivedMessageId(spec, connectionId);
                if (receivedCount >= expectedMin) return;
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            final var receivedCount = queryReceivedMessageId(spec, connectionId);
            assertTrue(receivedCount >= expectedMin,
                    "Expected receivedMessageId >= " + expectedMin + " on " + network.name()
                            + " but got " + receivedCount);
        });
    }

    private static com.hedera.services.bdd.spec.utilops.UtilOp assertAckedMessageCount(
            final SubProcessNetwork network,
            final byte[] connectionId,
            final long expectedMin) {
        return withOpContext(spec -> {
            final var deadline = Instant.now().plus(DELIVERY_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                final var ackedCount = queryAckedMessageId(spec, connectionId);
                if (ackedCount >= expectedMin) return;
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            final var ackedCount = queryAckedMessageId(spec, connectionId);
            assertTrue(ackedCount >= expectedMin,
                    "Expected ackedMessageId >= " + expectedMin + " on " + network.name()
                            + " but got " + ackedCount);
        });
    }

    private static long queryReceivedMessageId(
            final com.hedera.services.bdd.spec.HapiApiSpec spec,
            final byte[] connectionId) throws Throwable {
        return queryQueueState(spec, connectionId)[0];
    }

    private static long queryAckedMessageId(
            final com.hedera.services.bdd.spec.HapiApiSpec spec,
            final byte[] connectionId) throws Throwable {
        return queryQueueState(spec, connectionId)[1];
    }

    private static long[] queryQueueState(
            final com.hedera.services.bdd.spec.HapiApiSpec spec,
            final byte[] connectionId) throws Throwable {
        final var resultHolder = new long[]{0L, 0L};
        try {
            allRunFor(spec,
                withOpContext(inner -> {
                    // contractCallLocal on getConnectionQueueState returns (uint64, uint64)
                    final var txnRef = new java.util.concurrent.atomic.AtomicReference<byte[]>();
                    allRunFor(inner,
                        com.hedera.services.bdd.spec.queries.QueryVerbs
                            .contractCallLocalWithFunctionAbi(
                                CLPR_CONTRACT,
                                com.hedera.services.bdd.suites.contract.Utils
                                    .getABIFor(com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                        GET_QUEUE_STATE, CLPR_CONTRACT),
                                (Object) connectionId)
                            .exposingResultTo(res -> {
                                // res is a Tuple: (uint64 receivedMessageId, uint64 ackedMessageId)
                                if (res instanceof com.esaulpaugh.headlong.abi.Tuple t) {
                                    resultHolder[0] = ((BigInteger) t.get(0)).longValue();
                                    resultHolder[1] = ((BigInteger) t.get(1)).longValue();
                                }
                            })
                    );
                }));
        } catch (final Exception e) {
            // Connection may not exist yet; return [0, 0]
        }
        return resultHolder;
    }

    private static void allRunFor(
            final com.hedera.services.bdd.spec.HapiApiSpec spec,
            final com.hedera.services.bdd.spec.SpecOperation... ops) {
        com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor(spec, ops);
    }

    private static ClprLedgerConfiguration ledgerConfig(
            final String chainId, final int peerPort) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(chainId)
                .setServiceAddress(com.google.protobuf.ByteString.copyFrom(new byte[]{0, 0, 1}))
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(peerPort)
                                .build())
                        .setTlsCertificate(com.google.protobuf.ByteString.EMPTY)
                        .setEcdsaSigningKey(com.google.protobuf.ByteString.EMPTY)
                        .build())
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxSyncsPerSec(10)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build();
    }

    // ── Crypto helpers ───────────────────────────────────────────────────────────

    /**
     * Deterministic cryptographic material for test connections and connectors.
     * Uses a fixed Ed25519 private key so both ledgers compute the same connectionId and connectorId.
     */
    private static final class ClprCrypto {
        // Fixed test private key — 32 bytes, deterministic across test runs
        private static final byte[] TEST_PRIVATE_KEY = {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
        };

        // 0x000000000000000000000000000000000000016e (CLPR system contract address)
        private static final byte[] CLPR_SERVICE_ADDRESS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
        };

        final byte[] publicKey = new byte[32];
        final byte[] connectionId;
        final byte[] connectionCommitment;
        final byte[] connectionSignature = new byte[64];
        final byte[] connectorSalt = new byte[32]; // all zeros
        final byte[] connectorId;
        final byte[] connectorCommitment;
        final byte[] connectorSignature = new byte[64];

        ClprCrypto() {
            // Derive public key from fixed private key
            org.hiero.base.crypto.Ed25519.generatePublicKey(TEST_PRIVATE_KEY, 0, publicKey, 0);

            // Deterministic connectionId from a seed string
            connectionId = keccak256("hiero-e2e-test-connection-v1".getBytes(StandardCharsets.UTF_8));

            // Connection commitment: keccak256(connectionId || pubKey)
            connectionCommitment = keccak256(concat(connectionId, publicKey));

            // Connection signature: Ed25519 over keccak256(connectionId)
            final var connSigMsg = keccak256(connectionId);
            org.hiero.base.crypto.Ed25519.sign(
                    TEST_PRIVATE_KEY, 0, connSigMsg, 0, connSigMsg.length, connectionSignature, 0);

            // Connector ID: keccak256(connectionId || pubKey || connectorSalt)
            connectorId = keccak256(concat(connectionId, concat(publicKey, connectorSalt)));

            // Connector commitment: keccak256(connectorId || pubKey)
            connectorCommitment = keccak256(concat(connectorId, publicKey));

            // Connector signature: Ed25519 over keccak256(connectorId || CLPR_SERVICE_ADDRESS)
            final var connectorSigMsg = keccak256(concat(connectorId, CLPR_SERVICE_ADDRESS));
            org.hiero.base.crypto.Ed25519.sign(
                    TEST_PRIVATE_KEY, 0, connectorSigMsg, 0, connectorSigMsg.length, connectorSignature, 0);
        }

        private static byte[] keccak256(final byte[] input) {
            return new Keccak.Digest256().digest(input);
        }

        private static byte[] concat(final byte[] a, final byte[] b) {
            final byte[] result = new byte[a.length + b.length];
            System.arraycopy(a, 0, result, 0, a.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }
    }
}
```

**Note on `Ed25519.sign` import:** Use `org.hiero.base.crypto.Ed25519` (the same class used in `ClprCompleteConnectionHandlerTest`). If the import resolves to a different class, check the test module path and adjust. The method signature is `Ed25519.sign(privateKey, offset, message, msgOffset, msgLen, sig, sigOffset)`.

**Note on `contractCallLocalWithFunctionAbi`:** Uses the existing method from `QueryVerbs`. The `CLPR_CONTRACT` must be deployed with the ABI that includes `getConnectionQueueState`. The `Utils.getABIFor(FUNCTION, GET_QUEUE_STATE, CLPR_CONTRACT)` reads the ABI from the compiled contract JSON in resources. Ensure the Solidity contract and its compiled JSON are updated to include `getConnectionQueueState` (the `.json` and `.bin` files under `ClprSystemContract/` may need to be recompiled — or mock the ABI string directly as a fallback).

- [ ] **Step 2: Compile**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :test-clients:compileJava 2>&1 | grep -E "error:|BUILD" | tail -10
```

Expected: `BUILD SUCCESSFUL`. Fix any import issues.

- [ ] **Step 3: Commit**

```bash
git add hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/ClprHieroToHieroSuite.java
git commit -m "feat(clpr): add ClprHieroToHieroSuite — two-network Hiero-to-Hiero integration tests"
```

---

## Task 7: Recompile ClprSystemContract ABI artifacts

The `ClprSystemContract.json` and `.bin` files in test resources must be updated to include `getConnectionQueueState` and the updated `sendMessage` signature. These are compiled Solidity artifacts.

**Files:**
- Modify: `hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/ClprSystemContract.json`
- Modify: `hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/ClprSystemContract.bin`

- [ ] **Step 1: Recompile the contract**

If Foundry/solc is available:

```bash
cd <clpr-smart-contracts-checkout>
# Or use solc directly if available:
solc --abi --bin \
  hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/ClprSystemContract.sol \
  -o /tmp/clpr-recompile/
```

If the project uses a Hardhat or Foundry setup for compiling test contracts, follow the existing compilation pattern. The `.json` file needs the ABI to include both updated `sendMessage` and new `getConnectionQueueState`.

As a fallback (if compilation tooling is not available), manually update `ClprSystemContract.json` to add the `getConnectionQueueState` entry and update the `sendMessage` input from `address` to `bytes`. The `.bin` file (bytecode) also needs to reflect both changes.

The ABI entry for `getConnectionQueueState` to add to the JSON:

```json
{
  "inputs": [{"internalType": "bytes32", "name": "connectionId", "type": "bytes32"}],
  "name": "getConnectionQueueState",
  "outputs": [
    {"internalType": "uint64", "name": "receivedMessageId", "type": "uint64"},
    {"internalType": "uint64", "name": "ackedMessageId", "type": "uint64"}
  ],
  "stateMutability": "view",
  "type": "function"
}
```

Update the existing `sendMessage` ABI entry to change the second input from `"type": "address"` to `"type": "bytes"`.

- [ ] **Step 2: Build and verify contracts load**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :test-clients:compileJava 2>&1 | grep -E "error:|BUILD" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add hedera-node/test-clients/src/main/resources/contract/contracts/ClprSystemContract/
git commit -m "feat(clpr): update ClprSystemContract ABI with getConnectionQueueState and bytes connectorId"
```

---

## Task 8: Full build verification

- [ ] **Step 1: Run all CLPR unit tests**

```bash
cd <clpr-hiero-checkout>/hedera-node
../gradlew :app-service-clpr-impl:test --rerun-tasks 2>&1 | tail -10
```

Expected: `177 passing`

- [ ] **Step 2: Build all affected modules**

```bash
../gradlew :app-service-clpr-impl:build :hedera-smart-contract-service-impl:build :test-clients:compileJava 2>&1 | grep -E "BUILD|FAIL" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Fix any remaining issues and commit**

```bash
git add -A
git commit -m "fix(clpr): resolve any remaining compile issues from e2e test implementation"
```
