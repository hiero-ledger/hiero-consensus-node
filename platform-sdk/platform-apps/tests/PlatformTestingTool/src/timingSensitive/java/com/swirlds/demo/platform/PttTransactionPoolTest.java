// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.FCQ;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.HANDLED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;
import static com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord.DEFAULT_EXPIRATION_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.demo.merkle.map.FCMConfig;
import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.merkle.map.FCMTransactionHandler;
import com.swirlds.demo.merkle.map.MapValueFCQ;
import com.swirlds.demo.merkle.map.internal.DummyExpectedFCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.freeze.FreezeConfig;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQ;
import com.swirlds.demo.platform.fs.stresstest.proto.DeleteFCQNode;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.system.Platform;
import java.io.IOException;
import java.time.Instant;
import java.util.Random;
import org.hiero.base.crypto.test.fixtures.ED25519SigningProvider;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class PttTransactionPoolTest {
    private static final Platform platform;
    private static final ExpectedFCMFamily expectedFCMFamily;
    private static final FCMFamily fCMFamily;
    private static final FCMTransactionHandler handler;
    private static final byte[] payload = "Test TransactionPool Signature".getBytes();
    private static final byte[] payloadWithSig = new byte
            [payload.length + ED25519SigningProvider.SIGNATURE_LENGTH + ED25519SigningProvider.PUBLIC_KEY_LENGTH];
    private static final Random random = new Random();
    private static final long myID = 0;
    private static final long otherID = 1;
    private static final int nodesNum = 4;
    private static PayloadConfig config = PayloadConfig.builder().build();
    private static PttTransactionPool pttTransactionPool;
    private static MapValueFCQ fcqValue;

    static {
        platform = Mockito.mock(Platform.class);
        Mockito.when(platform.getSelfId()).thenReturn(NodeId.of(myID));
        System.arraycopy(payload, 0, payloadWithSig, 0, payload.length);
        expectedFCMFamily = new DummyExpectedFCMFamily(myID);
        fCMFamily = new FCMFamily(true);
        handler = Mockito.mock(FCMTransactionHandler.class);
    }

    private MapValueFCQ fcq;

    @BeforeEach
    public void setUp() {
        MapKey key = new MapKey(otherID, otherID, 1);

        final byte[] content = new byte[48];
        random.nextBytes(content);

        final long index = random.nextLong();
        final long balance = random.nextLong();

        fcqValue = MapValueFCQ.newBuilder()
                .setIndex(index)
                .setBalance(balance)
                .setContent(content)
                .setMapKey(key)
                .setExpirationQueue(null)
                .setExpirationTime(DEFAULT_EXPIRATION_TIME)
                .setAccountsWithExpiringRecords(null)
                .build();
        for (int i = 0; i < 1000; i++) {
            fcq = TestUtil.addRecord(fcqValue, DEFAULT_EXPIRATION_TIME);
            fcqValue = fcq;
        }
        // when queue is created one record is inserted. so total 1001 records
        assertEquals(1001, fcqValue.getRecordsSize());

        fCMFamily.getAccountFCQMap().put(key, fcqValue);

        expectedFCMFamily
                .getExpectedMap()
                .put(
                        key,
                        new ExpectedValue(
                                FCQ,
                                new LifecycleStatus(
                                        TransactionState.SUBMITTED,
                                        Create,
                                        Instant.now().getEpochSecond(),
                                        otherID)));
    }

    @Test
    public void invalidSigTest() {
        // invalidSigRatio be 1 denotes all signatures for FCMTransaction should be invalid
        config = PayloadConfig.builder().setInvalidSigRatio(1).build();
        pttTransactionPool = new PttTransactionPool(
                platform,
                myID,
                config,
                "test",
                Mockito.mock(FCMConfig.class),
                Mockito.mock(VirtualMerkleConfig.class),
                Mockito.mock(FreezeConfig.class),
                Mockito.mock(TransactionPoolConfig.class),
                Mockito.mock(TransactionSubmitter.class),
                Mockito.mock(ExpectedFCMFamily.class));
        for (int i = 0; i < 100; i++) {
            assertTrue(pttTransactionPool.invalidSig());
        }
    }

    @Test
    public void validSigTest() {
        // invalidSigRatio be 0 denotes all signatures for FCMTransaction should be valid
        config = PayloadConfig.builder().setInvalidSigRatio(0).build();
        pttTransactionPool = new PttTransactionPool(
                platform,
                myID,
                config,
                "test",
                Mockito.mock(FCMConfig.class),
                Mockito.mock(VirtualMerkleConfig.class),
                Mockito.mock(FreezeConfig.class),
                Mockito.mock(TransactionPoolConfig.class),
                Mockito.mock(TransactionSubmitter.class),
                Mockito.mock(ExpectedFCMFamily.class));
        for (int i = 0; i < 100; i++) {
            assertFalse(pttTransactionPool.invalidSig());
        }
    }

    @Test
    public void fcqDeleteTest() {
        // when queue is created one record is inserted. so total 1001 records
        assertEquals(1001, fcqValue.getRecordsSize());

        boolean isError = false;
        MapKey key = new MapKey(otherID, otherID, 1);
        assertEquals(1, fCMFamily.getAccountFCQMap().size());
        assertEquals(1, expectedFCMFamily.getExpectedMap().size());

        DeleteFCQ deleteFCQ = DeleteFCQ.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .build();

        FCMTransaction trans = FCMTransaction.newBuilder()
                .setDeleteFCQ(deleteFCQ)
                .setOriginNode(otherID)
                .build();
        try {
            final PlatformTestingToolState state = new PlatformTestingToolState();
            state.setFcmFamily(fCMFamily);
            handler.performOperation(
                    trans,
                    state,
                    expectedFCMFamily,
                    otherID,
                    Instant.now().getEpochSecond(),
                    FCQ,
                    DEFAULT_EXPIRATION_TIME,
                    null,
                    null);
            Thread.sleep(1000);
        } catch (IOException | InterruptedException e) {
            isError = true;
        }

        assertFalse(isError);

        assertEquals(0, fCMFamily.getAccountFCQMap().size());
        assertEquals(
                Delete,
                expectedFCMFamily
                        .getExpectedMap()
                        .get(key)
                        .getLatestHandledStatus()
                        .getTransactionType());
        assertEquals(
                HANDLED,
                expectedFCMFamily
                        .getExpectedMap()
                        .get(key)
                        .getLatestHandledStatus()
                        .getTransactionState());
    }

    @Test
    public void fcqDeleteNodeTest() {
        // when queue is created one record is inserted. so total 1001 records
        assertEquals(1001, fcqValue.getRecordsSize());

        boolean isError = false;
        MapKey key = new MapKey(otherID, otherID, 1);
        assertEquals(1, fCMFamily.getAccountFCQMap().size());
        assertEquals(1, expectedFCMFamily.getExpectedMap().size());

        DeleteFCQNode deleteFCQNode = DeleteFCQNode.newBuilder()
                .setShardID(key.getShardId())
                .setRealmID(key.getRealmId())
                .setAccountID(key.getAccountId())
                .build();

        FCMTransaction trans = FCMTransaction.newBuilder()
                .setDeleteFCQNode(deleteFCQNode)
                .setOriginNode(otherID)
                .build();
        try {
            final PlatformTestingToolState state = new PlatformTestingToolState();
            state.setFcmFamily(fCMFamily);
            handler.performOperation(
                    trans,
                    state,
                    expectedFCMFamily,
                    otherID,
                    Instant.now().getEpochSecond(),
                    FCQ,
                    DEFAULT_EXPIRATION_TIME,
                    null,
                    null);
            Thread.sleep(1000);
        } catch (InterruptedException | IOException e) {
            isError = true;
        }

        assertFalse(isError);

        assertEquals(1, fCMFamily.getAccountFCQMap().size());
        assertEquals(1000, fCMFamily.getAccountFCQMap().get(key).getRecordsSize());
        assertEquals(
                Update,
                expectedFCMFamily
                        .getExpectedMap()
                        .get(key)
                        .getLatestHandledStatus()
                        .getTransactionType());
        assertEquals(
                HANDLED,
                expectedFCMFamily
                        .getExpectedMap()
                        .get(key)
                        .getLatestHandledStatus()
                        .getTransactionState());
    }
}
