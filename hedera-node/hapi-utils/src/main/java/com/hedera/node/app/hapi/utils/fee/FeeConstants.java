// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import java.math.BigInteger;
import java.util.List;

/**
 * Holds the structural size constants and the handful of still-live helper methods that the simple-fee
 * path and a few test clients use. This is what remained after the legacy {@code FeeBuilder} engine and
 * its subclasses were removed.
 */
public final class FeeConstants {
    public static final long MAX_ENTITY_LIFETIME = 100L * 365L * 24L * 60L * 60L;

    public static final int LONG_SIZE = 8;
    public static final int FEE_MATRICES_CONST = 1;
    public static final int INT_SIZE = 4;
    public static final int BOOL_SIZE = 4;
    public static final long SOLIDITY_ADDRESS = 20;
    public static final int KEY_SIZE = 32;
    public static final int TX_HASH_SIZE = 48;
    public static final long RECEIPT_STORAGE_TIME_SEC = 180;
    public static final int THRESHOLD_STORAGE_TIME_SEC = 90000;
    public static final int FEE_DIVISOR_FACTOR = 1000;
    public static final int HRS_DIVISOR = 3600;
    public static final int BASIC_ENTITY_ID_SIZE = (3 * LONG_SIZE);
    public static final long BASIC_RICH_INSTANT_SIZE = (1L * LONG_SIZE) + INT_SIZE;
    public static final int BASIC_ACCOUNT_AMT_SIZE = BASIC_ENTITY_ID_SIZE + LONG_SIZE;
    public static final int BASIC_TX_ID_SIZE = BASIC_ENTITY_ID_SIZE + LONG_SIZE;
    public static final int EXCHANGE_RATE_SIZE = 2 * INT_SIZE + LONG_SIZE;
    public static final int CRYPTO_ALLOWANCE_SIZE = BASIC_ENTITY_ID_SIZE + INT_SIZE + LONG_SIZE; // owner, spender ,
    // amount
    public static final int TOKEN_ALLOWANCE_SIZE = BASIC_ENTITY_ID_SIZE + 2 * INT_SIZE + LONG_SIZE; // owner, tokenNum,
    // spender num, amount
    public static final int NFT_ALLOWANCE_SIZE = BASIC_ENTITY_ID_SIZE + 2 * INT_SIZE + BOOL_SIZE; // owner, tokenNum,
    // spender num, approvedForAll

    public static final int NFT_DELETE_ALLOWANCE_SIZE = 2 * BASIC_ENTITY_ID_SIZE; // owner, tokenID

    /** Fields included: status, exchangeRate. */
    public static final int BASIC_RECEIPT_SIZE = INT_SIZE + 2 * EXCHANGE_RATE_SIZE;
    /**
     * Fields included: transactionID, nodeAccountID, transactionFee, transactionValidDuration,
     * generateRecord.
     */
    public static final int BASIC_TX_BODY_SIZE =
            BASIC_ENTITY_ID_SIZE + BASIC_TX_ID_SIZE + LONG_SIZE + (LONG_SIZE) + BOOL_SIZE;

    public static final int STATE_PROOF_SIZE = 2000;
    public static final int BASE_FILEINFO_SIZE = BASIC_ENTITY_ID_SIZE + LONG_SIZE + (LONG_SIZE) + BOOL_SIZE;
    public static final int BASIC_ACCOUNT_SIZE = 8 * LONG_SIZE + BOOL_SIZE;
    /** Fields included: nodeTransactionPrecheckCode, responseType, cost. */
    public static final long BASIC_QUERY_RES_HEADER = 2L * INT_SIZE + LONG_SIZE;

    public static final long BASIC_QUERY_HEADER = 212L;
    public static final int BASIC_CONTRACT_CREATE_SIZE = BASIC_ENTITY_ID_SIZE + 6 * LONG_SIZE;
    public static final long BASIC_CONTRACT_INFO_SIZE = 2L * BASIC_ENTITY_ID_SIZE + SOLIDITY_ADDRESS + BASIC_TX_ID_SIZE;
    /**
     * Fields included in size: receipt (basic size), transactionHash, consensusTimestamp,
     * transactionID transactionFee.
     */
    public static final int BASIC_TX_RECORD_SIZE =
            BASIC_RECEIPT_SIZE + TX_HASH_SIZE + LONG_SIZE + BASIC_TX_ID_SIZE + LONG_SIZE;

    private FeeConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Calculates the Key size in bytes.
     *
     * @param key key
     * @return int representing account key storage size
     */
    public static int getAccountKeyStorageSize(final Key key) {
        if (key == null) {
            return 0;
        }
        if (Key.getDefaultInstance().equals(key)) {
            return 0;
        }

        int[] countKeyMetatData = {0, 0};
        countKeyMetatData = calculateKeysMetadata(key, countKeyMetatData);

        return countKeyMetatData[0] * KEY_SIZE + countKeyMetatData[1] * INT_SIZE;
    }

    private static int[] calculateKeysMetadata(final Key key, final int[] count) {
        int[] workingCount = count;
        if (key.hasKeyList()) {
            final List<Key> keyList = key.getKeyList().getKeysList();
            for (final Key value : keyList) {
                workingCount = calculateKeysMetadata(value, workingCount);
            }
        } else if (key.hasThresholdKey()) {
            final List<Key> keyList = key.getThresholdKey().getKeys().getKeysList();
            workingCount[1]++;
            for (final Key value : keyList) {
                workingCount = calculateKeysMetadata(value, workingCount);
            }
        } else {
            workingCount[0]++;
        }
        return workingCount;
    }

    /**
     * Convert tinyCents to tinybars.
     *
     * @param exchangeRate exchange rate
     * @param tinyCentsFee tiny cents fee
     * @return tinyHbars
     */
    public static long getTinybarsFromTinyCents(final ExchangeRate exchangeRate, final long tinyCentsFee) {
        return getAFromB(tinyCentsFee, exchangeRate.getHbarEquiv(), exchangeRate.getCentEquiv());
    }

    private static long getAFromB(final long bAmount, final int aEquiv, final int bEquiv) {
        final var aMultiplier = BigInteger.valueOf(aEquiv);
        final var bDivisor = BigInteger.valueOf(bEquiv);
        return BigInteger.valueOf(bAmount)
                .multiply(aMultiplier)
                .divide(bDivisor)
                .longValueExact();
    }

    /**
     * Get signature count.
     *
     * @param transaction transaction
     * @return int representing signature count
     */
    public static int getSignatureCount(final Transaction transaction) {
        try {
            return CommonUtils.extractSignatureMap(transaction).getSigPairCount();
        } catch (final InvalidProtocolBufferException ignored) {
            return 0;
        }
    }

    public static int getContractFunctionSize(final ContractFunctionResult contFuncResult) {
        int contResult = 0;

        if (contFuncResult.getContractCallResult() != null) {
            contResult = contFuncResult.getContractCallResult().size();
        }

        if (contFuncResult.getErrorMessage() != null) {
            contResult = contResult + contFuncResult.getErrorMessageBytes().size();
        }

        if (contFuncResult.getBloom() != null) {
            contResult = contResult + contFuncResult.getBloom().size();
        }
        contResult = contResult + LONG_SIZE + 2 * LONG_SIZE;

        return contResult;
    }
}
