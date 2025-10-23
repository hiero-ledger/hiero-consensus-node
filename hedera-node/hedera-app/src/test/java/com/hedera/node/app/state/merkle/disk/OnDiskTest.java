// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle.disk;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_ID;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_LABEL;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntry;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.state.merkle.MerkleSchemaRegistry;
import com.hedera.node.app.state.merkle.SchemaApplications;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskReadableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableQueueState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A variety of robust tests for the on-disk merkle data structure, especially including
 * serialization to/from disk (under normal operation) and to/from saved state. These tests use a
 * more complex map, with full objects to store and retrieve objects from the virtual map, and when
 * serializing for hashing, and for serializing when saving state.
 */
class OnDiskTest extends MerkleTestBase {

    private Schema schema;
    private StateDefinition<AccountID, Account> def;
    private VirtualMap virtualMap;

    @BeforeEach
    void setUp() throws IOException {
        setupConstructableRegistry();

        def = StateDefinition.onDisk(ACCOUNTS_STATE_ID, ACCOUNTS_KEY, AccountID.PROTOBUF, Account.PROTOBUF, 100);

        //noinspection rawtypes
        schema = new Schema<>(version(1, 0, 0), SEMANTIC_VERSION_COMPARATOR) {
            @NonNull
            @Override
            public Set<StateDefinition> statesToCreate() {
                return Set.of(def);
            }
        };

        final var builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 100, 0);
        virtualMap =
                new VirtualMap(StateMetadata.computeLabel(TokenService.NAME, ACCOUNTS_KEY), builder, CONFIGURATION);

        Configuration config = mock(Configuration.class);
        final var hederaConfig = mock(HederaConfig.class);
        lenient().when(config.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
    }

    VirtualMap copyHashAndFlush(VirtualMap map) {
        // Make the fast copy
        final var copy = map.copy();

        // Hash the now immutable map
        map.getHash();

        // Flush to disk
        map.enableFlush();
        map.release();
        try {
            map.waitUntilFlushed();
        } catch (InterruptedException e) {
            System.err.println("Unable to complete the test, the root node never flushed!");
            throw new RuntimeException(e);
        }

        // And we're done
        return copy;
    }

    @Test
    void populateTheMapAndFlushToDiskAndReadBack() throws IOException {
        // Populate the data set and flush it all to disk
        final var ws = new OnDiskWritableKVState<>(
                ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, AccountID.PROTOBUF, Account.PROTOBUF, virtualMap);
        for (int i = 0; i < 10; i++) {
            final var id = AccountID.newBuilder().accountNum(i).build();
            final var acct = Account.newBuilder()
                    .accountId(id)
                    .memo("Account " + i)
                    .tinybarBalance(i)
                    .build();

            ws.put(id, acct);
        }
        ws.commit();
        virtualMap = copyHashAndFlush(virtualMap);

        // We will now make another fast copy of our working copy of the tree.
        // Then we will hash the immutable copy and write it out. Then we will
        // release the immutable copy.
        VirtualMap copy = virtualMap.copy(); // throw away the copy, we won't use it
        copy.release();
        virtualMap.getHash();

        final var snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
        final byte[] serializedBytes = writeTree(virtualMap, snapshotDir);

        // Before we can read the data back, we need to register the data types
        // I plan to deserialize.
        final var r = new MerkleSchemaRegistry(TokenService.NAME, new SchemaApplications());
        r.register(schema);

        virtualMap.release();

        // read it back now as our map and validate the data come back fine
        virtualMap = parseTree(serializedBytes, snapshotDir);
        final var rs = new OnDiskReadableKVState<AccountID, Account>(
                ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, AccountID.PROTOBUF, Account.PROTOBUF, virtualMap);
        for (int i = 0; i < 10; i++) {
            final var id = AccountID.newBuilder().accountNum(i).build();
            final var acct = rs.get(id);
            assertThat(acct).isNotNull();
            assertThat(acct.accountId()).isEqualTo(id);
            assertThat(acct.memo()).isEqualTo("Account " + i);
            assertThat(acct.tinybarBalance()).isEqualTo(i);
        }
    }

