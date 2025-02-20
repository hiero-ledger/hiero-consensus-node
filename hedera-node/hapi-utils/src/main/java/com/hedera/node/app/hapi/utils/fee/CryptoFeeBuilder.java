// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class includes methods for generating Fee Matrices and calculating Fee for Crypto related
 * Transactions and Query.
 */
@Singleton
public final class CryptoFeeBuilder extends FeeBuilder {
    @Inject
    public CryptoFeeBuilder() {
        /* No-op */
    }

    /**
     * Creates fee matrices for a crypto create transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public static FeeData getCryptoCreateTxFeeMatrices(final TransactionBody txBody, final SigValueObj sigValObj) {
        final var txBodySize = getCommonTransactionBodyBytes(txBody);
        final var cryptoCreate = txBody.getCryptoCreateAccount();
        final var cryptoCreateSize = getCryptoCreateAccountBodyTxSize(cryptoCreate);
        final long bpt = txBodySize + cryptoCreateSize + sigValObj.getSignatureSize();
        final long vpt = sigValObj.getTotalSigCount();
        final long rbs = getCryptoRbs(cryptoCreate, cryptoCreateSize) + calculateRbs(txBody); // TxRecord
        final var rbsNetwork = getDefaultRbhNetworkSize() + BASIC_ENTITY_ID_SIZE * (RECEIPT_STORAGE_TIME_SEC);

        final long bpr = INT_SIZE;

        final var feeMatricesForTx = FeeComponents.newBuilder()
                .setBpt(bpt)
                .setVpt(vpt)
                .setRbh(rbs)
                .setSbh(0L)
                .setGas(0L)
                .setTv(0L)
                .setBpr(bpr)
                .setSbpr(0L)
                .build();
        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * Creates fee matrices for a crypto delete transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public FeeData getCryptoDeleteTxFeeMatrices(final TransactionBody txBody, final SigValueObj sigValObj) {
        final long bpr = INT_SIZE;
        final var txBodySize = getCommonTransactionBodyBytes(txBody);
        final var bpt = txBodySize + 2 * BASIC_ENTITY_ID_SIZE + sigValObj.getSignatureSize();
        final long vpt = sigValObj.getTotalSigCount();

        final var rbs = calculateRbs(txBody);
        final var rbsNetwork = getDefaultRbhNetworkSize();
        final var feeMatricesForTx = FeeComponents.newBuilder()
                .setBpt(bpt)
                .setVpt(vpt)
                .setRbh(rbs)
                .setSbh(0L)
                .setGas(0L)
                .setTv(0L)
                .setBpr(bpr)
                .setSbpr(0L)
                .build();

        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * This method calculates total RAM Bytes (product of total bytes that will be stored in memory
     * and time till account expires).
     *
     * @param cryptoCreate the crypto create transaction body
     * @param crCreateSize the size of the crypto create transaction body
     * @return the total RAM Bytes for the given crypto create transaction body
     */
    private static long getCryptoRbs(final CryptoCreateTransactionBody cryptoCreate, final int crCreateSize) {
        final var seconds = cryptoCreate.hasAutoRenewPeriod()
                ? cryptoCreate.getAutoRenewPeriod().getSeconds()
                : 0L;
        return crCreateSize * seconds;
    }

    /**
     * Calculates the total bytes in a crypto create transaction body.
     *
     * @param cryptoCreate the crypto create transaction body
     * @return the total bytes in the given crypto create transaction body
     */
    private static int getCryptoCreateAccountBodyTxSize(final CryptoCreateTransactionBody cryptoCreate) {
        final var keySize = getAccountKeyStorageSize(cryptoCreate.getKey());
        final var newRealmAdminKeySize =
                cryptoCreate.hasNewRealmAdminKey() ? getAccountKeyStorageSize(cryptoCreate.getNewRealmAdminKey()) : 0;

        return keySize + BASIC_ACCOUNT_SIZE + newRealmAdminKeySize;
    }

    /**
     * Creates fee matrices for a transaction record cost query (for getting the cost of a transaction record
     * query).
     *
     * @return fee data
     */
    public static FeeData getCostTransactionRecordQueryFeeMatrices() {
        return FeeData.getDefaultInstance();
    }

    /**
     * Creates fee matrices for a transaction record query.
     *
     * @param transRecord transaction record
     * @param responseType response type
     * @return fee data
     */
    public FeeData getTransactionRecordQueryFeeMatrices(
            final TransactionRecord transRecord, final ResponseType responseType) {
        if (transRecord == null) {
            return FeeData.getDefaultInstance();
        }
        final var bpt = BASIC_QUERY_HEADER + BASIC_TX_ID_SIZE;
        final var txRecordSize = getAccountTransactionRecordSize(transRecord);
        final var bpr = BASIC_QUERY_RES_HEADER + txRecordSize + getStateProofSize(responseType);

        final var feeMatrices = FeeComponents.newBuilder()
                .setBpt(bpt)
                .setVpt(0L)
                .setRbh(0L)
                .setSbh(0L)
                .setGas(0L)
                .setTv(0L)
                .setBpr(bpr)
                .setSbpr(0L)
                .build();

        return getQueryFeeDataMatrices(feeMatrices);
    }

    /**
     * Creates fee matrices for an account records query.
     *
     * @param transRecords list of transaction records
     * @param responseType response type
     * @return fee data
     */
    public FeeData getCryptoAccountRecordsQueryFeeMatrices(
            final List<TransactionRecord> transRecords, final ResponseType responseType) {
        final var bpt = BASIC_QUERY_HEADER + BASIC_TX_ID_SIZE;

        int txRecordListsize = 0;
        if (transRecords != null) {
            for (final var transRecord : transRecords) {
                txRecordListsize += getAccountTransactionRecordSize(transRecord);
            }
        }

        final var bpr = BASIC_QUERY_RES_HEADER + txRecordListsize + getStateProofSize(responseType);

        final var feeMatrices = FeeComponents.newBuilder()
                .setBpt(bpt)
                .setVpt(0L)
                .setRbh(0L)
                .setSbh(0L)
                .setGas(0L)
                .setTv(0L)
                .setBpr(bpr)
                .setSbpr(0L)
                .build();

        return getQueryFeeDataMatrices(feeMatrices);
    }

    /**
     * Creates fee matrices for an account record cost query (for getting the cost of an account record query).
     *
     * @return fee data
     */
    public static FeeData getCostCryptoAccountRecordsQueryFeeMatrices() {
        return getCostForQueryByIdOnly();
    }

    /**
     * Creates fee matrices for an account info cost query (for getting the cost of an account info query).
     *
     * @return fee data
     */
    public static FeeData getCostCryptoAccountInfoQueryFeeMatrices() {
        return getCostForQueryByIdOnly();
    }

    private static int getAccountTransactionRecordSize(final TransactionRecord transRecord) {
        final var memoBytesSize = transRecord.getMemoBytes().size();

        final var accountAmountSize = transRecord.hasTransferList()
                ? transRecord.getTransferList().getAccountAmountsCount() * BASIC_ACCOUNT_AMT_SIZE
                : 0;

        return BASIC_TX_RECORD_SIZE + memoBytesSize + accountAmountSize;
    }
}
