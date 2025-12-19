package com.hedera.node.app.fees;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.RealmID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.ShardID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.entityid.impl.EntityIdServiceImpl;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
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
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.AppThrottleFactory;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes32;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.AbstractOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.Test;

import com.hedera.hapi.node.transaction.TransactionBody;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.spi.AppContext.Gossip.UNAVAILABLE_GOSSIP;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.UNIVERSAL_NOOP_FEE_CHARGING;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*

Here's how calculating fees works.  Every service has a fee calculator class which implements the ServiceFeeCalculator interface.

There is a SimpleFeeCalculator interface with one implementation.  It needs a loaded fee schedule and a list of calculators
for each service you want to calculate with. From this interface you can call calculateTxFee(body,feeContext).
This will return a FeeResult object in USD, which you can convert to hbar using the FeeUtils.feeResultToFees() method
and the current exchange rate.



Questions:

Where is the best place to put this?

How do we properly import the service impls? ConsensusServiceImpl seems to be exported already but
others like CryptoServiceImpl are not. And some impls need a bunch of parameters to init. Instead
let's have static lists of fee calculators on each service impl.

How do I call this main method from within gradle?  For now I'll make it be a unit test.


The FeeContext is going to be a challenge. Some calculators need the fee context, not just for the
number of sigs but also for the readable store so they can get, for example, the topic a message
is being submitted to so it can determine if there are custom fees applied.


 */

@ExtendWith(MockitoExtension.class)
public class SimpleFeesMirrorNodeTest {

//    @Test
//    public void doTest() throws IOException, ParseException {
//        System.out.println("calculating a fee for a create topic transaction");
//
//        // load up the fee schedule from JSON file
//        var input = SimpleFeesMirrorNodeTest.class.getClassLoader().getResourceAsStream("test-schedule.json");
//        var bytes = input.readAllBytes();
//        final FeeSchedule feeSchedule = FeeSchedule.JSON.parse(Bytes.wrap(bytes));
//
//        // check the schedule is valid
//        if (!isValid(feeSchedule)) {
//            throw new Error("invalid fee schedule");
//        }
//
//
//        // create a simple fee calculator
////        SimpleFeeCalculator calc = new SimpleFeesCalculatorProvider().make(feeSchedule);
//
//        // build an example transaction
////        final var op = ConsensusCreateTopicTransactionBody.newBuilder().build();
////        final var txnBody = TransactionBody.newBuilder().consensusCreateTopic(op).build();
////        final var txn = Transaction.newBuilder().body(txnBody).build();
////        final var numSigs =  txn.sigMap().sigPair().size();
//        final long topicEntityNum = 1L;
//        final TopicID topicId =
//                TopicID.newBuilder().topicNum(topicEntityNum).build();
//
//        final var op = ConsensusSubmitMessageTransactionBody.newBuilder()
//                .topicID(topicId)
//                .message(Bytes.wrap("foo"))
//                .build();
//        final var txnBody =
//                TransactionBody.newBuilder().consensusSubmitMessage(op).build();
//
//
//        System.out.println("making the executor");
//        final var test = new TransactionExecutorsTest();
//        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101");
//        // Construct a full implementation of the consensus node State API with all genesis accounts and files
//        final var state = test.genesisState(overrides);
////            final MerkleNodeState state = test.genesisState(overrides);
//        System.out.println("made the genesis state");
//
//        // Get a standalone executor based on this state, with an override to allow slightly longer memos
//        final var executor = TRANSACTION_EXECUTORS.newExecutor(
//                TransactionExecutors.Properties.newBuilder()
//                        .state(state)
//                        .appProperties(overrides)
//                        .build(),
//                new AppEntityIdFactory(DEFAULT_CONFIG));
//        System.out.println("out to execute " + txnBody);
//        final var output = executor.execute(txnBody, Instant.EPOCH);
//        System.out.println("got the output " + output);
//    }

    private static final long GAS = 400_000L;
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
            "{\"pc\":0,\"op\":96,\"gas\":\"0x5c838\",\"gasCost\":\"0x3\",\"memSize\":0,\"depth\":1,\"refund\":0,\"opName\":\"PUSH1\"}";
    private static final NodeInfo DEFAULT_NODE_INFO =
            new NodeInfoImpl(0, idFactory.newAccountId(3L), 10, List.of(), Bytes.EMPTY, List.of(), true, null);

    public static final Metrics NO_OP_METRICS = new NoOpMetrics();

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

    @Test void basicStreaming() throws IOException {
        final String record_path = "../../temp/2025-09-10T03_02_14.342128000Z.rcd";
//        final String sidecar_path = "sidecar";
//        byte[] bytes = Files.readAllBytes(Path.of(record_path));

        try (final var fin = new FileInputStream(record_path)) {
            final var recordFileVersion = ByteBuffer.wrap(fin.readNBytes(4)).getInt();
            final var recordStreamFile = com.hedera.services.stream.proto.RecordStreamFile.parseFrom(fin);
            System.out.println("File version is " + recordFileVersion);
            System.out.println("stream file is " + recordStreamFile);
//            recordStreamFile.getRecordStreamItemsList().stream().flatMap()
            recordStreamFile.getRecordStreamItemsList().stream()
//                    .flatMap(recordStreamItem -> recordStreamItem.getRecord())
                    // pull out the record stream items
//                    .flatMap(recordWithSidecars -> recordWithSidecars.recordFile().getRecordStreamItemsList().stream())
                    .forEach(item -> {
                        System.out.println("record " + item);
                        process_record(item);
                    });
        }
    }

