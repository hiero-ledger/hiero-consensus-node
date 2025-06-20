// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import com.hedera.node.app.hapi.utils.builder.RequestBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FixedCustomFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Fee builder for Consensus service transactions. */
public final class ConsensusServiceFeeBuilder extends FeeBuilder {
    private static final int FIXED_HBAR_REPR_SIZE = LONG_SIZE;
    private static final int FIXED_HTS_REPR_SIZE = LONG_SIZE + BASIC_ENTITY_ID_SIZE;

    private ConsensusServiceFeeBuilder() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Computes fee for ConsensusCreateTopic transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public static FeeData getConsensusCreateTopicFee(final TransactionBody txBody, final SigValueObj sigValObj) {
        final var createTopicTxBody = txBody.getConsensusCreateTopic();
        final var variableSize = computeVariableSizedFieldsUsage(
                createTopicTxBody.getAdminKey(),
                createTopicTxBody.getSubmitKey(),
                createTopicTxBody.getMemo(),
                createTopicTxBody.hasAutoRenewAccount());
        long extraRbsServices = 0;
        if (createTopicTxBody.hasAutoRenewPeriod()) {
            // Scale the rbs based on topic size and custom fees
            extraRbsServices = getTopicRamBytes(variableSize + bytesNeededToRepr(createTopicTxBody.getCustomFeesList()))
                    * createTopicTxBody.getAutoRenewPeriod().getSeconds();
        }
        return getTxFeeMatrices(
                txBody,
                sigValObj,
                variableSize + LONG_SIZE, // For autoRenewPeriod
                extraRbsServices,
                BASIC_ENTITY_ID_SIZE * RECEIPT_STORAGE_TIME_SEC); // For topicID in receipt
    }

    /**
     * Computes fee for ConsensusUpdateTopic transaction.
     *
     * @param txBody transaction body
     * @param rbsIncrease rbs increase
     * @param sigValObj signature value object
     * @return fee data
     */
    public static FeeData getConsensusUpdateTopicFee(
            final TransactionBody txBody, final long rbsIncrease, final SigValueObj sigValObj) {
        final var updateTopicTxBody = txBody.getConsensusUpdateTopic();
        final var variableSize = computeVariableSizedFieldsUsage(
                updateTopicTxBody.getAdminKey(),
                updateTopicTxBody.getSubmitKey(),
                updateTopicTxBody.getMemo().getValue(),
                updateTopicTxBody.hasAutoRenewAccount());
        return getTxFeeMatrices(
                txBody,
                sigValObj,
                getConsensusUpdateTopicTransactionBodySize(updateTopicTxBody, variableSize),
                rbsIncrease,
                0);
    }

    private static int getConsensusUpdateTopicTransactionBodySize(
            final ConsensusUpdateTopicTransactionBody updateTopicTxBody, final int variableSize) {
        int calcSize = BASIC_ENTITY_ID_SIZE + variableSize; // topicID
        if (updateTopicTxBody.hasExpirationTime()) {
            calcSize += LONG_SIZE;
        }
        if (updateTopicTxBody.hasAutoRenewPeriod()) {
            calcSize += LONG_SIZE;
        }
        return calcSize;
    }

    /**
     * Computes additional rbs (services) for update topic transaction. If any of the variable sized
     * fields change, or the expiration time changes, rbs for the topic may increase
     *
     * @param txValidStartTimestamp transaction valid start timestamp
     * @param oldAdminKey old admin key
     * @param oldSubmitKey old submit key
     * @param oldMemo old memo
     * @param hasOldAutoRenewAccount boolean representing old auto renew account
     * @param oldExpirationTimeStamp old expiration timestamp
     * @param updateTopicTxBody update topic transaction body
     * @return long representing rbs increase
     */
    public static long getUpdateTopicRbsIncrease(
            final Timestamp txValidStartTimestamp,
            final Key oldAdminKey,
            final Key oldSubmitKey,
            final String oldMemo,
            final boolean hasOldAutoRenewAccount,
            final Timestamp oldExpirationTimeStamp,
            final ConsensusUpdateTopicTransactionBody updateTopicTxBody) {
        final var oldRamBytes = getTopicRamBytes(
                computeVariableSizedFieldsUsage(oldAdminKey, oldSubmitKey, oldMemo, hasOldAutoRenewAccount));
        // If value is null, do not update memo field
        final var newMemo =
                updateTopicTxBody.hasMemo() ? updateTopicTxBody.getMemo().getValue() : oldMemo;
        var hasNewAutoRenewAccount = hasOldAutoRenewAccount;
        if (updateTopicTxBody.hasAutoRenewAccount()) { // no change if unspecified
            hasNewAutoRenewAccount = true;
            AccountID account = updateTopicTxBody.getAutoRenewAccount();
            if (account.getAccountNum() == 0 && account.getShardNum() == 0 && account.getRealmNum() == 0) {
                hasNewAutoRenewAccount = false; // cleared if set to 0.0.0
            }
        }
        var newAdminKey = oldAdminKey;
        if (updateTopicTxBody.hasAdminKey()) { // no change if unspecified
            newAdminKey = updateTopicTxBody.getAdminKey();
            if (newAdminKey.hasKeyList() && newAdminKey.getKeyList().getKeysCount() == 0) {
                newAdminKey = null; // cleared if set to empty KeyList
            }
        }
        var newSubmitKey = oldSubmitKey;
        if (updateTopicTxBody.hasSubmitKey()) { // no change if unspecified
            newSubmitKey = updateTopicTxBody.getSubmitKey();
            if (newSubmitKey.hasKeyList() && newSubmitKey.getKeyList().getKeysCount() == 0) {
                newSubmitKey = null; // cleared if set to empty KeyList
            }
        }
        final var newRamBytes = getTopicRamBytes(
                computeVariableSizedFieldsUsage(newAdminKey, newSubmitKey, newMemo, hasNewAutoRenewAccount));

        final var newExpirationTimeStamp =
                updateTopicTxBody.hasExpirationTime() ? updateTopicTxBody.getExpirationTime() : oldExpirationTimeStamp;
        return calculateRbsIncrease(
                txValidStartTimestamp, oldRamBytes, oldExpirationTimeStamp, newRamBytes, newExpirationTimeStamp);
    }

