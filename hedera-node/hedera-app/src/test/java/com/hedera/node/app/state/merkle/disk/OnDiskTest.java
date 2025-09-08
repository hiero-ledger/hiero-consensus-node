// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle.disk;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_ID;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_LABEL;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_LABEL;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntry;
import com.hedera.hapi.node.state.token.Account;
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
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskReadableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableQueueState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import org.hiero.base.crypto.DigestType;
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
        final Path storageDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        def = StateDefinition.onDisk(ACCOUNTS_STATE_ID, ACCOUNTS_KEY, AccountID.PROTOBUF, Account.PROTOBUF, 100);

        //noinspection rawtypes
        schema = new Schema(version(1, 0, 0)) {
            @NonNull
            @Override
            public Set<StateDefinition> statesToCreate() {
                return Set.of(def);
            }
        };

        final var tableConfig = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                100,
                // Force all hashes to disk, to make sure we're going through all the
                // serialization paths we can
                0);

        final var builder = new MerkleDbDataSourceBuilder(storageDir, tableConfig, CONFIGURATION);
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
        CRYPTO.digestTreeSync(map);

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
        CRYPTO.digestTreeSync(virtualMap);

        final var snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
        final byte[] serializedBytes = writeTree(virtualMap, snapshotDir);

        // Before we can read the data back, we need to register the data types
        // I plan to deserialize.
        final var r = new MerkleSchemaRegistry(registry, TokenService.NAME, CONFIGURATION, new SchemaApplications());
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
    void populateAndReadBack() throws ParseException {
        // set up writable states
        final var singletonWs = new OnDiskWritableSingletonState<>(
                PLATFORM_STATE_STATE_ID, PLATFORM_STATE_STATE_LABEL, PlatformState.PROTOBUF, virtualMap);
        final var queueWs = new OnDiskWritableQueueState<>(
                TRANSACTION_RECEIPTS_STATE_ID,
                TRANSACTION_RECEIPTS_STATE_LABEL,
                TransactionReceiptEntries.PROTOBUF,
                virtualMap);
        final var kvWs = new OnDiskWritableKVState<>(
                ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, AccountID.PROTOBUF, Account.PROTOBUF, virtualMap);

        // populate and commit

        // singleton
        final var singletonOriginalValue = UNINITIALIZED_PLATFORM_STATE;
        singletonWs.put(singletonOriginalValue);
        singletonWs.commit();

        // queue
        final var queueOriginalValue = TransactionReceiptEntries.newBuilder()
                .entries(TransactionReceiptEntry.newBuilder().nodeId(0).build())
                .build();
        queueWs.add(queueOriginalValue);
        queueWs.commit();

        // kv
        final var kvOriginalKey = AccountID.newBuilder().accountNum(0).build();
        final var kvOriginalValue = Account.newBuilder()
                .accountId(kvOriginalKey)
                .memo("Account 0")
                .tinybarBalance(0)
                .build();
        kvWs.put(kvOriginalKey, kvOriginalValue);
        kvWs.commit();

        // verify via raw key/value bytes

        // singleton
        final var singletonBytesKey = StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(
                StateKey.KeyOneOfType.SINGLETON, SingletonType.fromProtobufOrdinal(PLATFORM_STATE_STATE_ID))));
        final var singletonValueBytes = virtualMap.getBytes(singletonBytesKey);
        Objects.requireNonNull(singletonValueBytes);
        final PlatformState singletonExtractedValue =
                StateValue.PROTOBUF.parse(singletonValueBytes).value().as();
        assertEquals(singletonOriginalValue, singletonExtractedValue);

        // queue
        final var queueBytesKey = StateKey.PROTOBUF.toBytes(new StateKey(
                new OneOf<>(StateKey.KeyOneOfType.fromProtobufOrdinal(TRANSACTION_RECEIPTS_STATE_ID), 1L)));
        final var queueValueBytes = virtualMap.getBytes(queueBytesKey);
        Objects.requireNonNull(queueValueBytes);
        final TransactionReceiptEntries queueExtractedValue =
                StateValue.PROTOBUF.parse(queueValueBytes).value().as();
        assertEquals(queueOriginalValue, queueExtractedValue);

        // kv
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
