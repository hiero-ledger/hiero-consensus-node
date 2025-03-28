// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static com.hedera.node.app.spi.AppContext.Gossip.UNAVAILABLE_GOSSIP;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.MAX_SIGNED_TXN_SIZE_PROPERTY;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.RealmID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.ShardID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.AppThrottleFactory;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.internal.CryptoUtils;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.apache.tuweni.bytes.Bytes32;
import org.hiero.consensus.model.node.NodeId;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.AbstractOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the ability to execute transactions against a standalone state by,
 * <ol>
 *   <li>Constructing a {@link FakeState} that fully implements the {@link State} API, with {@link WritableStates}
 *   that are all {@link CommittableWritableStates}; and hence accumulate changes as multiple transactions are
 *   executed.</li>
 *   <li>Executing a {@link HederaFunctionality#FILE_CREATE} to upload some contract initcode.</li>
 *   <li>Executing a {@link HederaFunctionality#CONTRACT_CREATE} to create an instance of the contract.</li>
 *   <li>Executing a {@link HederaFunctionality#CONTRACT_CALL} to call a function on the contract, and capturing
 *   the output of a {@link StandardJsonTracer} that is passed in as an extra argument.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
public class TransactionExecutorsTest {
    private static final long GAS = 100_000L;
    private static final long EXPECTED_LUCKY_NUMBER = 42L;
    private static final EntityIdFactory idFactory = new AppEntityIdFactory(DEFAULT_CONFIG);
    private static final AccountID TREASURY_ID = idFactory.newAccountId(2);
    private static final AccountID NODE_ACCOUNT_ID = idFactory.newAccountId(3);
    private static final FileID EXPECTED_INITCODE_ID = idFactory.newFileId(1001);
    private static final ContractID EXPECTED_CONTRACT_ID = idFactory.newContractId(1002);
    private static final com.esaulpaugh.headlong.abi.Function PICK_FUNCTION =
            new com.esaulpaugh.headlong.abi.Function("pick()", "(uint32)");
    private static final com.esaulpaugh.headlong.abi.Function GET_LAST_BLOCKHASH_FUNCTION =
            new com.esaulpaugh.headlong.abi.Function("getLastBlockHash()", "(bytes32)");
    private static final String EXPECTED_TRACE_START =
            "{\"pc\":0,\"op\":96,\"gas\":\"0x13458\",\"gasCost\":\"0x3\",\"memSize\":0,\"depth\":1,\"refund\":0,\"opName\":\"PUSH1\"}";
    private static final NodeInfo DEFAULT_NODE_INFO =
            new NodeInfoImpl(0, idFactory.newAccountId(3L), 10, List.of(), Bytes.EMPTY, List.of());

    public static final Metrics NO_OP_METRICS = new NoOpMetrics();
    public static final NetworkInfo FAKE_NETWORK_INFO = fakeNetworkInfo();

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private TransactionExecutors.TracerBinding tracerBinding;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private State state;

    @Mock
    private ConfigProviderImpl configProvider;

    @Mock
    private StoreMetricsServiceImpl storeMetricsService;

    @Test
    void executesTransactionsAsExpected() {
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101");
        // Construct a full implementation of the consensus node State API with all genesis accounts and files
        final var state = genesisState(overrides);

        // Get a standalone executor based on this state, with an override to allow slightly longer memos
        final var executor = TRANSACTION_EXECUTORS.newExecutor(
                TransactionExecutors.Properties.newBuilder()
                        .state(state)
                        .appProperties(overrides)
                        .build(),
                new AppEntityIdFactory(DEFAULT_CONFIG));

        // Execute a FileCreate that uploads the initcode for the Multipurpose.sol contract
        final var uploadOutput = executor.execute(uploadMultipurposeInitcode(), Instant.EPOCH);
        final var uploadReceipt = uploadOutput.getFirst().transactionRecord().receiptOrThrow();
        assertThat(uploadReceipt.fileIDOrThrow()).isEqualTo(EXPECTED_INITCODE_ID);

        // Execute a ContractCreate that creates a Multipurpose contract instance
        final var creationOutput = executor.execute(createContract(), Instant.EPOCH);
        final var creationReceipt =
                creationOutput.getFirst().transactionRecord().receiptOrThrow();
        assertThat(creationReceipt.contractIDOrThrow()).isEqualTo(EXPECTED_CONTRACT_ID);

        // Now execute a ContractCall against the contract, with an extra StandardJsonTracer whose output we
        // capture in a StringWriter for later inspection
        final var stringWriter = new StringWriter();
        final var printWriter = new PrintWriter(stringWriter);
        final var addOnTracer = new StandardJsonTracer(printWriter, false, false, false, false);
        final var callOutput = executor.execute(contractCallMultipurposePickFunction(), Instant.EPOCH, addOnTracer);
        final var callRecord = callOutput.getFirst().transactionRecord();
        final var callResult = callRecord.contractCallResultOrThrow().contractCallResult();
        final long luckyNumber =
                PICK_FUNCTION.getOutputs().decode(callResult.toByteArray()).get(0);
        assertThat(luckyNumber).isEqualTo(EXPECTED_LUCKY_NUMBER);
        printWriter.flush();
        assertThat(stringWriter.toString()).startsWith(EXPECTED_TRACE_START);
    }

    @Test
    void usesOverrideBlockhashOpAsExpected() {
        final var state = genesisState(Map.of());
        final var writableStates = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoSingleton = writableStates.<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY);
        blockInfoSingleton.put(requireNonNull(blockInfoSingleton.get())
                .copyBuilder()
                .lastBlockNumber(666L)
                .build());
        ((CommittableWritableStates) writableStates).commit();

        // Use a custom operation that overrides the BLOCKHASH operation
        final var customOp = new CustomBlockhashOperation();
        final var executor = TRANSACTION_EXECUTORS.newExecutor(
                TransactionExecutors.Properties.newBuilder()
                        .state(state)
                        .addCustomOp(customOp)
                        .appProperty("hedera.transaction.maxMemoUtf8Bytes", "101")
                        .build(),
                new AppEntityIdFactory(DEFAULT_CONFIG));

        final var uploadOutput = executor.execute(uploadEmitBlockTimestampInitcode(), Instant.EPOCH);
        final var uploadReceipt = uploadOutput.getFirst().transactionRecord().receiptOrThrow();
        assertThat(uploadReceipt.fileIDOrThrow()).isEqualTo(EXPECTED_INITCODE_ID);

        final var creationOutput = executor.execute(createContract(), Instant.EPOCH);
        final var creationReceipt =
                creationOutput.getFirst().transactionRecord().receiptOrThrow();
        assertThat(creationReceipt.contractIDOrThrow()).isEqualTo(EXPECTED_CONTRACT_ID);

        final var callOutput = executor.execute(contractCallGetLastBlockHashFunction(), Instant.EPOCH);
        final var callRecord = callOutput.getFirst().transactionRecord();
        final var callResult = callRecord.contractCallResultOrThrow().contractCallResult();
        final byte[] blockHash = GET_LAST_BLOCKHASH_FUNCTION
                .getOutputs()
                .decode(callResult.toByteArray())
                .get(0);
        assertThat(Bytes32.wrap(blockHash)).isEqualTo(CustomBlockhashOperation.FAKE_BLOCK_HASH);
    }

    @Test
    void respectsOverrideMaxSignedTxnSize() {
        final var overrides = Map.of(MAX_SIGNED_TXN_SIZE_PROPERTY, "42");
        // Construct a full implementation of the consensus node State API with all genesis accounts and files
        final var state = genesisState(overrides);

        // Get a standalone executor based on this state, with an override to allow slightly longer memos
        final var executor = TRANSACTION_EXECUTORS.newExecutor(
                TransactionExecutors.Properties.newBuilder()
                        .state(state)
                        .appProperties(overrides)
                        .build(),
                new AppEntityIdFactory(DEFAULT_CONFIG));

        // With just 42 bytes allowed for signed transactions, the executor will not be able to construct
        // a dispatch for the transaction and throw an exception
        assertThrows(NullPointerException.class, () -> executor.execute(uploadMultipurposeInitcode(), Instant.EPOCH));
    }

    @Test
    void propertiesBuilderRequiresNonNullState() {
        assertThrows(IllegalStateException.class, () -> TransactionExecutors.Properties.newBuilder()
                .build());
    }

    @Test
    void propertiesBuilderBulkOptionsAsExpected() {
        final var customOps = Set.of(new CustomBlockhashOperation());
        final var appProperties = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101");
        final var properties = TransactionExecutors.Properties.newBuilder()
                .customOps(customOps)
                .appProperties(appProperties)
                .customTracerBinding(tracerBinding)
                .state(state)
                .build();

        assertThat(properties.customOps()).isEqualTo(customOps);
        assertThat(properties.appProperties()).isEqualTo(appProperties);
        assertThat(properties.customTracerBinding()).isEqualTo(tracerBinding);
    }

    private TransactionBody contractCallMultipurposePickFunction() {
        final var callData = PICK_FUNCTION.encodeCallWithArgs();
        return newBodyBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(EXPECTED_CONTRACT_ID)
                        .functionParameters(Bytes.wrap(callData.array()))
                        .gas(GAS)
                        .build())
                .build();
    }

    private TransactionBody contractCallGetLastBlockHashFunction() {
        final var callData = GET_LAST_BLOCKHASH_FUNCTION.encodeCallWithArgs();
        return newBodyBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(EXPECTED_CONTRACT_ID)
                        .functionParameters(Bytes.wrap(callData.array()))
                        .gas(GAS)
                        .build())
                .build();
    }

    private TransactionBody createContract() {
        final var maxLifetime =
                DEFAULT_CONFIG.getConfigData(EntitiesConfig.class).maxLifetime();
        final var shard = DEFAULT_CONFIG.getConfigData(HederaConfig.class).shard();
        final var realm = DEFAULT_CONFIG.getConfigData(HederaConfig.class).realm();

        return newBodyBuilder()
                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                        .fileID(EXPECTED_INITCODE_ID)
                        .autoRenewPeriod(new Duration(maxLifetime))
                        .gas(GAS)
                        .shardID(new ShardID(shard))
                        .realmID(new RealmID(shard, realm))
                        .build())
                .build();
    }

    private TransactionBody uploadMultipurposeInitcode() {
        final var maxLifetime =
                DEFAULT_CONFIG.getConfigData(EntitiesConfig.class).maxLifetime();
        return newBodyBuilder()
                .fileCreate(FileCreateTransactionBody.newBuilder()
                        .contents(resourceAsBytes("initcode/Multipurpose.bin"))
                        .keys(IMMUTABILITY_SENTINEL_KEY.keyListOrThrow())
                        .expirationTime(new Timestamp(maxLifetime, 0))
                        .build())
                .build();
    }

    private TransactionBody uploadEmitBlockTimestampInitcode() {
        final var maxLifetime =
                DEFAULT_CONFIG.getConfigData(EntitiesConfig.class).maxLifetime();
        return newBodyBuilder()
                .fileCreate(FileCreateTransactionBody.newBuilder()
                        .contents(resourceAsBytes("initcode/EmitBlockTimestamp.bin"))
                        .keys(IMMUTABILITY_SENTINEL_KEY.keyListOrThrow())
                        .expirationTime(new Timestamp(maxLifetime, 0))
                        .build())
                .build();
    }

    private TransactionBody.Builder newBodyBuilder() {
        final var minValidDuration =
                DEFAULT_CONFIG.getConfigData(HederaConfig.class).transactionMinValidDuration();
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(new Timestamp(0, 0))
                        .accountID(TREASURY_ID)
                        .build())
                .memo(
                        "This memo is 101 characters long, which with default settings would die with the status MEMO_TOO_LONG")
                .nodeAccountID(NODE_ACCOUNT_ID)
                .transactionValidDuration(new Duration(minValidDuration));
    }

    private MerkleNodeState genesisState(@NonNull final Map<String, String> overrides) {
        final var state = new FakeState();
        final var configBuilder = HederaTestConfigBuilder.create();
        overrides.forEach(configBuilder::withValue);
        final var config = configBuilder.getOrCreateConfig();
        final var networkInfo = fakeNetworkInfo();
        final var servicesRegistry = new FakeServicesRegistry();
        final var appContext = new AppContextImpl(
                InstantSource.system(),
                signatureVerifier,
                UNAVAILABLE_GOSSIP,
                () -> config,
                () -> DEFAULT_NODE_INFO,
                () -> NO_OP_METRICS,
                new AppThrottleFactory(
                        () -> config,
                        () -> state,
                        () -> ThrottleDefinitions.DEFAULT,
                        ThrottleAccumulator::new,
                        v -> new ServicesSoftwareVersion()),
                () -> NOOP_FEE_CHARGING,
                new AppEntityIdFactory(config));
        registerServices(appContext, servicesRegistry);
        final var migrator = new FakeServiceMigrator();
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        migrator.doMigrations(
                state,
                servicesRegistry,
                null,
                new ServicesSoftwareVersion(
                        bootstrapConfig.getConfigData(VersionConfig.class).servicesVersion()),
                new ConfigProviderImpl().getConfiguration(),
                config,
                NO_OP_METRICS,
                startupNetworks,
                storeMetricsService,
                configProvider,
                TEST_PLATFORM_STATE_FACADE);
        // Create a node
        final var nodeWritableStates = state.getWritableStates(AddressBookService.NAME);
        final var nodes = nodeWritableStates.<EntityNumber, Node>get(NODES_KEY);
        nodes.put(
                new EntityNumber(0),
                Node.newBuilder()
                        .accountId(appContext.idFactory().newAccountId(3L))
                        .build());
        ((CommittableWritableStates) nodeWritableStates).commit();
        final var writableStates = state.getWritableStates(FileService.NAME);
        final var readableStates = state.getReadableStates(AddressBookService.NAME);
        final var entityIdStore = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
        final var nodeStore = new ReadableNodeStoreImpl(readableStates, entityIdStore);
        final var files = writableStates.<FileID, File>get(V0490FileSchema.BLOBS_KEY);
        genesisContentProviders(nodeStore, config).forEach((fileNum, provider) -> {
            final var fileId = createFileID(fileNum, config);
            files.put(
                    fileId,
                    File.newBuilder()
                            .fileId(fileId)
                            .keys(KeyList.DEFAULT)
                            .contents(provider.apply(config))
                            .build());
        });
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var accountsConfig = config.getConfigData(AccountsConfig.class);
        final var systemKey = Key.newBuilder()
                .ed25519(config.getConfigData(BootstrapConfig.class).genesisPublicKey())
                .build();
        final var accounts =
                state.getWritableStates(TokenService.NAME).<AccountID, Account>get(V0490TokenSchema.ACCOUNTS_KEY);
        // Create the system accounts
        for (int i = 1, n = ledgerConfig.numSystemAccounts(); i <= n; i++) {
            final var accountId = AccountID.newBuilder().accountNum(i).build();
            accounts.put(
                    accountId,
                    Account.newBuilder()
                            .accountId(accountId)
                            .key(systemKey)
                            .expirationSecond(Long.MAX_VALUE)
                            .tinybarBalance(
                                    (long) i == accountsConfig.treasury() ? ledgerConfig.totalTinyBarFloat() : 0L)
                            .build());
        }
        ((CommittableWritableStates) writableStates).commit();
        return state;
    }

    private Map<Long, Function<Configuration, Bytes>> genesisContentProviders(
            @NonNull final ReadableNodeStore nodeStore, @NonNull final Configuration config) {
        final var genesisSchema = new V0490FileSchema();
        final var filesConfig = config.getConfigData(FilesConfig.class);
        return Map.of(
                filesConfig.addressBook(), ignore -> genesisSchema.nodeStoreAddressBook(nodeStore),
                filesConfig.nodeDetails(), ignore -> genesisSchema.nodeStoreNodeDetails(nodeStore),
                filesConfig.feeSchedules(), genesisSchema::genesisFeeSchedules,
                filesConfig.exchangeRates(), genesisSchema::genesisExchangeRates,
                filesConfig.networkProperties(), genesisSchema::genesisNetworkProperties,
                filesConfig.hapiPermissions(), genesisSchema::genesisHapiPermissions,
                filesConfig.throttleDefinitions(), genesisSchema::genesisThrottleDefinitions);
    }

    private void registerServices(
            @NonNull final AppContext appContext, @NonNull final ServicesRegistry servicesRegistry) {
        // Register all service schema RuntimeConstructable factories before platform init
        Set.of(
                        new EntityIdService(),
                        new ConsensusServiceImpl(),
                        new ContractServiceImpl(appContext, NO_OP_METRICS),
                        new FileServiceImpl(),
                        new FreezeServiceImpl(),
                        new ScheduleServiceImpl(appContext),
                        new TokenServiceImpl(appContext),
                        new UtilServiceImpl(appContext, (signedTxn, config) -> null),
                        new RecordCacheService(),
                        new BlockRecordService(),
                        new FeeService(),
                        new CongestionThrottleService(),
                        new NetworkServiceImpl(),
                        new AddressBookServiceImpl())
                .forEach(servicesRegistry::register);
    }

    private static NetworkInfo fakeNetworkInfo() {
        final AccountID someAccount = idFactory.newAccountId(12345);
        final var addressBook = new AddressBook(StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(
                                RandomAddressBookBuilder.create(new Random())
                                        .withSize(1)
                                        .withRealKeysEnabled(true)
                                        .build()
                                        .iterator(),
                                0),
                        false)
                .map(address ->
                        address.copySetMemo("0.0." + (address.getNodeId().id() + 3)))
                .toList());
        return new NetworkInfo() {
            @NonNull
            @Override
            public Bytes ledgerId() {
                throw new UnsupportedOperationException("Not implemented");
            }

            @NonNull
            @Override
            public NodeInfo selfNodeInfo() {
                return new NodeInfoImpl(
                        0,
                        someAccount,
                        0,
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        getCertBytes(randomX509Certificate()),
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT));
            }

            @NonNull
            @Override
            public List<NodeInfo> addressBook() {
                return List.of(new NodeInfoImpl(
                        0,
                        someAccount,
                        0,
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        getCertBytes(randomX509Certificate()),
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT)));
            }

            @Override
            public NodeInfo nodeInfo(final long nodeId) {
                return new NodeInfoImpl(
                        0,
                        someAccount,
                        0,
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        Bytes.EMPTY,
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT));
            }

            @Override
            public boolean containsNode(final long nodeId) {
                return addressBook.contains(NodeId.of(nodeId));
            }

            @Override
            public void updateFrom(final State state) {
                throw new UnsupportedOperationException("Not implemented");
            }
        };
    }

    private Bytes resourceAsBytes(@NonNull final String loc) {
        try {
            try (final var in = TransactionExecutorsTest.class.getClassLoader().getResourceAsStream(loc)) {
                final var bytes = requireNonNull(in).readAllBytes();
                return Bytes.wrap(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static X509Certificate randomX509Certificate() {
        try {
            final SecureRandom secureRandom = CryptoUtils.getDetRandom();

            final KeyPairGenerator rsaKeyGen = KeyPairGenerator.getInstance("RSA");
            rsaKeyGen.initialize(3072, secureRandom);
            final KeyPair rsaKeyPair1 = rsaKeyGen.generateKeyPair();

            final String name = "CN=Bob";
            return CryptoStatic.generateCertificate(name, rsaKeyPair1, name, rsaKeyPair1, secureRandom);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Bytes getCertBytes(X509Certificate certificate) {
        try {
            return Bytes.wrap(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private class CustomBlockhashOperation extends AbstractOperation {
        private static final OperationResult ONLY_RESULT = new Operation.OperationResult(0L, null);
        private static final Bytes32 FAKE_BLOCK_HASH = Bytes32.fromHexString("0x1234567890");

        protected CustomBlockhashOperation() {
            super(64, "BLOCKHASH", 1, 1, gasCalculator);
        }

        @Override
        public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
            // This stack item has the requested block number, ignore it
            frame.popStackItem();
            frame.pushStackItem(FAKE_BLOCK_HASH);
            return ONLY_RESULT;
        }
    }
}
