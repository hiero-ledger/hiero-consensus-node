// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map.internal;

import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.demo.platform.PAYLOAD_CATEGORY;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.platform.PayloadConfig;
import com.swirlds.demo.platform.PlatformTestingToolState;
import com.swirlds.demo.platform.Triple;
import com.swirlds.demo.platform.UnsafeMutablePTTStateAccessor;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.lifecycle.LifecycleStatus;
import com.swirlds.merkle.test.fixtures.map.lifecycle.TransactionState;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.system.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/**
 * Utilities used for ExpectedMap in ExpectedFCMFamily
 */
public class ExpectedMapUtils {

    private static final Logger logger = LogManager.getLogger(ExpectedMapUtils.class);
    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");

    /**
     * Build expectedMap if necessary after reconnect Complete notification is received
     *
     * @param notification
     * 		ReconnectCompleteNotification received from platform
     */
    public static void buildExpectedMapAfterReconnect(
            final ReconnectCompleteNotification notification, final Platform platform) {
        // if the config is set to not check FCM after running test,
        // we don't need to rebuild ExpectedMap after reconnect

        if (notification.getState() == null) {
            logger.info(ERROR, "received state is null, " + "so do not rebuildExpectedMap after reconnect");
            return;
        }

        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {
            final PlatformTestingToolState state = wrapper.get();
            state.rebuildExpectedMapFromState(notification.getConsensusTimestamp(), false);
        }
    }

    /**
     * Modify the latest submit status to SUBMITTED or SUBMISSION_FAILED
     * When the latest submit status is SUBMISSION_FAILED set the isErrored flag to be true
     * If the error flag is set there will be no more transactions submitted to that account.
     */
    public static void modifySubmitStatus(
            final PlatformTestingToolState state,
            final boolean isSuccess,
            final boolean isActive,
            final Triple<byte[], PAYLOAD_TYPE, MapKey> submittedPayloadTriple,
            final PayloadConfig payloadConfig) {
        if (!isActive || state == null) {
            return;
        }

        ExpectedFCMFamily expectedFCMFamily = state.getStateExpectedMap();

        MapKey key = submittedPayloadTriple.right();

        if (key == null) {
            return;
        }

        final PAYLOAD_TYPE payload_type = submittedPayloadTriple.middle();
        if (payload_type == PAYLOAD_TYPE.TYPE_MINT_TOKEN
                || payload_type == PAYLOAD_TYPE.TYPE_BURN_TOKEN
                || payload_type == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_CREATE
                || payload_type == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_UPDATE
                || payload_type == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_DELETE
                || payload_type == PAYLOAD_TYPE.TYPE_TRANSFER_TOKEN) {
            return;
        }

        ExpectedValue value = expectedFCMFamily.getExpectedMap().get(key);

        if (value == null) {
            // The transactions for virtual merkle tests are inserted into the expected map
            // when they are created, and because of that there no reason to insert the
            // transaction now.
            if (payload_type.getPayloadCategory() == PAYLOAD_CATEGORY.CATEGORY_VIRTUAL_MERKLE) {
                return;
            }

            // This might happen during reconnect, when a transaction is initialized, and not
            // submitted to network yet. After reconnect finishes, when transaction is submitted to network
            // it doesn't exist in expectedMap as its cleared, while rebuilding after reconnect.
            // So we add those entities to expectedMap, if they doesn't exist
            boolean isEntityInserted = expectedFCMFamily.insertMissingEntity(
                    submittedPayloadTriple.left(), expectedFCMFamily, key, payloadConfig);
            if (!isEntityInserted) {
                return;
            }
        }

        value = expectedFCMFamily.getExpectedMap().get(key);
        LifecycleStatus latestSubmitStatus = value.getLatestSubmitStatus();

        if (latestSubmitStatus == null) {
            // LatestSubmitStatus can be null in the case when an entity is rebuilt
            // during restart or reconnect. Set latestSubmitStatus if it is null from the transaction payload
            LifecycleStatus status =
                    expectedFCMFamily.buildLifecycleStatusFromPayload(submittedPayloadTriple.left(), payloadConfig);
            value.setLatestSubmitStatus(status);
        }

        final TransactionState transactionstate =
                isSuccess ? TransactionState.SUBMITTED : TransactionState.SUBMISSION_FAILED;
        value.getLatestSubmitStatus().setTransactionState(transactionstate);
        expectedFCMFamily.getExpectedMap().put(key, value);
    }
}
