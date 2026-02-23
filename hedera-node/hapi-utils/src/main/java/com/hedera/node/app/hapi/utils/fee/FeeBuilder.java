// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.math.BigInteger;
import java.util.List;

/**
 * This is the base class for building Fee Matrices and calculating the Total as well as specific
 * component Fee for a given Transaction or Query. It includes common methods which is used to
 * calculate Fee for Crypto, File and Smart Contracts Transactions and Query
 */
public class FeeBuilder {
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

    /**
     * Empty constructor for inheritance.
     */
    public FeeBuilder() {
        /* No-op */
    }

    /**
     * This method calculates Fee for specific component (Noe/Network/Service) based upon param
     * componentCoefficients and componentMetrics.
     *
     * @param componentCoefficients component coefficients
     * @param componentMetrics component metrics
     * @return long representation of the fee in tiny cents
     */
    public static long getComponentFeeInTinyCents(
            final FeeComponents componentCoefficients, final FeeComponents componentMetrics) {

        final long bytesUsageFee = componentCoefficients.getBpt() * componentMetrics.getBpt();
        final long verificationFee = componentCoefficients.getVpt() * componentMetrics.getVpt();
        final long ramStorageFee = componentCoefficients.getRbh() * componentMetrics.getRbh();
        final long storageFee = componentCoefficients.getSbh() * componentMetrics.getSbh();
        final long evmGasFee = componentCoefficients.getGas() * componentMetrics.getGas();
        final long txValueFee = componentCoefficients.getTv() * componentMetrics.getTv();
        final long bytesResponseFee = componentCoefficients.getBpr() * componentMetrics.getBpr();
        final long storageBytesResponseFee = componentCoefficients.getSbpr() * componentMetrics.getSbpr();
        final long componentUsage = componentCoefficients.getConstant() * componentMetrics.getConstant();

        long totalComponentFee = componentUsage
                + (bytesUsageFee
                        + verificationFee
                        + ramStorageFee
                        + storageFee
                        + evmGasFee
                        + txValueFee
                        + bytesResponseFee
                        + storageBytesResponseFee);

        if (totalComponentFee < componentCoefficients.getMin()) {
            totalComponentFee = componentCoefficients.getMin();
        } else if (totalComponentFee > componentCoefficients.getMax()) {
            totalComponentFee = componentCoefficients.getMax();
        }
        return Math.max(totalComponentFee > 0 ? 1 : 0, (totalComponentFee) / FEE_DIVISOR_FACTOR);
    }

    /**
     * This method calculates total fee for transaction or query and returns the value in tinyBars.
     *
     * @param feeCoefficients fee coefficients
     * @param componentMetrics component metrics
     * @param exchangeRate exchange rates
     * @return long representing the total fee request
     */
    public static long getTotalFeeforRequest(
            final FeeData feeCoefficients, final FeeData componentMetrics, final ExchangeRate exchangeRate) {

        final FeeObject feeObject = getFeeObject(feeCoefficients, componentMetrics, exchangeRate);
        return feeObject.serviceFee() + feeObject.nodeFee() + feeObject.networkFee();
    }

    public static FeeObject getFeeObject(
            final FeeData feeData, final FeeData feeMatrices, final ExchangeRate exchangeRate, final long multiplier) {
        // get the Network Fee
        long networkFee = getComponentFeeInTinyCents(feeData.getNetworkdata(), feeMatrices.getNetworkdata());
        long nodeFee = getComponentFeeInTinyCents(feeData.getNodedata(), feeMatrices.getNodedata());
        long serviceFee = getComponentFeeInTinyCents(feeData.getServicedata(), feeMatrices.getServicedata());
        // convert the Fee to tiny hbars
        networkFee = FeeBuilder.getTinybarsFromTinyCents(exchangeRate, networkFee) * multiplier;
        nodeFee = FeeBuilder.getTinybarsFromTinyCents(exchangeRate, nodeFee) * multiplier;
        serviceFee = FeeBuilder.getTinybarsFromTinyCents(exchangeRate, serviceFee) * multiplier;
        return new FeeObject(nodeFee, networkFee, serviceFee);
    }

    /**
     * Get fee object.
     *
     * @param feeData fee data
     * @param feeMatrices fee matrices
     * @param exchangeRate exchange rate
     * @return fee object
     */
    public static FeeObject getFeeObject(
            final FeeData feeData, final FeeData feeMatrices, final ExchangeRate exchangeRate) {
        return getFeeObject(feeData, feeMatrices, exchangeRate, 1L);
    }