    private static long calculateRbsIncrease(
            final Timestamp txValidStartTimestamp,
            final long oldRamBytes,
            final Timestamp oldExpirationTimeStamp,
            final long newRamBytes,
            final Timestamp newExpirationTimeStamp) {
        final var txValidStart = RequestBuilder.convertProtoTimeStamp(txValidStartTimestamp);
        final var oldExpiration = RequestBuilder.convertProtoTimeStamp(oldExpirationTimeStamp);
        final var newExpiration = RequestBuilder.convertProtoTimeStamp(newExpirationTimeStamp);

        // RBS which has already been paid for.
        final var oldLifetime = Math.min(
                MAX_ENTITY_LIFETIME,
                Duration.between(txValidStart, oldExpiration).getSeconds());
        final var newLifetime = Math.min(
                MAX_ENTITY_LIFETIME,
                Duration.between(txValidStart, newExpiration).getSeconds());
        final var rbsRefund = oldRamBytes * oldLifetime;
        final var rbsCharge = newRamBytes * newLifetime;
        final var netRbs = rbsCharge - rbsRefund;
        return netRbs > 0 ? netRbs : 0;
    }

    /**
     * Computes fee for consensus delete topic transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public static FeeData getConsensusDeleteTopicFee(final TransactionBody txBody, final SigValueObj sigValObj) {
        return getTxFeeMatrices(txBody, sigValObj, BASIC_ENTITY_ID_SIZE, 0, 0);
    }

    /**
     * Given transaction specific additional rbh and bpt components, computes fee components for
     * node, network and services.
     */
    private static FeeData getTxFeeMatrices(
            final TransactionBody txBody,
            final SigValueObj sigValObj,
            final int txBodyDataSize,
            final long extraRbsServices,
            final long extraRbsNetwork) {
        final var feeComponentsBuilder = FeeComponents.newBuilder()
                .setVpt(sigValObj.getTotalSigCount())
                .setSbh(0)
                .setGas(0)
                .setTv(0)
                .setBpr(INT_SIZE)
                .setSbpr(0);
        feeComponentsBuilder.setBpt(
                getCommonTransactionBodyBytes(txBody) + txBodyDataSize + sigValObj.getSignatureSize());
        feeComponentsBuilder.setRbh(getBaseTransactionRecordSize(txBody) * RECEIPT_STORAGE_TIME_SEC + extraRbsServices);
        final var rbsNetwork = getDefaultRbhNetworkSize() + extraRbsNetwork;
        return getFeeDataMatrices(feeComponentsBuilder.build(), sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * @param variableSize value returned by {@link #computeVariableSizedFieldsUsage(Key, Key,
     *     String, boolean)}.
     * @return Estimation of size (in bytes) used by Topic in memory
     */
    public static int getTopicRamBytes(final int variableSize) {
        return BASIC_ENTITY_ID_SIZE
                + // topicID
                3 * LONG_SIZE
                + // expirationTime, sequenceNumber, autoRenewPeriod
                BOOL_SIZE
                + // deleted
                TX_HASH_SIZE
                + // runningHash
                variableSize; // adminKey, submitKey, memo, autoRenewAccount
    }

    /**
     * Compute variable sized fields usage.
     *
     * @param adminKey admin key
     * @param submitKey submit key
     * @param memo memo
     * @param hasAutoRenewAccount boolean representing an auto renew account
     * @return size (in bytes) used by variable sized fields in topic
     */
    public static int computeVariableSizedFieldsUsage(
            final Key adminKey, final Key submitKey, final String memo, final boolean hasAutoRenewAccount) {
        int size = 0;
        if (memo != null) {
            size += memo.getBytes(StandardCharsets.UTF_8).length;
        }
        size += getAccountKeyStorageSize(adminKey);
        size += getAccountKeyStorageSize(submitKey);
        size += hasAutoRenewAccount ? BASIC_ENTITY_ID_SIZE : 0;
        return size;
    }

    /**
     * Computes fee for TopicCreate with custom fees transaction.
     * @param feeSchedule the custom fees
     * @return long representing rbs increase
     */
    public static int bytesNeededToRepr(final List<FixedCustomFee> feeSchedule) {
        int numFixedHbarFees = 0;
        int numFixedHtsFees = 0;
        for (final var fee : feeSchedule) {
            if (fee.hasFixedFee()) {
                if (fee.getFixedFee().hasDenominatingTokenId()) {
                    numFixedHtsFees++;
                } else {
                    numFixedHbarFees++;
                }
            }
        }
        return bytesNeededToRepr(numFixedHbarFees, numFixedHtsFees);
    }

    public static int bytesNeededToRepr(final int numFixedHbarFees, final int numFixedHtsFees) {
        return numFixedHbarFees * plusCollectorSize(FIXED_HBAR_REPR_SIZE)
                + numFixedHtsFees * plusCollectorSize(FIXED_HTS_REPR_SIZE);
    }

    private static int plusCollectorSize(final int feeReprSize) {
        return feeReprSize + BASIC_ENTITY_ID_SIZE;
    }
}
