// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.fees.SimpleFeesMirrorNodeAnotherTest.makeMirrorNodeCalculator;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.asBytes;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.spi.AppContext.Gossip.UNAVAILABLE_GOSSIP;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.UNIVERSAL_NOOP_FEE_CHARGING;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.hedera.hapi.node.base.Transaction;
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
import com.hedera.hapi.node.transaction.TransactionBody;
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
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
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
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.stream.proto.RecordStreamFile;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SimpleFeesMirrorNodeTest {

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

    @Test
    void basicStreaming() throws IOException {
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
                    //                    .flatMap(recordWithSidecars ->
                    // recordWithSidecars.recordFile().getRecordStreamItemsList().stream())
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
            final var signedTxn =
                    SignedTransaction.parseFrom(item.getTransaction().getSignedTransactionBytes());
            System.out.println("the real transaction is" + signedTxn);
            com.hederahashgraph.api.proto.java.TransactionBody transactionBody =
                    com.hederahashgraph.api.proto.java.TransactionBody.parseFrom(signedTxn.getBodyBytes());
            System.out.println("TXN:transaction body is " + transactionBody);
            System.out.println("TXN: memo " + transactionBody.getMemo());
            System.out.println("TXN: fee " + transactionBody.getTransactionFee());
            System.out.println("TXN: id " + transactionBody.getTransactionID());
            System.out.println("TXN: data case " + transactionBody.getDataCase());

        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void streamingSimpleFees() throws IOException {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");

        final State state = FakeGenesisState.make(overrides);

        // config props
        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();
        final SimpleFeesMirrorNodeAnotherTest.FeeCalculator calc = makeMirrorNodeCalculator(state, properties);


        final String records_dir = "../../temp/";

        try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
            paths.filter(Files::isRegularFile).forEach(file -> {
//                System.out.println("reading file " + file);
                if (!file.toString().endsWith("rcd")) {
                    System.out.println("skipping");
                    return;
                }
                try (final var fin = new FileInputStream(file.toFile())) {
                    final var recordFileVersion =
                            ByteBuffer.wrap(fin.readNBytes(4)).getInt();
//                    System.out.println("read version " + recordFileVersion);
                    final var recordStreamFile = RecordStreamFile.parseFrom(fin);
                    recordStreamFile.getRecordStreamItemsList().stream().forEach(item -> {
                        try {
//                            final var txn = item.getTransaction();
                            final var signedTxnBytes = item.getTransaction().getSignedTransactionBytes();
                            final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
//                            final Transaction txn = Transaction.newBuilder().signedTransactionBytes(signedTxn.getBodyBytes())
                            final var body = TransactionBody.PROTOBUF.parse(
                                    Bytes.wrap(signedTxn.getBodyBytes().toByteArray()));
                            final Transaction txn = Transaction.newBuilder().body(body).build();
                            if (shouldSkip(body.data().kind())) {
                                return;
                            }
//                            System.out.println("TXN: memo " + body.memo());
//                            System.out.println("TXN: fee " + body.transactionFee());
//                            System.out.println("TXN: id " + body.transactionID());
//                            System.out.println("calculating simple fees for transaction " + body);
                            final var result = calc.calculate(txn, ServiceFeeCalculator.EstimationMode.Intrinsic);
//                            System.out.println("result is " + result);
                            // max fee in tiny bar //
//                            System.out.println("original      is : " + body.transactionFee());
                            final var record = item.getRecord();
                            final var txnFee = record.getTransactionFee();
                            // actual fee charged (in tiny bar)?
                            var fract = ((double)result.total())/(double)(txnFee*12);
                            if(Math.abs(1 - fract) > 0.05) {
                                System.out.println("TXN: data case " + body.data().kind());
                                System.out.println("simple        is : " + result.total());
                                System.out.println("record trans fee : " + (txnFee*12));
                                System.out.println("fract = " + fract);
                            }
                            // rec fee * 12 to get cents
                            // 845911
//                            System.out.println(
//                                    "status is " + record.getReceipt().getStatus());
//                            System.out.println(
//                                    "exchange rate is " + record.getReceipt().getExchangeRate());

                        } catch (Exception e) {
                            System.out.println("exception " + e);
                            e.printStackTrace();
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

    private boolean shouldSkip(TransactionBody.DataOneOfType kind) {
        // requires readable store
        if (kind == TransactionBody.DataOneOfType.CONSENSUS_SUBMIT_MESSAGE) {
            return true;
        }
//        if (kind == TransactionBody.DataOneOfType.CRYPTO_TRANSFER) {
//            return true;
//        }

        // fee calculator not implemented yet
        // coming in PR: https://github.com/hiero-ledger/hiero-consensus-node/pull/22584
        if (kind == TransactionBody.DataOneOfType.TOKEN_AIRDROP) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_ASSOCIATE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_DISSOCIATE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_UPDATE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_UPDATE_NFTS) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_WIPE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_REJECT) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_GRANT_KYC) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_FEE_SCHEDULE_UPDATE) {
            return true;
        }

        return false;
    }

    @Test
    void doIt() {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        final State state = FakeGenesisState.make(overrides);
        // config props
        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final SimpleFeesMirrorNodeAnotherTest.FeeCalculator calc = makeMirrorNodeCalculator(state, properties);

        System.out.println("got the calculator " + calc);
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final Transaction txn = Transaction.newBuilder().body(body).build();
        final var result = calc.calculate(txn, ServiceFeeCalculator.EstimationMode.Intrinsic);
        System.out.println("result is " + result);
        assertThat(result.service).isEqualTo(9999000000L);
    }

}