    @Test
    void populateFlushToDisk() {
        final var ws = new OnDiskWritableKVState<>(
                ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, AccountID.PROTOBUF, Account.PROTOBUF, virtualMap);
        for (int i = 1; i < 10; i++) {
            final var id = AccountID.newBuilder().accountNum(i).build();
            final var acct = Account.newBuilder()
                    .accountId(id)
                    .memo("Account " + i)
                    .tinybarBalance(i)
                    .build();
            ws.put(id, acct);
        }
        ws.commit();
        virtualMap = copyHashAndFlush(virtualMap);

        final var rs = new OnDiskReadableKVState<AccountID, Account>(
                ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, AccountID.PROTOBUF, Account.PROTOBUF, virtualMap);
        for (int i = 1; i < 10; i++) {
            final var id = AccountID.newBuilder().accountNum(i).build();
            final var acct = rs.get(id);
            assertThat(acct).isNotNull();
            assertThat(acct.accountId()).isEqualTo(id);
            assertThat(acct.memo()).isEqualTo("Account " + i);
            assertThat(acct.tinybarBalance()).isEqualTo(i);
        }
    }

    @Test
    void populateAndReadBackSingleton() throws ParseException {
        // set up writable states
        final var singletonWs = new OnDiskWritableSingletonState<>(
                PLATFORM_STATE_STATE_ID, PLATFORM_STATE_STATE_LABEL, PlatformState.PROTOBUF, virtualMap);

        // populate and commit
        final var singletonOriginalValue = PlatformState.newBuilder()
                .creationSoftwareVersion(SemanticVersion.newBuilder()
                        .major(1)
                        .minor(2)
                        .patch(3)
                        .pre("test")
                        .build())
                .roundsNonAncient(4)
                .consensusSnapshot(ConsensusSnapshot.newBuilder()
                        .round(5)
                        .minimumJudgeInfoList(List.of(MinimumJudgeInfo.newBuilder()
                                .round(6)
                                .minimumJudgeBirthRound(7)
                                .build()))
                        .nextConsensusNumber(8)
                        .consensusTimestamp(
                                Timestamp.newBuilder().seconds(9).nanos(10).build())
                        .judgeIds(List.of(JudgeId.newBuilder()
                                .creatorId(11)
                                .judgeHash(Bytes.fromHex("12"))
                                .build()))
                        .build())
                .freezeTime(Timestamp.newBuilder().seconds(13).nanos(14).build())
                .lastFrozenTime(Timestamp.newBuilder().seconds(15).nanos(16).build())
                .latestFreezeRound(17)
                .legacyRunningEventHash(Bytes.fromHex("18"))
                .build();

        singletonWs.put(singletonOriginalValue);
        singletonWs.commit();

        // verify via raw key/value bytes
        final var singletonBytesKey = StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(
                StateKey.KeyOneOfType.SINGLETON, SingletonType.fromProtobufOrdinal(PLATFORM_STATE_STATE_ID))));
        final var singletonValueBytes = virtualMap.getBytes(singletonBytesKey);
        Objects.requireNonNull(singletonValueBytes);
        final PlatformState singletonExtractedValue =
                StateValue.PROTOBUF.parse(singletonValueBytes).value().as();
        assertEquals(singletonOriginalValue, singletonExtractedValue);
    }

    @Test
    void populateAndReadBackQueue() throws ParseException {
        // set up writable states
        final var queueWs = new OnDiskWritableQueueState<>(
                TRANSACTION_RECEIPTS_STATE_ID,
                TRANSACTION_RECEIPTS_STATE_LABEL,
                TransactionReceiptEntries.PROTOBUF,
                virtualMap);

        // populate and commit
        final var queueOriginalValue = TransactionReceiptEntries.newBuilder()
                .entries(TransactionReceiptEntry.newBuilder()
                        .nodeId(1)
                        .transactionId(TransactionID.newBuilder()
                                .transactionValidStart(Timestamp.newBuilder()
                                        .seconds(2)
                                        .nanos(3)
                                        .build())
                                .accountID(AccountID.newBuilder()
                                        .shardNum(4)
                                        .realmNum(5)
                                        .accountNum(6)
                                        .build())
                                .scheduled(true)
                                .nonce(7)
                                .build())
                        .build())
                .build();
        queueWs.add(queueOriginalValue);
        queueWs.commit();

        // verify via raw key/value bytes
        final var queueBytesKey = StateKey.PROTOBUF.toBytes(new StateKey(
                new OneOf<>(StateKey.KeyOneOfType.fromProtobufOrdinal(TRANSACTION_RECEIPTS_STATE_ID), 1L)));
        final var queueValueBytes = virtualMap.getBytes(queueBytesKey);
        Objects.requireNonNull(queueValueBytes);
        final TransactionReceiptEntries queueExtractedValue =
                StateValue.PROTOBUF.parse(queueValueBytes).value().as();
        assertEquals(queueOriginalValue, queueExtractedValue);
    }

    @Test
    void populateAndReadBackKv() throws ParseException {
        // set up writable states
        final var kvWs = new OnDiskWritableKVState<>(
                ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, AccountID.PROTOBUF, Account.PROTOBUF, virtualMap);

        // populate and commit
        final var kvOriginalKey =
                AccountID.newBuilder().shardNum(1).realmNum(1).accountNum(2).build();
        final var kvOriginalValue = Account.newBuilder()
                .accountId(kvOriginalKey)
                .expirationSecond(3)
                .tinybarBalance(4)
                .memo("Account 123")
                .deleted(true)
                .stakedToMe(5)
                .stakePeriodStart(6)
                .stakedAccountId(AccountID.newBuilder()
                        .shardNum(7)
                        .realmNum(8)
                        .accountNum(9)
                        .build())
                .declineReward(true)
                .receiverSigRequired(true)
                .headNftId(NftID.newBuilder()
                        .tokenId(TokenID.newBuilder()
                                .shardNum(10)
                                .realmNum(11)
                                .tokenNum(12)
                                .build())
                        .build())
                .headNftSerialNumber(13)
                .numberOwnedNfts(14)
                .maxAutoAssociations(15)
                .usedAutoAssociations(16)
                .numberAssociations(17)
                .smartContract(true)
                .numberPositiveBalances(18)
                .ethereumNonce(19)
                .stakeAtStartOfLastRewardedPeriod(20)
                .autoRenewAccountId(AccountID.newBuilder()
                        .shardNum(21)
                        .realmNum(22)
                        .accountNum(23)
                        .build())
                .autoRenewSeconds(24)
                .contractKvPairsNumber(25)
                .cryptoAllowances(List.of(AccountCryptoAllowance.newBuilder()
                        .spenderId(AccountID.newBuilder()
                                .shardNum(29)
                                .realmNum(30)
                                .accountNum(31)
                                .build())
                        .amount(32)
                        .build()))
                .approveForAllNftAllowances(AccountApprovalForAllAllowance.newBuilder()
                        .tokenId(TokenID.newBuilder()
                                .shardNum(33)
                                .realmNum(34)
                                .tokenNum(35)
                                .build())
                        .spenderId(AccountID.newBuilder()
                                .shardNum(36)
                                .realmNum(37)
                                .accountNum(38)
                                .build())
                        .build())
                .tokenAllowances(AccountFungibleTokenAllowance.newBuilder()
                        .tokenId(TokenID.newBuilder()
                                .shardNum(39)
                                .realmNum(40)
                                .tokenNum(41)
                                .build())
                        .spenderId(AccountID.newBuilder()
                                .shardNum(43)
                                .realmNum(44)
                                .accountNum(45)
                                .build())
                        .amount(42)
                        .build())
                .numberTreasuryTitles(43)
                .expiredAndPendingRemoval(true)
                .firstContractStorageKey(Bytes.fromHex("44"))
                .headPendingAirdropId(PendingAirdropId.newBuilder()
                        .senderId(AccountID.newBuilder()
                                .shardNum(46)
                                .realmNum(47)
                                .accountNum(48)
                                .build())
                        .receiverId(AccountID.newBuilder()
                                .shardNum(49)
                                .realmNum(50)
                                .accountNum(51)
                                .build())
                        .fungibleTokenType(TokenID.newBuilder()
                                .shardNum(52)
                                .realmNum(53)
                                .tokenNum(54)
                                .build())
                        .build())
                .numberPendingAirdrops(55)
                .numberHooksInUse(56)
                .firstHookId(57)
                .numberLambdaStorageSlots(59)
                .build();
        kvWs.put(kvOriginalKey, kvOriginalValue);
        kvWs.commit();

        // verify via raw key/value bytes
        final var kvBytesKey = StateKey.PROTOBUF.toBytes(
                new StateKey(new OneOf<>(StateKey.KeyOneOfType.fromProtobufOrdinal(ACCOUNTS_STATE_ID), kvOriginalKey)));
        final var kvValueBytes = virtualMap.getBytes(kvBytesKey);
        Objects.requireNonNull(kvValueBytes);
        final Account kvExtractedValue =
                StateValue.PROTOBUF.parse(kvValueBytes).value().as();
        assertEquals(kvOriginalValue, kvExtractedValue);
    }

    @AfterEach
    void tearDown() {
        virtualMap.release();
    }
}
