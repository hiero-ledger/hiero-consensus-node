// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocalWithFunctionAbi;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.solidityIdFrom;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.interledger.ClprSuite.createClient;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.interledger.clpr.ClprStateProofUtils.buildLocalClprStateProofWrapper;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractConfiguration;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageQueueMetadata;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.ContractID;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * CLPR two-ledger round-trip HapiTest suite.
 *
 * <p>For the full human-readable staged specification of the test flow, see the comment block at the
 * start of {@link #twoNetworkMessagesExchange(SubProcessNetwork, SubProcessNetwork)}.
 */
@Tag(TestTags.MULTINETWORK)
@OrderedInIsolation
public class ClprMessagesSuite {

    private static final Duration AWAIT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration AWAIT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long CONNECTOR_INITIAL_BALANCE_TINYBARS = 100_000_000L;

    private static final String PRIVATE_LEDGER = "private";
    private static final String PUBLIC_LEDGER = "public";
    private static final String PUBLIC_LEDGER_S = "smallBundleLedger";
    private static final String PUBLIC_LEDGER_L = "largeBundleLedger";

    private static final String CLPR_MIDDLEWARE = "ClprMiddleware";
    private static final String CLPR_CONNECTOR = "MockClprConnector";
    private static final String SOURCE_APP = "SourceApplication";
    private static final String ECHO_APP = "EchoApplication";

    private static final String UNIT = "HBAR";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String CLPR_QUEUE_SYSTEM_CONTRACT = "0x000000000000000000000000000000000000016e";

    private static final byte[] SOURCE_CONNECTOR_ID = bytes32("01");
    private static final byte[] DESTINATION_CONNECTOR_ID = bytes32("02");

    private static final byte[] PAYLOAD_ONE = "hello-clpr-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD_TWO = "hello-clpr-2".getBytes(StandardCharsets.UTF_8);

    private static final String ABI_REGISTER_LOCAL_APPLICATION =
            getABIFor(FUNCTION, "registerLocalApplication", CLPR_MIDDLEWARE);
    private static final String ABI_SET_TRUSTED_CALLBACK_CALLER =
            getABIFor(FUNCTION, "setTrustedCallbackCaller", CLPR_MIDDLEWARE);
    private static final String ABI_SET_CONNECTOR_REMOTE_MIDDLEWARE =
            getABIFor(FUNCTION, "setConnectorRemoteMiddleware", CLPR_MIDDLEWARE);
    private static final String ABI_REGISTER_WITH_MIDDLEWARE =
            getABIFor(FUNCTION, "registerWithMiddleware", CLPR_CONNECTOR);
    private static final String ABI_CONNECTOR_ID = getABIFor(FUNCTION, "connectorId", CLPR_CONNECTOR);
    private static final String ABI_CONNECTOR_ADMIN = getABIFor(FUNCTION, "admin", CLPR_CONNECTOR);
    private static final String ABI_SEND_WITH_FAILOVER_FROM_FIRST =
            getABIFor(FUNCTION, "sendWithFailoverFromFirst", SOURCE_APP);
    private static final String ABI_LAST_RECEIVED_APP_MSG_ID = getABIFor(FUNCTION, "lastReceivedAppMsgId", SOURCE_APP);
    private static final String ABI_GET_RESPONSE = getABIFor(FUNCTION, "getResponse", SOURCE_APP);
    private static final String ABI_GET_CONNECTOR_IDS = getABIFor(FUNCTION, "getConnectorIds", SOURCE_APP);
    private static final String ABI_CONNECTORS = getABIFor(FUNCTION, "connectors", CLPR_MIDDLEWARE);
    private static final String ABI_REQUEST_COUNT = getABIFor(FUNCTION, "requestCount", ECHO_APP);

    @Order(1)
    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = PRIVATE_LEDGER,
                        size = 1,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.devModeEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "5000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true"),
                            @ConfigOverride(key = "clpr.maxBundleMessages", value = "10"),
                            @ConfigOverride(key = "clpr.maxBundleBytes", value = "10240")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = PUBLIC_LEDGER,
                        size = 1,
                        firstGrpcPort = 36400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.devModeEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "1000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true"),
                            @ConfigOverride(key = "clpr.maxBundleMessages", value = "2"),
                            @ConfigOverride(key = "clpr.maxBundleBytes", value = "6144")
                        })
            })
    @DisplayName("Two-network middleware/system-contract round-trip")
    Stream<DynamicTest> twoNetworkMessagesExchange(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        /*
         * Specification: Two ledgers exchange one request/response pair twice through the full CLPR pipeline.
         *
         * Goal:
         * - Prove the middleware + queue system contract + native CLPR messaging layer execute an end-to-end
         *   round-trip between two subprocess networks.
         * - Prove repeatability (second request/response succeeds with the same deployment).
         * - Prove queue-state convergence (messages are consumed, not left stranded in outgoing queues).
         *
         * Stage 1: Bootstrap both ledgers with CLPR enabled and the queue system contract enabled.
         * Stage 2: Query each ledger's local ClprLedgerConfiguration and capture each ledger id.
         * Stage 3: Exchange configurations (A->B and B->A) via CLPR state-proof submission to trigger
         *          native endpoint discovery and remote queue metadata initialization.
         * Stage 4: Wait until each ledger reports message queue metadata for its remote peer.
         *
         * Stage 5 (destination/public deployment):
         * - Deploy ClprMiddleware on the destination ledger, configured with queue system contract address 0x16e.
         * - Deploy destination connector and register it with destination middleware.
         * - Deploy EchoApplication and register it as a local app on destination middleware.
         *
         * Stage 6 (source/private deployment):
         * - Deploy source ClprMiddleware on the source ledger, also bound to queue system contract 0x16e.
         * - Deploy source connector and register it with source middleware.
         * - Configure source connector's remote middleware pointer to destination middleware.
         * - Deploy SourceApplication with connector preference list.
         * - Register SourceApplication as a local app on source middleware.
         *
         * Stage 7: Finish destination connector pairing by setting destination connector's remote middleware
         *          pointer back to source middleware (bi-directional connector/middleware linkage complete).
         *
         * Stage 8 (message 1):
         * - Invoke SourceApplication.sendWithFailoverFromFirst(payload1) on source ledger.
         * - Assert queue metadata advances in the expected direction.
         * - Assert destination EchoApplication.requestCount >= 1.
         * - Assert source SourceApplication.lastReceivedAppMsgId advances and getResponse(appMsgId) == payload1.
         *
         * Stage 9 (message 2 / repeatability):
         * - Invoke SourceApplication.sendWithFailoverFromFirst(payload2) again.
         * - Assert destination EchoApplication.requestCount >= 2.
         * - Assert source response storage now contains payload2 at the new appMsgId.
         *
         * Stage 10 (queue convergence):
         * - Assert queue counters on both ledgers reflect sent/received progress.
         * - Assert outgoing queue is empty in both directions, proving consumption and no stranded work.
         *
         * Stage 11 (respect bundle shape)
         * - Assert that source leger respect the bunle limits set on the destination ledger
         *
         * Observability:
         * - The suite logs transaction records (including child records) and key intermediate snapshots
         *   so failures can be compared directly against this staged specification.
         */
        final var privateConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var publicConfig = new AtomicReference<ClprLedgerConfiguration>();

        final var privateLedgerId = new AtomicReference<byte[]>();
        final var publicLedgerId = new AtomicReference<byte[]>();

        final var privateMiddleware = new AtomicReference<String>();
        final var publicMiddleware = new AtomicReference<String>();
        final var privateMiddlewareContractId = new AtomicReference<String>();
        final var publicMiddlewareContractId = new AtomicReference<String>();
        final var privateMiddlewareId = new AtomicReference<ContractID>();
        final var publicMiddlewareId = new AtomicReference<ContractID>();
        final var privateConnector = new AtomicReference<String>();
        final var publicConnector = new AtomicReference<String>();
        final var privateConnectorContractId = new AtomicReference<String>();
        final var publicConnectorContractId = new AtomicReference<String>();
        final var privateConnectorId = new AtomicReference<ContractID>();
        final var publicConnectorId = new AtomicReference<ContractID>();
        final var privateSourceApp = new AtomicReference<String>();
        final var publicEchoApp = new AtomicReference<String>();
        final var privateSourceAppContractId = new AtomicReference<String>();
        final var publicEchoAppContractId = new AtomicReference<String>();
        final var privateSourceAppId = new AtomicReference<ContractID>();
        final var publicEchoAppId = new AtomicReference<ContractID>();

        final var appMsgIdOne = new AtomicLong(0L);
        final var appMsgIdTwo = new AtomicLong(0L);

        final var builder = multiNetworkHapiTest(netA, netB)
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    privateConfig.set(config);
                    privateLedgerId.set(asBytes32(config.ledgerIdOrThrow()));
                }))
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    publicConfig.set(config);
                    publicLedgerId.set(asBytes32(config.ledgerIdOrThrow()));
                }))

                // Kick off native messaging discovery/config exchange in both directions.
                .onNetwork(
                        PRIVATE_LEDGER,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(publicConfig.get()))))
                .onNetwork(
                        PUBLIC_LEDGER,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(privateConfig.get()))))
                .onNetwork(
                        PRIVATE_LEDGER,
                        withOpContext((spec, log) -> awaitMessageQueueMetadataAvailable(
                                log, spec.getNetworkNodes(), requireNonNull(publicConfig.get()))))
                .onNetwork(
                        PUBLIC_LEDGER,
                        withOpContext((spec, log) -> awaitMessageQueueMetadataAvailable(
                                log, spec.getNetworkNodes(), requireNonNull(privateConfig.get()))))

                // Deploy destination side (middleware + connector + echo app).
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    final var payerAddress = asHeadlongAddress(asAddress(asAccount(spec, 2)));
                    final var queueAddress = asHeadlongAddress(CLPR_QUEUE_SYSTEM_CONTRACT);
                    final var zeroAddress = asHeadlongAddress(ZERO_ADDRESS);

                    allRunFor(
                            spec,
                            uploadInitCode(CLPR_MIDDLEWARE, CLPR_CONNECTOR, SOURCE_APP, ECHO_APP),
                            contractCreate(CLPR_MIDDLEWARE, queueAddress, requireNonNull(publicLedgerId.get()))
                                    .gas(8_000_000L)
                                    .exposingContractIdTo(id -> {
                                        publicMiddleware.set(solidityIdFrom(id));
                                        publicMiddlewareContractId.set(asContractIdLiteral(id));
                                        publicMiddlewareId.set(id);
                                    }));
                    final var publicMiddlewareAddress =
                            asHeadlongAddress(asAddress(requireNonNull(publicMiddlewareId.get())));

                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(publicMiddlewareContractId.get()),
                                            ABI_SET_TRUSTED_CALLBACK_CALLER,
                                            payerAddress)
                                    .gas(500_000L),
                            contractCreate(
                                            CLPR_CONNECTOR,
                                            DESTINATION_CONNECTOR_ID,
                                            SOURCE_CONNECTOR_ID,
                                            requireNonNull(privateLedgerId.get()),
                                            UNIT,
                                            zeroAddress,
                                            BigInteger.ZERO,
                                            BigInteger.ZERO,
                                            BigInteger.ZERO,
                                            Tuple.of(BigInteger.ZERO, UNIT))
                                    .gas(6_000_000L)
                                    .balance(CONNECTOR_INITIAL_BALANCE_TINYBARS)
                                    .exposingContractIdTo(id -> {
                                        publicConnector.set(solidityIdFrom(id));
                                        publicConnectorContractId.set(asContractIdLiteral(id));
                                        publicConnectorId.set(id);
                                    }));
                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(publicConnectorContractId.get()),
                                            ABI_REGISTER_WITH_MIDDLEWARE,
                                            publicMiddlewareAddress)
                                    .gas(1_000_000L)
                                    .via("publicRegisterWithMiddleware"),
                            getTxnRecord("publicRegisterWithMiddleware")
                                    .andAllChildRecords()
                                    .logged(),
                            contractCreate(ECHO_APP, publicMiddlewareAddress)
                                    .gas(3_000_000L)
                                    .exposingContractIdTo(id -> {
                                        publicEchoApp.set(solidityIdFrom(id));
                                        publicEchoAppContractId.set(asContractIdLiteral(id));
                                        publicEchoAppId.set(id);
                                    }));
                    final var publicEchoAppAddress =
                            asHeadlongAddress(asAddress(requireNonNull(publicEchoAppId.get())));

                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(publicMiddlewareContractId.get()),
                                            ABI_REGISTER_LOCAL_APPLICATION,
                                            publicEchoAppAddress)
                                    .gas(500_000L));
                }))

                // Deploy source side (middleware + connector + source app).
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    final var payerAddress = asHeadlongAddress(asAddress(asAccount(spec, 2)));
                    final var queueAddress = asHeadlongAddress(CLPR_QUEUE_SYSTEM_CONTRACT);
                    final var zeroAddress = asHeadlongAddress(ZERO_ADDRESS);

                    allRunFor(
                            spec,
                            uploadInitCode(CLPR_MIDDLEWARE, CLPR_CONNECTOR, SOURCE_APP, ECHO_APP),
                            contractCreate(CLPR_MIDDLEWARE, queueAddress, requireNonNull(privateLedgerId.get()))
                                    .gas(8_000_000L)
                                    .exposingContractIdTo(id -> {
                                        privateMiddleware.set(solidityIdFrom(id));
                                        privateMiddlewareContractId.set(asContractIdLiteral(id));
                                        privateMiddlewareId.set(id);
                                    }));
                    final var privateMiddlewareAddress =
                            asHeadlongAddress(asAddress(requireNonNull(privateMiddlewareId.get())));

                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateMiddlewareContractId.get()),
                                            ABI_SET_TRUSTED_CALLBACK_CALLER,
                                            payerAddress)
                                    .gas(500_000L),
                            contractCreate(
                                            CLPR_CONNECTOR,
                                            SOURCE_CONNECTOR_ID,
                                            DESTINATION_CONNECTOR_ID,
                                            requireNonNull(publicLedgerId.get()),
                                            UNIT,
                                            zeroAddress,
                                            BigInteger.ZERO,
                                            BigInteger.ZERO,
                                            BigInteger.ZERO,
                                            Tuple.of(BigInteger.ZERO, UNIT))
                                    .gas(6_000_000L)
                                    .balance(CONNECTOR_INITIAL_BALANCE_TINYBARS)
                                    .exposingContractIdTo(id -> {
                                        privateConnector.set(solidityIdFrom(id));
                                        privateConnectorContractId.set(asContractIdLiteral(id));
                                        privateConnectorId.set(id);
                                    }));
                    final var publicMiddlewareAddress =
                            asHeadlongAddress(asAddress(requireNonNull(publicMiddlewareId.get())));
                    final var publicEchoAppAddress =
                            asHeadlongAddress(asAddress(requireNonNull(publicEchoAppId.get())));
                    final var connectorIdFromContract = new AtomicReference<Object[]>();
                    final var connectorAdminFromContract = new AtomicReference<Object[]>();

                    allRunFor(
                            spec,
                            contractCallLocalWithFunctionAbi(
                                            requireNonNull(privateConnectorContractId.get()), ABI_CONNECTOR_ID)
                                    .gas(500_000L)
                                    .exposingTypedResultsTo(connectorIdFromContract::set),
                            contractCallLocalWithFunctionAbi(
                                            requireNonNull(privateConnectorContractId.get()), ABI_CONNECTOR_ADMIN)
                                    .gas(500_000L)
                                    .exposingTypedResultsTo(connectorAdminFromContract::set));

                    log.info(
                            "CLPR_TEST_OBS|component=clpr_messages_suite|stage=post_source_connector_create|connectorId={}|admin={}",
                            Arrays.deepToString(connectorIdFromContract.get()),
                            Arrays.deepToString(connectorAdminFromContract.get()));
                    assertThat(connectorIdFromContract.get()).isNotNull();
                    assertThat((byte[]) connectorIdFromContract.get()[0]).isEqualTo(SOURCE_CONNECTOR_ID);

                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateConnectorContractId.get()),
                                            ABI_REGISTER_WITH_MIDDLEWARE,
                                            privateMiddlewareAddress)
                                    .gas(1_000_000L)
                                    .via("privateRegisterWithMiddleware"),
                            getTxnRecord("privateRegisterWithMiddleware").logged(),
                            contractCallLocalWithFunctionAbi(
                                            requireNonNull(privateMiddlewareContractId.get()),
                                            ABI_CONNECTORS,
                                            SOURCE_CONNECTOR_ID)
                                    .gas(500_000L)
                                    .exposingTypedResultsTo(connectorSnapshot -> log.info(
                                            "CLPR_TEST_OBS|component=clpr_messages_suite|stage=pre_set_remote_middleware_connector_snapshot|value={}",
                                            Arrays.deepToString(connectorSnapshot))),
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateMiddlewareContractId.get()),
                                            ABI_SET_CONNECTOR_REMOTE_MIDDLEWARE,
                                            SOURCE_CONNECTOR_ID,
                                            publicMiddlewareAddress)
                                    .gas(500_000L)
                                    .via("privateSetConnectorRemoteMiddleware"),
                            getTxnRecord("privateSetConnectorRemoteMiddleware").logged(),
                            contractCreate(
                                            SOURCE_APP,
                                            privateMiddlewareAddress,
                                            publicEchoAppAddress,
                                            (Object) new byte[][] {SOURCE_CONNECTOR_ID},
                                            BigInteger.ZERO,
                                            UNIT)
                                    .gas(4_000_000L)
                                    .exposingContractIdTo(id -> {
                                        privateSourceApp.set(solidityIdFrom(id));
                                        privateSourceAppContractId.set(asContractIdLiteral(id));
                                        privateSourceAppId.set(id);
                                    }));

                    final var connectorSnapshot = new AtomicReference<Object[]>();
                    allRunFor(
                            spec,
                            contractCallLocalWithFunctionAbi(
                                            requireNonNull(privateMiddlewareContractId.get()),
                                            ABI_CONNECTORS,
                                            SOURCE_CONNECTOR_ID)
                                    .gas(500_000L)
                                    .exposingTypedResultsTo(connectorSnapshot::set));
                    log.info(
                            "CLPR_TEST_OBS|component=clpr_messages_suite|stage=post_source_deploy_connector_snapshot|value={}",
                            Arrays.deepToString(connectorSnapshot.get()));
                    assertThat(connectorSnapshot.get()).isNotNull();
                    assertThat((Boolean) connectorSnapshot.get()[6]).isTrue();
                    final var privateSourceAppAddress = requireNonNull(privateSourceApp.get());
                    final var privateSourceAppHeadlong =
                            asHeadlongAddress(asAddress(requireNonNull(privateSourceAppId.get())));

                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateMiddlewareContractId.get()),
                                            ABI_REGISTER_LOCAL_APPLICATION,
                                            privateSourceAppHeadlong)
                                    .gas(500_000L));
                }))

                // Complete destination-side connector pairing once source middleware address is known.
                .onNetwork(
                        PUBLIC_LEDGER,
                        withOpContext((spec, log) -> allRunFor(
                                spec,
                                contractCallWithFunctionAbi(
                                                requireNonNull(publicMiddlewareContractId.get()),
                                                ABI_SET_CONNECTOR_REMOTE_MIDDLEWARE,
                                                DESTINATION_CONNECTOR_ID,
                                                asHeadlongAddress(asAddress(requireNonNull(privateMiddlewareId.get()))))
                                        .gas(500_000L))))

                // Send first request and capture app message id.
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    final var firstSendTxn = "firstSendWithFailover";
                    final var connectorIds = new AtomicReference<Object[]>();
                    final var connectorConfig = new AtomicReference<Object[]>();
                    allRunFor(
                            spec,
                            contractCallLocalWithFunctionAbi(
                                            requireNonNull(privateSourceAppContractId.get()), ABI_GET_CONNECTOR_IDS)
                                    .gas(500_000L)
                                    .exposingTypedResultsTo(connectorIds::set),
                            contractCallLocalWithFunctionAbi(
                                            requireNonNull(privateMiddlewareContractId.get()),
                                            ABI_CONNECTORS,
                                            SOURCE_CONNECTOR_ID)
                                    .gas(500_000L)
                                    .exposingTypedResultsTo(connectorConfig::set),
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateSourceAppContractId.get()),
                                            ABI_SEND_WITH_FAILOVER_FROM_FIRST,
                                            PAYLOAD_ONE)
                                    .gas(5_000_000L)
                                    .via(firstSendTxn),
                            getTxnRecord(firstSendTxn)
                                    .andAllChildRecords()
                                    .logged()
                                    .exposingAllTo(records -> {
                                        log.info(
                                                "CLPR_TEST_OBS|component=clpr_messages_suite|stage=pre_send_snapshot|connectorIds={}|connectorConfig={}|dryRun=skipped",
                                                Arrays.deepToString(connectorIds.get()),
                                                Arrays.deepToString(connectorConfig.get()));
                                        log.info(
                                                "CLPR_TEST_OBS|component=clpr_messages_suite|stage=first_send_records|count={}",
                                                records.size());
                                        for (int i = 0; i < records.size(); i++) {
                                            final var rec = records.get(i);
                                            final var result = rec.getContractCallResult();
                                            log.info(
                                                    "CLPR_TEST_OBS|component=clpr_messages_suite|stage=first_send_record|index={}|status={}|gasUsed={}|resultBytes={}|errorMessage={}",
                                                    i,
                                                    rec.getReceipt().getStatus(),
                                                    result.getGasUsed(),
                                                    result.getContractCallResult()
                                                            .size(),
                                                    result.getErrorMessage());
                                        }
                                    }));
                    awaitMessageQueueCountersAtLeast(
                            log, spec.getNetworkNodes(), requireNonNull(publicConfig.get()), 0L, 1L);
                }))

                // Verify destination app handled the request and source app received the response.
                .onNetwork(
                        PUBLIC_LEDGER,
                        withOpContext((spec, log) -> awaitUint64AtLeast(
                                spec,
                                log,
                                requireNonNull(publicEchoAppContractId.get()),
                                ABI_REQUEST_COUNT,
                                1L,
                                "echo requestCount")))
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    final var receivedAppMsgId = awaitUint64AtLeast(
                            spec,
                            log,
                            requireNonNull(privateSourceAppContractId.get()),
                            ABI_LAST_RECEIVED_APP_MSG_ID,
                            1L,
                            "source lastReceivedAppMsgId");
                    appMsgIdOne.set(receivedAppMsgId);
                    final var response = queryBytes(
                            spec,
                            requireNonNull(privateSourceAppContractId.get()),
                            ABI_GET_RESPONSE,
                            BigInteger.valueOf(appMsgIdOne.get()));
                    assertThat(response).isEqualTo(PAYLOAD_ONE);
                }))

                // Send second request to prove repeatability.
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateSourceAppContractId.get()),
                                            ABI_SEND_WITH_FAILOVER_FROM_FIRST,
                                            PAYLOAD_TWO)
                                    .gas(5_000_000L));
                    awaitMessageQueueCountersAtLeast(
                            log, spec.getNetworkNodes(), requireNonNull(publicConfig.get()), 0L, 2L);
                }))
                .onNetwork(
                        PUBLIC_LEDGER,
                        withOpContext((spec, log) -> awaitUint64AtLeast(
                                spec,
                                log,
                                requireNonNull(publicEchoAppContractId.get()),
                                ABI_REQUEST_COUNT,
                                2L,
                                "echo requestCount")))
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    final var receivedAppMsgId = awaitUint64AtLeast(
                            spec,
                            log,
                            requireNonNull(privateSourceAppContractId.get()),
                            ABI_LAST_RECEIVED_APP_MSG_ID,
                            appMsgIdOne.get() + 1,
                            "source lastReceivedAppMsgId");
                    appMsgIdTwo.set(receivedAppMsgId);
                    final var response = queryBytes(
                            spec,
                            requireNonNull(privateSourceAppContractId.get()),
                            ABI_GET_RESPONSE,
                            BigInteger.valueOf(appMsgIdTwo.get()));
                    assertThat(response).isEqualTo(PAYLOAD_TWO);
                }))

                // Queue-state assertions prove request/response were consumed, not left queued.
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    awaitMessageQueueCountersAtLeast(
                            log, spec.getNetworkNodes(), requireNonNull(publicConfig.get()), 2L, 2L);
                    awaitEmptyOutgoingQueue(log, spec.getNetworkNodes(), requireNonNull(publicConfig.get()));
                }))
                .onNetwork(PUBLIC_LEDGER, withOpContext((spec, log) -> {
                    awaitMessageQueueCountersAtLeast(
                            log, spec.getNetworkNodes(), requireNonNull(privateConfig.get()), 2L, 2L);
                    awaitEmptyOutgoingQueue(log, spec.getNetworkNodes(), requireNonNull(privateConfig.get()));
                }))

                // Validate bundle shape is respected by remote
                .onNetwork(PRIVATE_LEDGER, withOpContext((spec, log) -> {
                    // submit 3 msgs
                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateSourceAppContractId.get()),
                                            ABI_SEND_WITH_FAILOVER_FROM_FIRST,
                                            "hello-clpr-3".getBytes(StandardCharsets.UTF_8))
                                    .gas(5_000_000L),
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateSourceAppContractId.get()),
                                            ABI_SEND_WITH_FAILOVER_FROM_FIRST,
                                            "hello-clpr-4".getBytes(StandardCharsets.UTF_8))
                                    .gas(5_000_000L),
                            contractCallWithFunctionAbi(
                                            requireNonNull(privateSourceAppContractId.get()),
                                            ABI_SEND_WITH_FAILOVER_FROM_FIRST,
                                            "hello-clpr-5".getBytes(StandardCharsets.UTF_8))
                                    .gas(5_000_000L));
                    awaitMessageQueueCountersAtLeast(
                            log, spec.getNetworkNodes(), requireNonNull(publicConfig.get()), 5L, 5L);
                    // TODO Assert the biggest bundle contains 2 msgs
                }));

        return builder.asDynamicTests();
    }

    private static HederaNode getFirstNode(final HapiSpec spec) {
        return spec.getNetworkNodes().getFirst();
    }

    private static long awaitUint64AtLeast(
            final HapiSpec spec,
            final Logger log,
            final String contractEvmAddress,
            final String functionAbi,
            final long minimum,
            final String label) {
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        long last = -1;
        do {
            final var observed = new AtomicReference<Object[]>();
            allRunFor(
                    spec,
                    contractCallLocalWithFunctionAbi(contractEvmAddress, functionAbi)
                            .gas(500_000L)
                            .exposingTypedResultsTo(observed::set));
            final var typed = observed.get();
            if (typed != null && typed.length > 0) {
                last = toLong(typed[0]);
                if (last >= minimum) {
                    return last;
                }
            }
            sleepQuietly(AWAIT_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for " + label + " >= " + minimum + " (last=" + last + ")");
    }

    private static byte[] queryBytes(
            final HapiSpec spec, final String contractEvmAddress, final String functionAbi, final Object... params) {
        final var observed = new AtomicReference<Object[]>();
        allRunFor(
                spec,
                contractCallLocalWithFunctionAbi(contractEvmAddress, functionAbi, params)
                        .gas(500_000L)
                        .exposingTypedResultsTo(observed::set));
        final var typed = observed.get();
        assertThat(typed).isNotNull();
        assertThat(typed).hasSizeGreaterThanOrEqualTo(1);
        return (byte[]) typed[0];
    }

    private static ClprLedgerConfiguration awaitLocalLedgerConfiguration(final List<HederaNode> nodes) {
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        do {
            for (final var node : nodes) {
                final var config = tryFetchLocalLedgerConfiguration(node);
                if (config != null) {
                    return config;
                }
            }
            sleepQuietly(AWAIT_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for local CLPR ledger configuration");
    }

    private static void awaitMessageQueueMetadataAvailable(
            final Logger log, final List<HederaNode> nodes, final ClprLedgerConfiguration remoteConfiguration) {
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        do {
            for (final var node : nodes) {
                final var metadata = tryFetchMessageQueueMetadata(node, remoteConfiguration);
                if (metadata != null) {
                    return;
                }
            }
            sleepQuietly(AWAIT_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for queue metadata for remote ledger "
                + remoteConfiguration.ledgerId().ledgerId());
    }

    private static void awaitMessageQueueCountersAtLeast(
            final Logger log,
            final List<HederaNode> nodes,
            final ClprLedgerConfiguration remoteConfiguration,
            final long minReceivedMessageId,
            final long minSentMessageId) {
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        ClprMessageQueueMetadata last = null;
        do {
            for (final var node : nodes) {
                final var metadata = tryFetchMessageQueueMetadata(node, remoteConfiguration);
                if (metadata != null) {
                    last = metadata;
                    if (metadata.receivedMessageId() >= minReceivedMessageId
                            && metadata.sentMessageId() >= minSentMessageId) {
                        return;
                    }
                }
            }
            sleepQuietly(AWAIT_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for queue counters on "
                + remoteConfiguration.ledgerId().ledgerId() + " (last=" + last + ")");
    }

    private static void awaitEmptyOutgoingQueue(
            final Logger log, final List<HederaNode> nodes, final ClprLedgerConfiguration remoteConfiguration) {
        requireNonNull(nodes);
        requireNonNull(remoteConfiguration);
        final var deadline = Instant.now().plus(AWAIT_TIMEOUT);
        do {
            for (final var node : nodes) {
                try (final var client = createClient(node)) {
                    final var bundle = client.getMessages(remoteConfiguration.ledgerId(), 1, 1000);
                    if (bundle == null) {
                        return;
                    }
                }
            }
            sleepQuietly(AWAIT_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for empty message queue of ledger "
                + remoteConfiguration.ledgerId().ledgerId());
    }

    private static ClprMessageQueueMetadata tryFetchMessageQueueMetadata(
            final HederaNode node, final ClprLedgerConfiguration clprLedgerConfiguration) {
        try (final var client = createClient(node)) {
            final var proof = client.getMessageQueueMetadata(clprLedgerConfiguration.ledgerId());
            if (proof == null) {
                return null;
            }
            return extractMessageQueueMetadata(proof);
        }
    }

    private static ClprLedgerConfiguration tryFetchLocalLedgerConfiguration(final HederaNode node) {
        try (final var client = createClient(node)) {
            final var proof = client.getConfiguration();
            if (proof == null) {
                return null;
            }
            return extractConfiguration(proof);
        }
    }

    private static void submitConfiguration(
            final HapiSpec spec, final HederaNode node, final ClprLedgerConfiguration configuration) {
        final var payer = toPbj(asAccount(spec, 2));
        final var proof = buildLocalClprStateProofWrapper(configuration);
        try (var client = createClient(node)) {
            client.setConfiguration(payer, node.getAccountId(), proof);
        }
    }

    private static byte[] asBytes32(final ClprLedgerId ledgerId) {
        final var bytes = ledgerId.ledgerId().toByteArray();
        if (bytes.length != 32) {
            throw new IllegalStateException("Expected 32-byte ledger id, got " + bytes.length);
        }
        return bytes;
    }

    private static byte[] bytes32(final String suffixHex) {
        final var value = new byte[32];
        final var suffix = HexFormat.of().parseHex(suffixHex.length() % 2 == 0 ? suffixHex : "0" + suffixHex);
        System.arraycopy(suffix, 0, value, 32 - suffix.length, suffix.length);
        return value;
    }

    private static long toLong(final Object value) {
        if (value instanceof BigInteger bigInteger) {
            return bigInteger.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Unsupported numeric type: " + value);
    }

    private static String asContractIdLiteral(final com.hederahashgraph.api.proto.java.ContractID contractId) {
        return contractId.getShardNum() + "." + contractId.getRealmNum() + "." + contractId.getContractNum();
    }

    private static Address asHeadlongAddress(final String hexAddress) {
        return com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress(hexAddress);
    }

    private static Address asHeadlongAddress(final byte[] address) {
        return com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress(address);
    }

    private static void sleepQuietly(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for CLPR condition", e);
        }
    }
}