    private void process_record(RecordStreamItem item) {
        System.out.println("record " + item);
        System.out.println("OUT: memo = " + item.getRecord().getMemo());
        System.out.println("OUT: transaction fee " + item.getRecord().getTransactionFee());
        System.out.println("OUT: transaction " + item.getTransaction().getSignedTransactionBytes());
        final var receipt = item.getRecord().getReceipt();
        System.out.println("OUT: status " + receipt.getStatus());
        System.out.println("OUT: exchange rate " + receipt.getExchangeRate());
        try {
            final var signedTxn = SignedTransaction.parseFrom(item.getTransaction().getSignedTransactionBytes());
            System.out.println("the real transaction is" + signedTxn);
            com.hederahashgraph.api.proto.java.TransactionBody transactionBody = com.hederahashgraph.api.proto.java.TransactionBody.parseFrom(signedTxn.getBodyBytes());
            System.out.println("TXN:transaction body is " + transactionBody);
            System.out.println("TXN: memo " + transactionBody.getMemo());
            System.out.println("TXN: fee " + transactionBody.getTransactionFee());
            System.out.println("TXN: id " + transactionBody.getTransactionID());
            System.out.println("TXN: data case " + transactionBody.getDataCase());

        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    @Test void streamingSimpleFees() throws IOException {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101","fees.simpleFeesEnabled","true");
        // bring up the full state
        final var state = genesisState(overrides);
        // config props
        final var properties =  TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();
        // make an entity id factory
        final var entityIdFactory = new AppEntityIdFactory(DEFAULT_CONFIG);
        // load a new executor component
        final var executorComponent = TRANSACTION_EXECUTORS.newExecutorJoshBetter(properties, entityIdFactory);
        // grab the fee calculator
        final var calc = executorComponent.feeManager().getSimpleFeeCalculator();
        System.out.println("got the calculator " +calc);

        final String records_dir = "../../temp/";


        try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
            paths
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        System.out.println("reading file " + file);
                        if(!file.toString().endsWith("rcd")) {
                            System.out.println("skipping");
                            return;
                        }
                        try (final var fin = new FileInputStream(file.toFile())) {
                            final var recordFileVersion = ByteBuffer.wrap(fin.readNBytes(4)).getInt();
                            System.out.println("read version " + recordFileVersion);
                            final var recordStreamFile = com.hedera.services.stream.proto.RecordStreamFile.parseFrom(fin);
                            recordStreamFile.getRecordStreamItemsList().stream()
                                    .forEach(item -> {
                                        try {
                                            TransactionBody body = parseTransactionBody(item);
                                            System.out.println("TXN: memo " + body.memo());
                                            System.out.println("TXN: fee " + body.transactionFee());
                                            System.out.println("TXN: id " + body.transactionID());
                                            System.out.println("TXN: data case " + body.data().kind());
                                            System.out.println("calculating simple fees for transaction " + body);
                                            final FeeContext feeContext = null;
                                            final var result = calc.calculateTxFee(body,feeContext);
                                            System.out.println("result is " + result);
                                            System.out.println("original is : " + body.transactionFee());
                                            System.out.println("simple   is : " + result.total());
                                        } catch (Exception e) {
                                            System.out.println("exception " + e);
                                        }
                                    });
                        } catch (FileNotFoundException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private TransactionBody parseTransactionBody(RecordStreamItem item) throws InvalidProtocolBufferException, ParseException {
        final var signedTxn = SignedTransaction.parseFrom(item.getTransaction().getSignedTransactionBytes());
        final var body =  TransactionBody.PROTOBUF.parse(Bytes.wrap(signedTxn.getBodyBytes().toByteArray()));
        return body;
    }

    @Test
    void doIt() {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101","fees.simpleFeesEnabled","true");
        // bring up the full state
        final var state = genesisState(overrides);
        // config props
        final var properties =  TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();
        // make an entity id factory
        final var entityIdFactory = new AppEntityIdFactory(DEFAULT_CONFIG);
        // load a new executor component
        final var executorComponent = TRANSACTION_EXECUTORS.newExecutorJoshBetter(properties, entityIdFactory);
        // grab the fee calculator
        final var calc = executorComponent.feeManager().getSimpleFeeCalculator();
        System.out.println("got the calculator " +calc);
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final FeeContext feeContext = null;
        final var result = calc.calculateTxFee(body,feeContext);
        System.out.println("result is " + result);
        assertThat(result.service).isEqualTo(9999000000L);
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

    public MerkleNodeState genesisState(@NonNull final Map<String, String> overrides) {
        final var state = new FakeState();
        final var configBuilder = HederaTestConfigBuilder.create();
        overrides.forEach(configBuilder::withValue);
        final var config = configBuilder.getOrCreateConfig();
        final var servicesRegistry = new FakeServicesRegistry();
        final var appContext = new AppContextImpl(
                InstantSource.system(),
                signatureVerifier,
                UNAVAILABLE_GOSSIP,
                () -> config,
                () -> DEFAULT_NODE_INFO,
                () -> NO_OP_METRICS,
                new AppThrottleFactory(
                        () -> config, () -> state, () -> ThrottleDefinitions.DEFAULT, ThrottleAccumulator::new),
                () -> UNIVERSAL_NOOP_FEE_CHARGING,
                new AppEntityIdFactory(config));
        registerServices(appContext, servicesRegistry);
        final var migrator = new FakeServiceMigrator();
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        migrator.doMigrations(
                state,
                servicesRegistry,
                null,
                bootstrapConfig.getConfigData(VersionConfig.class).servicesVersion(),
                new ConfigProviderImpl().getConfiguration(),
                config,
                startupNetworks,
                storeMetricsService,
                configProvider);
        // Create a node
        final var nodeWritableStates = state.getWritableStates(AddressBookService.NAME);
        final var nodes = nodeWritableStates.<EntityNumber, Node>get(NODES_STATE_ID);
        nodes.put(
                new EntityNumber(0),
                Node.newBuilder()
                        .accountId(appContext.idFactory().newAccountId(3L))
                        .build());
        ((CommittableWritableStates) nodeWritableStates).commit();
        final var writableStates = state.getWritableStates(FileService.NAME);
        final var readableStates = state.getReadableStates(AddressBookService.NAME);
        final var entityIdStore = new WritableEntityIdStoreImpl(state.getWritableStates(EntityIdService.NAME));
        entityIdStore.adjustEntityCount(EntityType.NODE, 1);
        final var nodeStore = new ReadableNodeStoreImpl(readableStates, entityIdStore);
        final var files = writableStates.<FileID, File>get(V0490FileSchema.FILES_STATE_ID);
        System.out.println("setting up te genesis content providers " + genesisContentProviders(nodeStore, config));
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
                state.getWritableStates(TokenService.NAME).<AccountID, Account>get(V0490TokenSchema.ACCOUNTS_STATE_ID);
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
        for (final long num : List.of(800L, 801L)) {
            final var accountId = AccountID.newBuilder().accountNum(num).build();
            accounts.put(
                    accountId,
                    Account.newBuilder()
                            .accountId(accountId)
                            .key(systemKey)
                            .expirationSecond(Long.MAX_VALUE)
                            .tinybarBalance(0L)
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
                filesConfig.simpleFeesSchedules(), genesisSchema::genesisSimpleFeesSchedules,
                filesConfig.exchangeRates(), genesisSchema::genesisExchangeRates,
                filesConfig.networkProperties(), genesisSchema::genesisNetworkProperties,
                filesConfig.hapiPermissions(), genesisSchema::genesisHapiPermissions,
                filesConfig.throttleDefinitions(), genesisSchema::genesisThrottleDefinitions);
    }

    private void registerServices(
            @NonNull final AppContext appContext, @NonNull final ServicesRegistry servicesRegistry) {
        // Register all service schema RuntimeConstructable factories before platform init
        Set.of(
                        new EntityIdServiceImpl(),
                        new ConsensusServiceImpl(),
                        new ContractServiceImpl(appContext, NO_OP_METRICS),
                        new FileServiceImpl(),
                        new FreezeServiceImpl(),
                        new ScheduleServiceImpl(appContext),
                        new TokenServiceImpl(appContext),
                        new UtilServiceImpl(appContext, (signedTxn, config) -> null),
                        new RecordCacheService(),
                        new BlockRecordService(),
                        new BlockStreamService(),
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
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        true,
                        null);
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
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        false,
                        null));
            }

            @Override
            public NodeInfo nodeInfo(final long nodeId) {
                return new NodeInfoImpl(
                        0,
                        someAccount,
                        0,
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        Bytes.EMPTY,
                        List.of(ServiceEndpoint.DEFAULT, ServiceEndpoint.DEFAULT),
                        false,
                        null);
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
            try (final var in = com.hedera.node.app.workflows.standalone.TransactionExecutorsTest.class.getClassLoader().getResourceAsStream(loc)) {
                final var bytes = requireNonNull(in).readAllBytes();
                return Bytes.wrap(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static X509Certificate randomX509Certificate() {
        try {
            final SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");

            final KeyPairGenerator rsaKeyGen = KeyPairGenerator.getInstance("RSA");
            rsaKeyGen.initialize(3072, secureRandom);
            final KeyPair rsaKeyPair1 = rsaKeyGen.generateKeyPair();

            final String name = "CN=Bob";
            return CryptoStatic.generateCertificate(
                    name, rsaKeyPair1, name, rsaKeyPair1, secureRandom, SigningSchema.RSA.getSigningAlgorithm());
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