    /**
     * This method calculates the common bytes included in a every transaction. Common bytes only
     * differ based upon memo field.
     *
     * <p>Common fields in all transaction:
     *
     * <ul>
     *   <li>TransactionID transactionID - BASIC_ENTITY_ID_SIZE (accountId) + LONG_SIZE
     *       (transactionValidStart)
     *   <li>AccountID nodeAccountID - BASIC_ENTITY_ID_SIZE
     *   <li>uint64 transactionFee - LONG_SIZE
     *   <li>Duration transactionValidDuration - (LONG_SIZE)
     *   <li>bool generateRecord - BOOL_SIZE
     *   <li>bytes string memo - get memo size from transaction
     * </ul>
     *
     * @param txBody transaction body
     * @return long representing transaction size
     */
    public static long getCommonTransactionBodyBytes(final TransactionBody txBody) {
        int memoSize = 0;
        if (txBody.getMemo() != null) {
            memoSize = txBody.getMemoBytes().size();
        }
        return (long) BASIC_TX_BODY_SIZE + memoSize;
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

    /**
     * This method calculates number of keys.
     *
     * @param key key
     * @param count count array
     * @return int array containing key metadata
     */
    public static int[] calculateKeysMetadata(final Key key, final int[] count) {
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
     * Retrieves the default fee matrices for querying based upon ID (account / file / smart
     * contract).
     *
     * @return fee data
     */
    public static FeeData getCostForQueryByIdOnly() {
        return FeeData.getDefaultInstance();
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

    /**
     * Get signature size.
     *
     * @param transaction transaction
     * @return int representing signature size
     */
    public static int getSignatureSize(final Transaction transaction) {
        try {
            return CommonUtils.extractSignatureMap(transaction).getSerializedSize();
        } catch (final InvalidProtocolBufferException ignored) {
            return 0;
        }
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

    public static FeeData getFeeDataMatrices(
            final FeeComponents feeComponents, final int payerVpt, final long rbsNetwork) {

        final long rbh = Math.max(feeComponents.getRbh() > 0 ? 1 : 0, feeComponents.getRbh() / HRS_DIVISOR);
        final long sbh = Math.max(feeComponents.getSbh() > 0 ? 1 : 0, feeComponents.getSbh() / HRS_DIVISOR);
        final long rbhNetwork = Math.max(rbsNetwork > 0 ? 1 : 0, (rbsNetwork) / HRS_DIVISOR);
        final FeeComponents feeMatricesForTxService = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setRbh(rbh)
                .setSbh(sbh)
                .setTv(feeComponents.getTv())
                .build();

        final FeeComponents feeMatricesForTxNetwork = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(feeComponents.getBpt())
                .setVpt(feeComponents.getVpt())
                .setRbh(rbhNetwork)
                .build();

        final FeeComponents feeMatricesForTxNode = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(feeComponents.getBpt())
                .setVpt(payerVpt)
                .setBpr(feeComponents.getBpr())
                .setSbpr(feeComponents.getSbpr())
                .build();

        return FeeData.newBuilder()
                .setNetworkdata(feeMatricesForTxNetwork)
                .setNodedata(feeMatricesForTxNode)
                .setServicedata(feeMatricesForTxService)
                .build();
    }

    public static FeeData getQueryFeeDataMatrices(final FeeComponents feeComponents) {

        final FeeComponents feeMatricesForTxService = FeeComponents.getDefaultInstance();

        final FeeComponents feeMatricesForTxNetwork = FeeComponents.getDefaultInstance();

        final FeeComponents feeMatricesForTxNode = FeeComponents.newBuilder()
                .setConstant(FEE_MATRICES_CONST)
                .setBpt(feeComponents.getBpt())
                .setBpr(feeComponents.getBpr())
                .setSbpr(feeComponents.getSbpr())
                .build();

        return FeeData.newBuilder()
                .setNetworkdata(feeMatricesForTxNetwork)
                .setNodedata(feeMatricesForTxNode)
                .setServicedata(feeMatricesForTxService)
                .build();
    }

    public static long getDefaultRbhNetworkSize() {
        return (BASIC_RECEIPT_SIZE) * (RECEIPT_STORAGE_TIME_SEC);
    }

    public static int getBaseTransactionRecordSize(final TransactionBody txBody) {
        int txRecordSize = BASIC_TX_RECORD_SIZE;
        if (txBody.getMemo() != null) {
            txRecordSize = txRecordSize + txBody.getMemoBytes().size();
        }
        // TransferList size
        if (txBody.hasCryptoTransfer()) {
            txRecordSize = txRecordSize
                    + txBody.getCryptoTransfer().getTransfers().getAccountAmountsCount() * (BASIC_ACCOUNT_AMT_SIZE);
        }
        return txRecordSize;
    }

    public static long getTxRecordUsageRbh(final TransactionRecord txRecord, final int timeInSeconds) {
        if (txRecord == null) {
            return 0;
        }
        final long txRecordSize = getTransactionRecordSize(txRecord);
        return (txRecordSize) * getHoursFromSec(timeInSeconds);
    }

    public static int getHoursFromSec(final int valueInSeconds) {
        return valueInSeconds == 0 ? 0 : Math.max(1, (valueInSeconds / HRS_DIVISOR));
    }

    public static int getTransactionRecordSize(final TransactionRecord txRecord) {

        if (txRecord == null) {
            return 0;
        }

        int txRecordSize = BASIC_TX_RECORD_SIZE;

        if (txRecord.hasContractCallResult()) {
            txRecordSize = txRecordSize + getContractFunctionSize(txRecord.getContractCallResult());
        } else if (txRecord.hasContractCreateResult()) {
            txRecordSize = txRecordSize + getContractFunctionSize(txRecord.getContractCreateResult());
        }
        if (txRecord.hasTransferList()) {
            txRecordSize =
                    txRecordSize + (txRecord.getTransferList().getAccountAmountsCount()) * (BASIC_ACCOUNT_AMT_SIZE);
        }

        int memoBytesSize = 0;
        if (txRecord.getMemo() != null) {
            memoBytesSize = txRecord.getMemoBytes().size();
        }

        return txRecordSize + memoBytesSize;
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

    public static int getStateProofSize(final ResponseType responseType) {
        return (responseType == ResponseType.ANSWER_STATE_PROOF || responseType == ResponseType.COST_ANSWER_STATE_PROOF)
                ? STATE_PROOF_SIZE
                : 0;
    }

    protected static long calculateRbs(final TransactionBody txBody) {
        return getBaseTransactionRecordSize(txBody) * RECEIPT_STORAGE_TIME_SEC;
    }

    protected static long calculateBpt() {
        return BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE;
    }
}
