// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.state.merkle.MerkleSchemaRegistry;
import com.hedera.node.app.state.merkle.SchemaApplications;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskReadableKVState;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.disk.OnDiskWritableKVState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
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
    private static final String SERVICE_NAME = "CryptoService";
    private static final String ACCOUNT_STATE_KEY = "Account";

    private Schema schema;
    private StateDefinition<AccountID, Account> def;
    private VirtualMap<OnDiskKey<AccountID>, OnDiskValue<Account>> virtualMap;

    @BeforeEach
    void setUp() throws IOException {
        setupConstructableRegistry();
        final Path storageDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        def = StateDefinition.onDisk(ACCOUNT_STATE_KEY, AccountID.PROTOBUF, Account.PROTOBUF, 100);

        //noinspection rawtypes
        schema = new Schema(version(1, 0, 0)) {
            @NonNull
            @Override
            public Set<StateDefinition> statesToCreate() {
                return Set.of(def);
            }
        };

        final KeySerializer<OnDiskKey<AccountID>> keySerializer = new OnDiskKeySerializer<>(
                onDiskKeySerializerClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                onDiskKeyClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                AccountID.PROTOBUF);
        final ValueSerializer<OnDiskValue<Account>> valueSerializer = new OnDiskValueSerializer<>(
                onDiskValueSerializerClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                onDiskValueClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                Account.PROTOBUF);
        final var tableConfig = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                100,
                // Force all hashes to disk, to make sure we're going through all the
                // serialization paths we can
                0);

        final var builder = new MerkleDbDataSourceBuilder(storageDir, tableConfig, CONFIGURATION);
        virtualMap = new VirtualMap<>(
                StateMetadata.computeLabel(SERVICE_NAME, ACCOUNT_STATE_KEY),
                keySerializer,
                valueSerializer,
                builder,
                CONFIGURATION);

        Configuration config = mock(Configuration.class);
        final var hederaConfig = mock(HederaConfig.class);
        lenient().when(config.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
    }

    <K, V> VirtualMap<OnDiskKey<K>, OnDiskValue<V>> copyHashAndFlush(VirtualMap<OnDiskKey<K>, OnDiskValue<V>> map) {
        // Make the fast copy
        final var copy = map.copy();

        // Hash the now immutable map
        CRYPTO.digestTreeSync(map);

        // Flush to disk
        final VirtualRootNode<?, ?> root = map.getChild(1);
        root.enableFlush();
        map.release();
        try {
            root.waitUntilFlushed();
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
                ACCOUNT_STATE_KEY,
                onDiskKeyClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                AccountID.PROTOBUF,
                onDiskValueClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                Account.PROTOBUF,
                virtualMap);
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
        final var r = new MerkleSchemaRegistry(registry, SERVICE_NAME, CONFIGURATION, new SchemaApplications());
        r.register(schema);

        virtualMap.release();

        // read it back now as our map and validate the data come back fine
        virtualMap = parseTree(serializedBytes, snapshotDir);
        final var rs = new OnDiskReadableKVState<>(
                ACCOUNT_STATE_KEY, onDiskKeyClassId(SERVICE_NAME, ACCOUNT_STATE_KEY), AccountID.PROTOBUF, virtualMap);
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
                ACCOUNT_STATE_KEY,
                onDiskKeyClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                AccountID.PROTOBUF,
                onDiskValueClassId(SERVICE_NAME, ACCOUNT_STATE_KEY),
                Account.PROTOBUF,
                virtualMap);
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

        final var rs = new OnDiskReadableKVState<>(
                ACCOUNT_STATE_KEY, onDiskKeyClassId(SERVICE_NAME, ACCOUNT_STATE_KEY), AccountID.PROTOBUF, virtualMap);
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
    void toStringWorks() {
        final var key = new OnDiskKey<>(onDiskKeyClassId(SERVICE_NAME, ACCOUNT_STATE_KEY), AccountID.PROTOBUF);
        final var string = key.toString();
        assertThat(string).isEqualTo("OnDiskKey{key=null}");
    }

    @AfterEach
    void tearDown() {
        virtualMap.release();
    }
}
