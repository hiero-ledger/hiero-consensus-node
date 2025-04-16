// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map.internal;

import static com.swirlds.demo.platform.TestUtil.signTransaction;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.Crypto;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.FCQ;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.HANDLED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.HANDLE_FAILED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.HANDLE_REJECTED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState.INITIALIZED;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Create;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Delete;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Expire;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Transfer;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType.Update;
import static com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord.DEFAULT_EXPIRATION_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.demo.merkle.map.FCMConfig;
import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.merkle.map.MapValueData;
import com.swirlds.demo.merkle.map.MapValueFCQ;
import com.swirlds.demo.platform.PayloadCfgSimple;
import com.swirlds.demo.platform.PayloadConfig;
import com.swirlds.demo.platform.PttTransactionPool;
import com.swirlds.demo.platform.TransactionPoolConfig;
import com.swirlds.demo.platform.TransactionSubmitter;
import com.swirlds.demo.platform.freeze.FreezeConfig;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateAccount;
import com.swirlds.demo.platform.fs.stresstest.proto.FCMTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.UpdateAccount;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionType;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import com.swirlds.platform.system.Platform;
import java.io.IOException;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class ExpectedFCMFamilyTest {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static int weightedNodesNum = 4;
    // ExpectedFCMFamily with default PayloadCfgSimple setting,
    // in which performOnDeleted, createOnExistingEntities, and performOnNonExistingEntities are false

    // ExpectedFCMFamily of node0, entities (0, 0, accountId) would be in its selfEntityLists
    private static ExpectedFCMFamilyImpl expectedFCMFamily0 = new ExpectedFCMFamilyImpl();

    static {
        expectedFCMFamily0.setWeightedNodeNum(weightedNodesNum);
    }

    // ExpectedFCMFamily of node1, entities (1, 1, accountId) would be in its selfEntityLists
    private static ExpectedFCMFamilyImpl expectedFCMFamily1 = new ExpectedFCMFamilyImpl();

    static {
        expectedFCMFamily1.setWeightedNodeNum(weightedNodesNum);
    }

    private static final long NODE_ID_ZERO = 0;
    private static final long NODE_ID_ONE = 1;
    private static final long NODE_ID_TWO = 2;
    private static final long NODE_ID_THREE = 3;

    private static final long timestamp = Instant.now().toEpochMilli();
    private static MapKey deletedCrypto, toBeDeletedCrypto, expiredCrypto, existCrypto, existFCQ;
    private static PayloadConfig config =
            PayloadConfig.builder().setAppendSig(true).build();

    // PayloadCfgSimple with default setting
    // performOnDeleted, createOnExistingEntities, and performOnNonExistingEntities are false
    private static final PayloadCfgSimple defaultPayloadCfg = new PayloadCfgSimple();

    // performOnDeleted, createOnExistingEntities, and performOnNonExistingEntities are true
    private static final PayloadCfgSimple allowErrorsCfg = new PayloadCfgSimple();

    private static MapKey createdFCQ;
    private static MapKey createdCrypto;
    private static MapKey handleRejectedCrypto;
    private static MapKey errorCrypto;
    private static MapKey createdNotHandledFCQ;
    private static PttTransactionPool pttTransactionPool;
    private static final Platform platform;

    // borrowed from FCMTransactionPool for use in getMapKeyForFCMTx()
    private static final int UNRESTRICTED_SUBSET = 0;

    /**
     * count of each type of entities created by each node
     */
    private static final int countEach = 10;
    /**
     * accountNum range of each type of entities created by each node
     */
    private static final int cryptoNum1 = countEach * 2;

    private static final int fcqNum1 = countEach * 6;

    private static final int TRANSACTION_RECORD_INDEX = 0;
    private static final int TRANSACTION_RECORD_BALANCE = 0;
    private static final int BALANCE = 0;

    static {
        platform = Mockito.mock(Platform.class);
        Mockito.when(platform.getSelfId()).thenReturn(NodeId.of(0L));
    }

    @BeforeAll
    public static void init() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkle.map.*");
        registry.registerConstructable(new ClassConstructorPair(MapValueFCQ.class, MapValueFCQ::new));
        allowErrorsCfg.setCreateOnExistingEntities(true);
        allowErrorsCfg.setPerformOnDeleted(true);
        allowErrorsCfg.setPerformOnNonExistingEntities(true);
    }

    @Test
    public void getSelfEntitiesListTest() {
        putEntitiesForGetListTesting();

        assertEquals(countEach, expectedFCMFamily0.getSelfEntitiesList(Crypto).size());
        assertEquals(countEach, expectedFCMFamily1.getSelfEntitiesList(Crypto).size());

        assertEquals(countEach * 2, expectedFCMFamily0.getSelfEntitiesList(FCQ).size());
        assertEquals(countEach * 2, expectedFCMFamily1.getSelfEntitiesList(FCQ).size());
    }

    @Test
    public void getEntityListTest() {
        putEntitiesForGetListTesting();

        assertEquals(countEach * 2, expectedFCMFamily0.getEntityList(Crypto).size());
        assertEquals(countEach * 2, expectedFCMFamily1.getEntityList(Crypto).size());

        assertEquals(countEach * 4, expectedFCMFamily0.getEntityList(FCQ).size());
        assertEquals(countEach * 4, expectedFCMFamily1.getEntityList(FCQ).size());
    }

    void putEntitiesForGetListTesting() {
        // add entities to expectedFCMFamilies
        expectedFCMFamily0 = new ExpectedFCMFamilyImpl();
        expectedFCMFamily0.setWeightedNodeNum(weightedNodesNum);
        expectedFCMFamily1 = new ExpectedFCMFamilyImpl();
        expectedFCMFamily1.setNodeId(1);
        expectedFCMFamily1.setWeightedNodeNum(weightedNodesNum);

        for (int i = 0; i < fcqNum1; i++) {
            EntityType type;
            long shardId;
            long realmId;
            if ((i / 10) % 2 == 0) {
                shardId = realmId = 0;
            } else {
                shardId = realmId = 1;
            }
            if (i < cryptoNum1) {
                type = Crypto;
            } else {
                type = FCQ;
            }
            MapKey mapKey = new MapKey(shardId, realmId, i);
            ExpectedValue expectedValue = new ExpectedValue(type, null);
            expectedFCMFamily0.addEntityToExpectedMap(mapKey, expectedValue);
            expectedFCMFamily1.addEntityToExpectedMap(mapKey, expectedValue);
        }
    }

    /**
     * build FCMFamily
     *
     * @param size
     * 		size of each entity map
     * @return
     */
    FCMFamily buildFCMFamily(final int size) {
        final FCMFamily fcmFamily = new FCMFamily(true);
        for (int i = 0; i < size; i++) {
            final MapKey cryptoKey = new MapKey(0, 0, i);
            final MapValueData randomValue = getRandomValueData();
            final MerkleMap<MapKey, MapValueData> map = fcmFamily.getMap();
            map.put(cryptoKey, randomValue);

            final MapKey fcqKey = new MapKey(1, 1, i + size * 2L);
            fcmFamily.getAccountFCQMap().put(fcqKey, getRandomValueFCQ());
        }
        return fcmFamily;
    }

    MapValueData getRandomValueData() {
        int randomValue = ThreadLocalRandom.current().nextInt();
        return new MapValueData(
                randomValue,
                randomValue + 1,
                randomValue + 2,
                false,
                ThreadLocalRandom.current().nextLong());
    }

    MapValueFCQ<TransactionRecord> getRandomValueFCQ() {
        FCQueue<TransactionRecord> fcQueue = new FCQueue<>();
        byte[] content = new byte[100];
        ThreadLocalRandom.current().nextBytes(content);
        fcQueue.add(new TransactionRecord(
                TRANSACTION_RECORD_INDEX, TRANSACTION_RECORD_BALANCE, content, DEFAULT_EXPIRATION_TIME));
        ThreadLocalRandom.current().nextBytes(content);

        return new MapValueFCQ<>(BALANCE, fcQueue);
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 10_000})
    public void addEntitiesFromActualFCMsTest(int size) {
        FCMFamily fcmFamily = buildFCMFamily(size);
        long consensusTime = Instant.now().toEpochMilli();

        Instant start = Instant.now();
        expectedFCMFamily0.rebuildExpectedMap(fcmFamily, false, consensusTime);
        System.out.println("RebuildExpectedMap costs "
                + Duration.between(start, Instant.now()).getSeconds() + " secs");

        assertEquals(size * 2, fcmFamily.getTotalCount());
        assertEquals(size * 3, expectedFCMFamily0.getNextIdToCreate());

        assertEquals(size, expectedFCMFamily0.getEntityList(Crypto).size());
        assertEquals(size, expectedFCMFamily0.getEntityList(FCQ).size());

        assertEquals(size, expectedFCMFamily0.getSelfEntitiesList(Crypto).size());
        // FCQs are (1, 1, i), so they are created by node1
        assertEquals(0, expectedFCMFamily0.getSelfEntitiesList(FCQ).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000})
    @Tag(TestComponentTags.EXPECTED_MAP)
    @DisplayName("Add Entities during Reconnect test")
    public void addEntitiesFromActualFCMsReconnectTest(int size) {
        int startIndex = 100000;
        expectedFCMFamily0.getExpectedMap().clear();
        FCMFamily fcmFamily = buildFCMFamily(size);
        long consensusTime = Instant.now().toEpochMilli();
        assertEquals(0, expectedFCMFamily0.getExpectedMap().size());
        addInitializedCreateDeleteEntities(startIndex);
        assertEquals(10, expectedFCMFamily0.getExpectedMap().size());

        Instant start = Instant.now();
        expectedFCMFamily0.rebuildExpectedMap(fcmFamily, false, consensusTime);
        System.out.println("RebuildExpectedMap costs "
                + Duration.between(start, Instant.now()).getSeconds() + " secs");

        // check if Create initialized transactions are added to new expectedMap from old expectedMap after reconnect
        // check if Delete Initialized transactions have submit status retained after adding to
        // new ExpectedMap though the state received doesn't have it
        for (int i = startIndex; i < startIndex + 5; i++) {
            MapKey key = new MapKey(0, 0, i);
            assertTrue(expectedFCMFamily0.getExpectedMap().containsKey(key));
            assertEquals(
                    expectedFCMFamily0
                            .getExpectedMap()
                            .get(key)
                            .getLatestSubmitStatus()
                            .getTransactionState(),
                    INITIALIZED);
            assertEquals(
                    expectedFCMFamily0
                            .getExpectedMap()
                            .get(key)
                            .getLatestSubmitStatus()
                            .getTransactionType(),
                    Create);
            assertNull(expectedFCMFamily0.getExpectedMap().get(key).getLatestHandledStatus(), "status should be null");
        }

        for (int i = 0; i < 5; i++) {
            MapKey key = new MapKey(0, 0, i);
            assertTrue(expectedFCMFamily0.getExpectedMap().containsKey(key));
            assertEquals(
                    expectedFCMFamily0
                            .getExpectedMap()
                            .get(key)
                            .getLatestSubmitStatus()
                            .getTransactionState(),
                    INITIALIZED);
            assertEquals(
                    expectedFCMFamily0
                            .getExpectedMap()
                            .get(key)
                            .getLatestSubmitStatus()
                            .getTransactionType(),
                    Delete);
            assertTrue(expectedFCMFamily0.getExpectedMap().get(key).getLatestHandledStatus() != null);
        }
    }

    /**
     * Add a few entities with Delete and Create INITIALIZED transactions,
     * to check if they are present after reconnect
     *
     * @param startIndex
     */
    private void addInitializedCreateDeleteEntities(int startIndex) {
        for (int i = startIndex; i < startIndex + 5; i++) {
            MapKey key = new MapKey(0, 0, i);
            LifecycleStatus submitStatus = LifecycleStatus.builder()
                    .setTransactionType(Create)
                    .setTransactionState(INITIALIZED)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .setNodeId(0)
                    .build();
            expectedFCMFamily0.getExpectedMap().put(key, new ExpectedValue(Crypto, submitStatus));
        }

        for (int i = 0; i < 5; i++) {
            MapKey key = new MapKey(0, 0, i);
            LifecycleStatus submitStatus = LifecycleStatus.builder()
                    .setTransactionType(Delete)
                    .setTransactionState(INITIALIZED)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .setNodeId(0)
                    .build();
            expectedFCMFamily0.getExpectedMap().put(key, new ExpectedValue(Crypto, submitStatus));
        }
    }

    private void initForGetMapKeyForFCMTxTest() {
        expectedFCMFamily0 = new ExpectedFCMFamilyImpl();
        expectedFCMFamily0.setWeightedNodeNum(weightedNodesNum);

        deletedCrypto = new MapKey(0, 0, 1);
        ExpectedValue deletedCryptoValue = new ExpectedValue(Crypto, null);
        deletedCryptoValue.setLatestHandledStatus(new LifecycleStatus(HANDLED, Delete, timestamp, NODE_ID_ZERO));

        toBeDeletedCrypto = new MapKey(0, 0, 2);
        ExpectedValue toBeDeletedCryptoValue = new ExpectedValue(Crypto, null);
        toBeDeletedCryptoValue.setLatestHandledStatus(new LifecycleStatus(HANDLED, Create, timestamp, NODE_ID_THREE));

        expiredCrypto = new MapKey(0, 0, 3);
        ExpectedValue expiredCryptoValue = new ExpectedValue(Crypto, null);
        expiredCryptoValue.setLatestHandledStatus(new LifecycleStatus(HANDLED, Expire, timestamp, NODE_ID_ONE));

        existCrypto = new MapKey(0, 0, 4);
        ExpectedValue existCryptoValue =
                new ExpectedValue(Crypto, new LifecycleStatus(INITIALIZED, Create, timestamp, NODE_ID_TWO));

        expectedFCMFamily0.addEntityToExpectedMap(deletedCrypto, deletedCryptoValue);
        expectedFCMFamily0.addEntityToExpectedMap(toBeDeletedCrypto, toBeDeletedCryptoValue);
        expectedFCMFamily0.addEntityToExpectedMap(expiredCrypto, expiredCryptoValue);
        expectedFCMFamily0.addEntityToExpectedMap(existCrypto, existCryptoValue);

        existFCQ = new MapKey(0, 0, 5);
        ExpectedValue existFCQValue = new ExpectedValue(FCQ, null);
        existFCQValue.setLatestHandledStatus(new LifecycleStatus(HANDLED, Create, timestamp, NODE_ID_ZERO));

        expectedFCMFamily0.addEntityToExpectedMap(existFCQ, existFCQValue);

        // add Entities with different realmId
        for (int i = 0; i < 10; i++) {
            MapKey key = new MapKey(1, 1, i);
            ExpectedValue value = new ExpectedValue(Crypto, null);
            value.setLatestHandledStatus(new LifecycleStatus(HANDLED, Create, timestamp, NODE_ID_ONE));
            expectedFCMFamily0.addEntityToExpectedMap(key, value);
        }

        // 10 deleted FCQ
        for (int nodeId = 100; nodeId < 110; nodeId++) {
            ExpectedValue deletedFCQValue = new ExpectedValue(FCQ, null);
            deletedFCQValue.setLatestHandledStatus(new LifecycleStatus(HANDLED, Delete, timestamp, NODE_ID_ZERO));
            MapKey key = new MapKey(0, 0, nodeId);
            expectedFCMFamily0.addEntityToExpectedMap(key, deletedFCQValue);
        }
        assertEquals(expectedFCMFamily0.getEntityList(FCQ).size(), 11);
        assertEquals(expectedFCMFamily0.getSelfEntitiesList(FCQ).size(), 11);
        assertEquals(expectedFCMFamily0.getEntityList(Crypto).size(), 14);
        assertEquals(expectedFCMFamily0.getSelfEntitiesList(Crypto).size(), 4);
        assertEquals(expectedFCMFamily0.getExpectedMap().size(), 25);
    }

    private void putEntitiesForTests() {
        deletedCrypto = new MapKey(0, 0, 2);
        expiredCrypto = new MapKey(0, 0, 3);

        createdFCQ = new MapKey(0, 0, 1);

        createdCrypto = new MapKey(0, 0, 4);
        handleRejectedCrypto = new MapKey(0, 0, 5);
        errorCrypto = new MapKey(0, 0, 6);

        expectedFCMFamily0.clear();
        // put entities into expectedMap
        expectedFCMFamily0.addEntityToExpectedMap(errorCrypto, new ExpectedValue(Crypto, null).setErrored(true));
        expectedFCMFamily0.addEntityToExpectedMap(
                deletedCrypto,
                new ExpectedValue(Crypto, null)
                        .setLatestHandledStatus(LifecycleStatus.builder()
                                .setTransactionState(HANDLED)
                                .setTransactionType(Delete)
                                .build()));
        expectedFCMFamily0.addEntityToExpectedMap(
                expiredCrypto,
                new ExpectedValue(Crypto, null)
                        .setLatestHandledStatus(LifecycleStatus.builder()
                                .setTransactionState(HANDLED)
                                .setTransactionType(Expire)
                                .build()));

        expectedFCMFamily0.addEntityToExpectedMap(
                createdCrypto,
                new ExpectedValue(Crypto, null)
                        .setLatestHandledStatus(LifecycleStatus.builder()
                                .setTransactionState(HANDLED)
                                .setTransactionType(Create)
                                .build()));

        expectedFCMFamily0.addEntityToExpectedMap(
                handleRejectedCrypto,
                new ExpectedValue(Crypto, null)
                        .setLatestHandledStatus(LifecycleStatus.builder()
                                .setTransactionState(HANDLE_REJECTED)
                                .setTransactionType(Create)
                                .build()));

        expectedFCMFamily0.addEntityToExpectedMap(
                createdFCQ,
                new ExpectedValue(FCQ, null)
                        .setLatestHandledStatus(LifecycleStatus.builder()
                                .setTransactionState(HANDLED)
                                .setTransactionType(Create)
                                .build()));

        createdNotHandledFCQ = new MapKey(0, 0, 7);
        ExpectedValue createdNotHandledFCQValue = new ExpectedValue(
                FCQ, new LifecycleStatus(INITIALIZED, Create, Instant.now().toEpochMilli(), NODE_ID_ZERO));

        expectedFCMFamily0.addEntityToExpectedMap(createdNotHandledFCQ, createdNotHandledFCQValue);
    }

    @Test
    public void getMapKeyForFCMTxTest() {
        initForGetMapKeyForFCMTxTest();
        // start from the head of entityList, find the first un-removed entity
        assertEquals(
                toBeDeletedCrypto,
                expectedFCMFamily0.getMapKeyForFCMTx(Delete, Crypto, false, false, UNRESTRICTED_SUBSET));
        // set this entity to be deleted
        expectedFCMFamily0
                .getExpectedMap()
                .get(toBeDeletedCrypto)
                .setLatestHandledStatus(new LifecycleStatus(HANDLED, Delete, timestamp, NODE_ID_ZERO));

        // when performOnDeleted is false, should return a Key which has not been removed yet
        assertEquals(
                existCrypto, expectedFCMFamily0.getMapKeyForFCMTx(Update, Crypto, false, true, UNRESTRICTED_SUBSET));

        // when performOnDeleted is expectedFCMFamily0, can return any Key in accountList
        MapKey crypto = expectedFCMFamily0.getMapKeyForFCMTx(Transfer, Crypto, true, false, UNRESTRICTED_SUBSET);
        assertNotNull(crypto);

        // fcqList has one key which has not removed
        assertEquals(existFCQ, expectedFCMFamily0.getMapKeyForFCMTx(Update, FCQ, false, false, UNRESTRICTED_SUBSET));
        assertNotNull(expectedFCMFamily0.getMapKeyForFCMTx(Transfer, FCQ, true, false, UNRESTRICTED_SUBSET));
    }

    @Test
    public void testOperateEntitiesOfSameNode() {
        initForGetMapKeyForFCMTxTest();
        // start from the head of entityList, find the first un-removed entity
        assertEquals(
                toBeDeletedCrypto,
                expectedFCMFamily0.getMapKeyForFCMTx(Delete, Crypto, false, true, UNRESTRICTED_SUBSET));
        // when operateEntitiesOfSameNode is true, shardId and realmId should be same as this node Id
        assertEquals(toBeDeletedCrypto.getShardId(), 0);
        assertEquals(toBeDeletedCrypto.getRealmId(), 0);
        // set this entity to be deleted
        expectedFCMFamily0
                .getExpectedMap()
                .get(toBeDeletedCrypto)
                .setLatestHandledStatus(new LifecycleStatus(HANDLED, Delete, timestamp, NODE_ID_ZERO));

        // start from the head of entityList, find a random un-removed self entity
        assertEquals(
                existCrypto, expectedFCMFamily0.getMapKeyForFCMTx(Update, Crypto, false, true, UNRESTRICTED_SUBSET));
        // start from the head of entityList, find random un-removed self entity
        assertEquals(
                existCrypto, expectedFCMFamily0.getMapKeyForFCMTx(Update, Crypto, false, true, UNRESTRICTED_SUBSET));

        // when operateEntitiesOfSameNode is true, shardId and realmId should be same as this node Id
        assertEquals(existCrypto.getShardId(), 0);
        assertEquals(existCrypto.getRealmId(), 0);

        // set this entity to be deleted. This leads to all entities being deleted/expired
        expectedFCMFamily0
                .getExpectedMap()
                .get(existCrypto)
                .setLatestHandledStatus(new LifecycleStatus(HANDLED, Delete, timestamp, NODE_ID_ZERO));
    }

    /**
     * we don't handle a transaction and don't change latestHandledStatus in the following cases:
     * (1) the entity's isErrored flag is true;
     * (2) the entity's latestHandledStatus is HANDLE_REJECTED;
     * (3) the transaction tries to create an existing entity;
     * (4) the transaction tries to operate on an entity which does not exist in ExpectedMap;
     *
     * when return false:
     * if corresponding config is set to allow this error, we don't modify this entity's isErrored
     */
    @Test
    public void shouldHandleAllowErrorsTest() {
        putEntitiesForTests();
        Map<MapKey, ExpectedValue> expectedMap0 = expectedFCMFamily0.getExpectedMap();
        final long nodeId = 0;
        // if the entity's isErrored flag is true, should return false
        assertTrue(expectedMap0.get(errorCrypto).isErrored());
        assertTrue(expectedFCMFamily0.shouldHandle(errorCrypto, Create, allowErrorsCfg, Crypto, timestamp, nodeId));
        assertFalse(expectedFCMFamily0.shouldHandle(errorCrypto, Update, allowErrorsCfg, Crypto, timestamp, nodeId));
        assertFalse(expectedFCMFamily0.shouldHandle(errorCrypto, Transfer, allowErrorsCfg, Crypto, timestamp, nodeId));
        assertFalse(expectedFCMFamily0.shouldHandle(errorCrypto, Delete, allowErrorsCfg, Crypto, timestamp, nodeId));
        // isErrored should still be true
        assertTrue(expectedMap0.get(errorCrypto).isErrored());

        // if the entity's latestHandledStatus is HANDLE_REJECTED, should return false
        assertFalse(expectedFCMFamily0.shouldHandle(
                handleRejectedCrypto, Create, allowErrorsCfg, Crypto, timestamp, nodeId));
        assertFalse(expectedFCMFamily0.shouldHandle(
                handleRejectedCrypto, Update, allowErrorsCfg, Crypto, timestamp, nodeId));
        assertFalse(expectedFCMFamily0.shouldHandle(
                handleRejectedCrypto, Transfer, allowErrorsCfg, Crypto, timestamp, nodeId));
        assertFalse(expectedFCMFamily0.shouldHandle(
                handleRejectedCrypto, Delete, allowErrorsCfg, Crypto, timestamp, nodeId));

        // if the transaction tries to create an existing entity, should return false
        // but isErrored should not change
        assertFalse(expectedMap0.get(createdCrypto).isErrored());
        assertFalse(expectedFCMFamily0.shouldHandle(createdCrypto, Create, allowErrorsCfg, Crypto, timestamp, nodeId));
        // isErrored should still be false
        assertFalse(expectedMap0.get(createdCrypto).isErrored());

        assertFalse(expectedFCMFamily0.shouldHandle(
                handleRejectedCrypto, Create, allowErrorsCfg, Crypto, timestamp, nodeId));

        assertFalse(expectedMap0.get(expiredCrypto).isErrored());
        assertFalse(expectedFCMFamily0.shouldHandle(expiredCrypto, Create, allowErrorsCfg, Crypto, timestamp, nodeId));
        // isErrored should still be false
        assertFalse(expectedMap0.get(expiredCrypto).isErrored());

        assertFalse(expectedMap0.get(deletedCrypto).isErrored());
        assertFalse(expectedFCMFamily0.shouldHandle(deletedCrypto, Create, allowErrorsCfg, Crypto, timestamp, nodeId));
        // isErrored should still be false
        assertFalse(expectedMap0.get(deletedCrypto).isErrored());

        // if lastedHandledStatus is null, we should only handle Create transactions for this entity
        assertTrue(
                expectedFCMFamily0.shouldHandle(createdNotHandledFCQ, Create, allowErrorsCfg, FCQ, timestamp, nodeId));
        assertFalse(
                expectedFCMFamily0.shouldHandle(createdNotHandledFCQ, Update, allowErrorsCfg, FCQ, timestamp, nodeId));
    }

    @Test
    public void checkEntityTypeTest() {
        putEntitiesForTests();

        assertTrue(expectedFCMFamily0.checkEntityType(expiredCrypto, Crypto));
        assertTrue(expectedFCMFamily0.checkEntityType(createdCrypto, Crypto));
        assertTrue(expectedFCMFamily0.checkEntityType(deletedCrypto, Crypto));
        assertTrue(expectedFCMFamily0.checkEntityType(createdFCQ, FCQ));

        assertFalse(expectedFCMFamily0.checkEntityType(createdCrypto, FCQ));
    }

    @Test
    public void setLatestHandledStatusForKeyTest() throws IOException {
        putEntitiesForTests();
        Map<MapKey, ExpectedValue> expectedMap0 = expectedFCMFamily0.getExpectedMap();
        // mapKey to create
        final MapKey newCrypto = new MapKey(0, 0, expectedFCMFamily0.getNextIdToCreate());
        final MapValueData value = new MapValueData(
                ThreadLocalRandom.current().nextLong(),
                10,
                20,
                false,
                ThreadLocalRandom.current().nextLong());
        final Hash hash = value.calculateHash();
        final TransactionState state = HANDLED;
        final TransactionType txType = Create;
        final EntityType entityType = Crypto;
        final long timestamp = ThreadLocalRandom.current().nextLong();
        final long nodeId = ThreadLocalRandom.current().nextLong(10);
        final boolean error = false;

        // the mapKey doesn't exist in expectedMap
        assertFalse(expectedMap0.containsKey(newCrypto));

        // set LatestHandledStatus after handling a create transaction
        expectedFCMFamily0.setLatestHandledStatusForKey(
                newCrypto, entityType, value, state, txType, timestamp, nodeId, error);

        // the mapKey exists in expectedMap
        ExpectedValue expectedValue = expectedMap0.get(newCrypto);
        assertNotNull(expectedValue);
        // hash, entityType, isErrored should match
        assertEquals(hash, expectedValue.getHash());
        assertEquals(entityType, expectedValue.getEntityType());
        assertEquals(error, expectedValue.isErrored());

        // suppose the create transaction is sent by another node,
        // this new entity's latestSubmitStatus should be null
        assertNull(expectedValue.getLatestSubmitStatus());
        // new entity's historyHandledStatus should be null
        assertNull(expectedValue.getHistoryHandledStatus());
        // new entity's latestHandledStatus should not be null, all fields should match expected value
        LifecycleStatus firstHandled = expectedValue.getLatestHandledStatus();
        assertNotNull(firstHandled);
        assertEquals(state, firstHandled.getTransactionState());
        assertEquals(txType, firstHandled.getTransactionType());
        assertEquals(timestamp, firstHandled.getTimestamp());
        assertEquals(nodeId, firstHandled.getNodeId());

        // an update transaction, suppose the handling failed
        final MapValueData newValue = new MapValueData(
                ThreadLocalRandom.current().nextLong(),
                30,
                40,
                true,
                ThreadLocalRandom.current().nextLong());
        final Hash newHash = newValue.calculateHash();
        final TransactionState newState = HANDLE_FAILED;
        final TransactionType newTxType = Update;
        final long newTimestamp = ThreadLocalRandom.current().nextLong();
        final long newNodeId = ThreadLocalRandom.current().nextLong(10);
        final boolean newError = true;

        // set LatestHandledStatus after handling the update transaction
        expectedFCMFamily0.setLatestHandledStatusForKey(
                newCrypto, entityType, newValue, newState, newTxType, newTimestamp, newNodeId, newError);

        // the mapKey exists in expectedMap
        expectedValue = expectedMap0.get(newCrypto);
        assertNotNull(expectedValue);
        // hash, entityType, isErrored should match
        assertEquals(newHash, expectedValue.getHash());
        assertEquals(entityType, expectedValue.getEntityType());
        assertEquals(newError, expectedValue.isErrored());

        // latestSubmitStatus should still be null
        assertNull(expectedValue.getLatestSubmitStatus());
        // new entity's historyHandledStatus should be equal to firstHandled
        assertTrue(firstHandled == expectedValue.getHistoryHandledStatus());
        // new entity's latestHandledStatus should not be null
        LifecycleStatus latestHandled = expectedValue.getLatestHandledStatus();
        assertNotNull(latestHandled);
        assertEquals(newState, latestHandled.getTransactionState());
        assertEquals(newTxType, latestHandled.getTransactionType());
        assertEquals(newTimestamp, latestHandled.getTimestamp());
        assertEquals(newNodeId, latestHandled.getNodeId());
    }

    @Test
    public void insertMissingEntitiesTest() {
        FCMTransaction trans = createTransaction(Create);
        byte[] payloadWithSig = signTransaction(
                TestTransaction.newBuilder().setFcmTransaction(trans).build().toByteArray(), pttTransactionPool);
        // If the mapkey extracted from FCMTransaction (0.0.10) and mapKey provided (0.0.30) doesn't
        // match entity is not inserted to ExpectedMap
        boolean isInsertedWongMapKey = expectedFCMFamily0.insertMissingEntity(
                payloadWithSig, expectedFCMFamily0, new MapKey(0, 0, 30), config);
        assertFalse(isInsertedWongMapKey);
        assertFalse(expectedFCMFamily0.getExpectedMap().containsKey(new MapKey(0, 0, 30)));
        // If the mapkey extracted from FCMTransaction (0.0.10) and mapKey provided (0.0.10)
        // matches entity is inserted to ExpectedMap
        boolean isInsertedCorrectMapKey = expectedFCMFamily0.insertMissingEntity(
                payloadWithSig, expectedFCMFamily0, new MapKey(0, 0, 10), config);
        assertTrue(isInsertedCorrectMapKey);
        assertTrue(expectedFCMFamily0.getExpectedMap().containsKey(new MapKey(0, 0, 10)));
    }

    @Test
    public void insertMissingEntitiesConfigEnabledTest() {
        FCMTransaction trans = createTransaction(Update);
        PayloadConfig config = PayloadConfig.builder()
                .setAppendSig(true)
                .setPerformOnNonExistingEntities(true)
                .build();

        byte[] payloadWithSig = signTransaction(
                TestTransaction.newBuilder().setFcmTransaction(trans).build().toByteArray(), pttTransactionPool);

        boolean isInsertedNonExistingKey = expectedFCMFamily0.insertMissingEntity(
                payloadWithSig, expectedFCMFamily0, new MapKey(0, 0, 10), config);
        assertFalse(isInsertedNonExistingKey);
        assertFalse(expectedFCMFamily0.getExpectedMap().containsKey(new MapKey(0, 0, 10)));
    }

    private FCMTransaction createTransaction(TransactionType type) {
        pttTransactionPool = new PttTransactionPool(
                platform,
                0,
                config,
                "test",
                Mockito.mock(FCMConfig.class),
                Mockito.mock(VirtualMerkleConfig.class),
                Mockito.mock(FreezeConfig.class),
                Mockito.mock(TransactionPoolConfig.class),
                Mockito.mock(TransactionSubmitter.class),
                Mockito.mock(ExpectedFCMFamily.class));
        switch (type) {
            case Create:
                CreateAccount createAccount = CreateAccount.newBuilder()
                        .setAccountID(10)
                        .setRealmID(0)
                        .setShardID(0)
                        .setBalance(10)
                        .setReceiveThreshold(0)
                        .setSendThreshold(0)
                        .setRequireSignature(false)
                        .build();
                return FCMTransaction.newBuilder()
                        .setCreateAccount(createAccount)
                        .build();
            case Update:
                UpdateAccount updateAccount = UpdateAccount.newBuilder()
                        .setAccountID(10)
                        .setRealmID(0)
                        .setShardID(0)
                        .setBalance(10)
                        .setReceiveThreshold(0)
                        .setSendThreshold(0)
                        .setRequireSignature(false)
                        .build();
                return FCMTransaction.newBuilder()
                        .setUpdateAccount(updateAccount)
                        .build();
            default:
                return FCMTransaction.newBuilder().build();
        }
    }

    @Test
    public void calculateHashTest() {
        MapValueData data = new MapValueData(3, 4, 5, true, -1);
        assertNotEquals(new Hash(), data.calculateHash());
    }

    @Test
    public void calculateSelfListCapacityTest() {
        final ExpectedFCMFamilyImpl expectedFCMFamily = new ExpectedFCMFamilyImpl();
        expectedFCMFamily.setNodeId(NODE_ID_ZERO);
        expectedFCMFamily.setWeightedNodeNum(weightedNodesNum);

        assertEquals(1000, expectedFCMFamily.calculateSelfListCapacity(1000 * weightedNodesNum));
        assertEquals(1001, expectedFCMFamily.calculateSelfListCapacity(1000 * weightedNodesNum + 1));
        assertEquals(1001, expectedFCMFamily.calculateSelfListCapacity(1000 * weightedNodesNum + 2));
        assertEquals(1001, expectedFCMFamily.calculateSelfListCapacity(1000 * weightedNodesNum + 3));
    }

    @Test
    public void setLatestHandledStatusForKeyWithNullValue() {
        final ExpectedFCMFamilyImpl expectedFCMFamily = new ExpectedFCMFamilyImpl();
        expectedFCMFamily.setNodeId(NODE_ID_ZERO);
        expectedFCMFamily.setWeightedNodeNum(weightedNodesNum);

        final MapKey key = new MapKey(0, 0, 0);
        expectedFCMFamily.setLatestHandledStatusForKey(key, FCQ, null, HANDLED, Create, 0, 0, false);
    }
}
