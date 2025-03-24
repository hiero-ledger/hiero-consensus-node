// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.expiration;

import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_NANOSECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.merkle.map.MapValueFCQ;
import com.swirlds.demo.merkle.map.internal.DummyExpectedFCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility class used for Expiration of FCQueue transaction records
 */
public class ExpirationUtils {
    private static final Marker EXPIRATION_MARKER = MarkerManager.getMarker("EXPIRATION");
    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");
    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO_FCQ_SIZE");
    private static final Logger logger = LogManager.getLogger(ExpirationUtils.class);

    /**
     * Add records to expirationQueue after restart/reconnect from FCMFamily accountFCQMap
     *
     * @param fcmFamily
     * @param expirationQueue
     * @param accountsWithExpiringRecords
     */
    public static void addRecordsDuringRebuild(
            FCMFamily fcmFamily,
            BlockingQueue<ExpirationRecordEntry> expirationQueue,
            Set<MapKey> accountsWithExpiringRecords) {
        final long startTime = System.nanoTime();
        logger.info(EXPIRATION_MARKER, "Start to build expirationQueue");
        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap = fcmFamily.getAccountFCQMap();
        for (final Map.Entry<MapKey, MapValueFCQ<TransactionRecord>> entry : fcqMap.entrySet()) {
            final FCQueue<TransactionRecord> records = entry.getValue().getRecords();
            if (!records.isEmpty()) {
                expirationQueue.offer(new ExpirationRecordEntry(records.peek().getExpirationTime(), entry.getKey()));
                accountsWithExpiringRecords.add(entry.getKey());
            }
        }
        final long timeTakenMs = (System.nanoTime() - startTime) / MILLISECONDS_TO_NANOSECONDS;
        logger.info(
                EXPIRATION_MARKER,
                "Finish building expirationQueue in {} ms, "
                        + "expirationQueue size: {}, accountsWithExpiringRecords size: {}, "
                        + "FCQ Account Map size: {}",
                timeTakenMs,
                expirationQueue.size(),
                accountsWithExpiringRecords.size(),
                fcqMap.size());
    }

    /**
     * Recalculate hash of FCQueue entity after records are purged to set hash for ExpectedValue in expectedMap
     *
     * @param id
     * @param expectedFCMFamily
     */
    private static void recalculateHashAfterPurge(MapKey id, ExpectedFCMFamily expectedFCMFamily) {
        if (expectedFCMFamily instanceof DummyExpectedFCMFamily) {
            return;
        }
        ExpectedValue newHashedValue = expectedFCMFamily.getExpectedMap().get(id);
        expectedFCMFamily.getExpectedMap().put(id, newHashedValue.setHash(null));
        logger.trace(
                EXPIRATION_MARKER,
                "New Hash set after purging FCQ transaction records {} for mapKey {}",
                expectedFCMFamily.getExpectedMap().get(id).getHash(),
                id);
    }

    /**
     * Purge FCQueue records whose expiration time is less than current time
     *
     * @param timestamp
     * @param expirationQueue
     * @param accountsWithExpiringRecords
     * @param id
     * @param fcmFamily
     * @param expectedFCMFamily
     * @return Number of expired records purged
     */
    public static long purgeTransactionRecords(
            long timestamp,
            BlockingQueue<ExpirationRecordEntry> expirationQueue,
            Set<MapKey> accountsWithExpiringRecords,
            MapKey id,
            FCMFamily fcmFamily,
            ExpectedFCMFamily expectedFCMFamily) {
        if (fcmFamily == null || fcmFamily.getAccountFCQMap() == null) {
            logger.error(EXCEPTION.getMarker(), "FCMFamily is null, so could not rebuild Expiration Queue");
            return 0;
        }

        if (!fcmFamily.getAccountFCQMap().containsKey(id)) {
            accountsWithExpiringRecords.remove(id);
            return 0;
        }
        MapValueFCQ fcqValue = fcmFamily.getAccountFCQMap().getForModify(id);
        final long sizeBeforePurge = fcqValue.getRecordsSize();
        final FCQueue<TransactionRecord> updatedRecords = fcqValue.getRecords();
        // remove expired records based on timestamp
        while (isExpiredRecordPresent(updatedRecords, timestamp)) {
            updatedRecords.poll();
        }
        fcmFamily.getAccountFCQMap().replace(id, fcqValue);

        if (fcqValue.getRecords().isEmpty()) {
            accountsWithExpiringRecords.remove(id);
        } else {
            long newEarliestExpiry = ((TransactionRecord) fcqValue.getRecords().peek()).getExpirationTime();
            ExpirationRecordEntry newEre = new ExpirationRecordEntry(newEarliestExpiry, id);
            expirationQueue.offer(newEre);
        }
        // as transaction records are removed FCQueue hash will be changed. Recalculate for ExpectedNap
        recalculateHashAfterPurge(id, expectedFCMFamily);
        return sizeBeforePurge - fcqValue.getRecordsSize();
    }

    /**
     * Validate if there are any transactionRecords with expiration time below lastPurgeTimestamp
     *
     * @param lastPurgeTimestamp
     * @param fcqMap
     * @param selfId
     * @return
     */
    public static boolean isRecordExpirationValid(
            final long lastPurgeTimestamp,
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap,
            final NodeId selfId) {
        boolean passExpirationCheck = true;
        for (final Map.Entry<MapKey, MapValueFCQ<TransactionRecord>> entry : fcqMap.entrySet()) {
            final FCQueue<TransactionRecord> records = entry.getValue().getRecords();
            if (isExpiredRecordPresent(records, lastPurgeTimestamp)) {
                passExpirationCheck = false;
                logger.info(
                        ERROR,
                        "{} Fails Expiration Checking. lastPurgeTimestamp: {}; MapKey: {}; first record's "
                                + "expirationTime: {}",
                        selfId,
                        lastPurgeTimestamp,
                        entry.getKey(),
                        records.peek().getExpirationTime());
            }
        }
        return passExpirationCheck;
    }

    /**
     * If the top most record in FCQueue has ExpirationTime before or equal to
     * lastPurgeTimestamp return true
     *
     * @param records
     * @param lastPurgeTimestamp
     * @return
     */
    private static boolean isExpiredRecordPresent(FCQueue<TransactionRecord> records, long lastPurgeTimestamp) {
        return !records.isEmpty() && records.peek().getExpirationTime() <= lastPurgeTimestamp;
    }

    /**
     * We can take for granted that records are added in monotonic increasing order of expiry.
     * If accountsWithExpiringRecords contains this mapKey, which denotes this account already has one entry (with
     * earlier expiration time than this record) in expirationQueue, we don't have replace it
     *
     * @param record
     * @param mapKey
     * @param expirationQueue
     * @param accountsWithExpiringRecords
     */
    public static void addRecordToExpirationQueue(
            final TransactionRecord record,
            final MapKey mapKey,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        if (expirationQueue == null || accountsWithExpiringRecords == null) {
            logger.info(
                    MARKER,
                    "TransactionRecord could not be added to expirationQueue, as expirationQueue {} or "
                            + "accountsWithExpiringRecords {} is null",
                    expirationQueue,
                    accountsWithExpiringRecords);
            return;
        }
        if (accountsWithExpiringRecords.isEmpty() || !accountsWithExpiringRecords.contains(mapKey)) {
            expirationQueue.offer(new ExpirationRecordEntry(record.getExpirationTime(), mapKey));
            accountsWithExpiringRecords.add(mapKey);
        }
    }
}
