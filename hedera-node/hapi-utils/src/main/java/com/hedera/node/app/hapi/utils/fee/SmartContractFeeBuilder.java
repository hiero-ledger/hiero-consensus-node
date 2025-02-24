// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.builder.RequestBuilder;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class includes methods for generating Fee Matrices and calculating Fee for Smart Contract
 * related Transactions and Query.
 */
@Singleton
public final class SmartContractFeeBuilder extends FeeBuilder {
    @Inject
    public SmartContractFeeBuilder() {
        /* No-op */
    }

    /**
     * Creates fee matrices for a contract create transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public FeeData getContractCreateTxFeeMatrices(TransactionBody txBody, SigValueObj sigValObj) {
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;

        // calculate BPT - Total Bytes in Transaction
        long txBodySize = 0;
        txBodySize = getCommonTransactionBodyBytes(txBody);
        bpt = txBodySize + getContractCreateTransactionBodySize(txBody) + sigValObj.getSignatureSize();

        // vpt - verifications per transactions
        vpt = sigValObj.getTotalSigCount();

        bpr = INT_SIZE;
        rbs = getBaseTransactionRecordSize(txBody) * (RECEIPT_STORAGE_TIME_SEC + THRESHOLD_STORAGE_TIME_SEC);
        long rbsNetwork = getDefaultRbhNetworkSize() + BASIC_ENTITY_ID_SIZE * (RECEIPT_STORAGE_TIME_SEC);

        FeeComponents feeMatricesForTx = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /** Calculates the total bytes in Contract Create Transaction body. */
    private long getContractCreateTransactionBodySize(TransactionBody txBody) {
        /*
         * FileID fileID - BASIC_ENTITY_ID_SIZE Key adminKey - calculated value int64 gas - LONG_SIZE uint64
         * initialBalance - LONG_SIZE AccountID proxyAccountID - BASIC_ENTITY_ID_SIZE bytes
         * constructorParameters - calculated value Duration autoRenewPeriod - (LONG_SIZE + INT_SIZE)
         * ShardID shardID - LONG_SIZE RealmID realmID - LONG_SIZE Key newRealmAdminKey - calculated
         * value string memo - calculated value
         *
         */
        ContractCreateTransactionBody contractCreate =
                txBody.contractCreateInstanceOrElse(ContractCreateTransactionBody.DEFAULT);
        int adminKeySize = 0;
        int proxyAcctID = 0;
        if (contractCreate.hasAdminKey()) {
            adminKeySize = getAccountKeyStorageSize(contractCreate.adminKey());
        }
        int newRealmAdminKeySize = 0;
        if (contractCreate.hasNewRealmAdminKey()) {
            newRealmAdminKeySize = getAccountKeyStorageSize(contractCreate.newRealmAdminKey());
        }

        long constructParamSize = 0;

        if (contractCreate.constructorParameters() != null) {
            constructParamSize = contractCreate.constructorParameters().length();
        }

        if (contractCreate.hasProxyAccountID()) {
            proxyAcctID = BASIC_ENTITY_ID_SIZE;
        }

        int memoSize = 0;
        if (contractCreate.memo() != null) {
            memoSize = contractCreate.memo().getBytes().length;
        }
        return BASIC_CONTRACT_CREATE_SIZE
                + adminKeySize
                + proxyAcctID
                + constructParamSize
                + newRealmAdminKeySize
                + memoSize;
    }

    /**
     * Createsfee matrices for a contract update transaction.
     *
     * @param txBody transaction body
     * @param contractExpiryTime contract expiration time
     * @param sigValObj signature value object
     * @return fee data
     */
    public FeeData getContractUpdateTxFeeMatrices(
            TransactionBody txBody, Timestamp contractExpiryTime, SigValueObj sigValObj) {
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;
        long txBodySize = 0;
        txBodySize = getCommonTransactionBodyBytes(txBody);

        // bpt - Bytes per Transaction
        bpt = txBodySize + getContractUpdateBodyTxSize(txBody) + sigValObj.getSignatureSize();

        // vpt - verifications per transactions
        vpt = sigValObj.getTotalSigCount();

        bpr = INT_SIZE;

        if (contractExpiryTime != null && contractExpiryTime.seconds() > 0) {
            sbs = getContractUpdateStorageBytesSec(txBody, contractExpiryTime);
        }

        long rbsNetwork = getDefaultRbhNetworkSize();

        rbs = getBaseTransactionRecordSize(txBody) * (RECEIPT_STORAGE_TIME_SEC + THRESHOLD_STORAGE_TIME_SEC);

        FeeComponents feeMatricesForTx = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * Creates fee matrices for a contract call transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public FeeData getContractCallTxFeeMatrices(TransactionBody txBody, SigValueObj sigValObj) {
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;
        long txBodySize = 0;
        txBodySize = getCommonTransactionBodyBytes(txBody);

        // bpt - Bytes per Transaction
        bpt = txBodySize + getContractCallBodyTxSize(txBody) + sigValObj.getSignatureSize();

        // vpt - verifications per transactions
        vpt = sigValObj.getTotalSigCount();

        bpr = INT_SIZE;

        rbs = getBaseTransactionRecordSize(txBody) * (RECEIPT_STORAGE_TIME_SEC + THRESHOLD_STORAGE_TIME_SEC);
        long rbsNetwork = getDefaultRbhNetworkSize();

        FeeComponents feeMatricesForTx = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * Creates fee matrices for a contract call local transaction.
     *
     * @param funcParamSize function parameter size
     * @param contractFuncResult contract function result
     * @param responseType response type
     * @return fee data
     */
    public FeeData getContractCallLocalFeeMatrices(
            int funcParamSize, ContractFunctionResult contractFuncResult, ResponseType responseType) {
        // get the Fee Matrices
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;

        /*
         * QueryHeader header Transaction - CryptoTransfer - (will be taken care in Transaction
         * processing) ResponseType - INT_SIZE ContractID contractID - BASIC_ENTITY_ID_SIZE int64 gas -
         * LONG_SIZE bytes functionParameters - calculated value int64 maxResultSize - LONG_SIZE
         */

        bpt = BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE + LONG_SIZE + funcParamSize + LONG_SIZE;
        /*
         *
         * Response header NodeTransactionPrecheckCode - 4 bytes ResponseType - 4 bytes
         * ContractFunctionResult ContractID contractID - BASIC_ENTITY_ID_SIZE bytes contractCallResult -
         * Calculated Value string errorMessage - Calculated value bytes bloom - Calculated value uint64
         * gasUsed - LONG_SIZE repeated ContractLoginfo ContractID contractID - BASIC_ENTITY_ID_SIZE bytes
         * bloom - Calculated Value repeated bytes - Calculated Value bytes data - Calculated Value
         *
         */

        long errorMessageSize = 0;
        long contractFuncResultSize = 0;
        if (contractFuncResult != null) {

            if (contractFuncResult.contractCallResult() != null) {
                contractFuncResultSize = contractFuncResult.contractCallResult().length();
            }
            if (contractFuncResult.errorMessage() != null) {
                errorMessageSize = contractFuncResult.errorMessage().length();
            }
        }

        bpr = BASIC_QUERY_RES_HEADER + getStateProofSize(responseType);

        sbpr = BASIC_ENTITY_ID_SIZE + errorMessageSize + LONG_SIZE + contractFuncResultSize;

        FeeComponents feeMatrices = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getQueryFeeDataMatrices(feeMatrices);
    }

    /** Calculates the total bytes in a Contract Update Transaction. */
    private int getContractUpdateBodyTxSize(TransactionBody txBody) {
        /*
         * ContractID contractID - BASIC_ENTITY_ID_SIZE Timestamp expirationTime - LONG_SIZE + INT_SIZE
         * AccountID proxyAccountID - BASIC_ENTITY_ID_SIZE Duration autoRenewPeriod - LONG_SIZE + INT_SIZE
         * FileID fileID - BASIC_ENTITY_ID_SIZE Key adminKey - calculated value string memo - calculated value
         */
        int contractUpdateBodySize = BASIC_ENTITY_ID_SIZE;

        ContractUpdateTransactionBody contractUpdateTxBody =
                txBody.contractUpdateInstanceOrElse(ContractUpdateTransactionBody.DEFAULT);

        if (contractUpdateTxBody.hasProxyAccountID()) {
            contractUpdateBodySize += BASIC_ENTITY_ID_SIZE;
        }

        if (contractUpdateTxBody.hasFileID()) {
            contractUpdateBodySize += BASIC_ENTITY_ID_SIZE;
        }

        if (contractUpdateTxBody.hasExpirationTime()) {
            contractUpdateBodySize += (LONG_SIZE);
        }

        if (contractUpdateTxBody.hasAutoRenewPeriod()) {
            contractUpdateBodySize += (LONG_SIZE);
        }

        if (contractUpdateTxBody.hasAdminKey()) {
            contractUpdateBodySize += getAccountKeyStorageSize(contractUpdateTxBody.adminKey());
        }

        if (contractUpdateTxBody.memo() != null) {
            contractUpdateBodySize += contractUpdateTxBody.memo().getBytes().length;
        }

        return contractUpdateBodySize;
    }

    /** Calculates the total bytes in a Contract Call body Transaction. */
    private long getContractCallBodyTxSize(TransactionBody txBody) {
        /*
         * ContractID contractID - BASIC_ENTITY_ID_SIZE int64 gas - LONG_SIZE int64 amount - LONG_SIZE bytes
         * functionParameters - calculated value
         *
         */
        long contractCallBodySize = BASIC_ACCOUNT_SIZE + LONG_SIZE;

        ContractCallTransactionBody contractCallTxBody = txBody.contractCallOrElse(ContractCallTransactionBody.DEFAULT);

        if (contractCallTxBody.functionParameters() != null) {
            contractCallBodySize += contractCallTxBody.functionParameters().length();
        }

        if (contractCallTxBody.amount() != 0) {
            contractCallBodySize += LONG_SIZE;
        }

        return contractCallBodySize;
    }

    /** Calculates the total bytes in a Contract Call body Transaction. */
    private long getEthereumTransactionBodyTxSize(TransactionBody txBody) {
        /*
         * AccountId contractID - BASIC_ENTITY_ID_SIZE int64 gas - LONG_SIZE int64 amount - LONG_SIZE bytes
         * EthereumTransaction - calculated value
         * nonce - LONG
         * FileId callData - BASIC_ENTITY_ID_SIZE int64 gas - LONG_SIZE int64 amount - LONG_SIZE bytes
         */
        EthereumTransactionBody ethereumTransactionBody =
                txBody.ethereumTransactionOrElse(EthereumTransactionBody.DEFAULT);

        return BASIC_ACCOUNT_SIZE * 2
                + LONG_SIZE
                + ethereumTransactionBody.ethereumData().length();
    }

    /**
     * Creates the fee matrices for a contract byte code query.
     *
     * @param byteCodeSize byte code size
     * @param responseType response type
     * @return fee data
     */
    public FeeData getContractByteCodeQueryFeeMatrices(int byteCodeSize, ResponseType responseType) {
        // get the Fee Matrices
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;

        /*
         * ContractGetBytecodeQuery QueryHeader Transaction - CryptoTransfer - (will be taken care in
         * Transaction processing) ResponseType - INT_SIZE ContractID - BASIC_ENTITY_ID_SIZE
         */

        bpt = calculateBpt();
        /*
         *
         * Response header NodeTransactionPrecheckCode - 4 bytes ResponseType - 4 bytes
         *
         * bytes bytecode - calculated value
         *
         */

        bpr = BASIC_QUERY_RES_HEADER + getStateProofSize(responseType);

        sbpr = byteCodeSize;

        FeeComponents feeMatrices = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getQueryFeeDataMatrices(feeMatrices);
    }

    private long getContractUpdateStorageBytesSec(TransactionBody txBody, Timestamp contractExpiryTime) {
        long storageSize = 0;
        ContractUpdateTransactionBody contractUpdateTxBody = txBody.contractUpdateInstance();
        if (contractUpdateTxBody.hasAdminKey()) {
            storageSize += getAccountKeyStorageSize(contractUpdateTxBody.adminKey());
        }
        if (contractUpdateTxBody.memo() != null) {
            storageSize += contractUpdateTxBody.memo().getBytes().length;
        }
        Instant expirationTime = RequestBuilder.convertProtoTimeStamp(contractExpiryTime);
        Timestamp txValidStartTimestamp =
                txBody.transactionIDOrElse(TransactionID.DEFAULT).transactionValidStartOrElse(Timestamp.DEFAULT);
        Instant txValidStartTime = RequestBuilder.convertProtoTimeStamp(txValidStartTimestamp);
        Duration duration = Duration.between(txValidStartTime, expirationTime);
        long seconds = Math.min(duration.getSeconds(), MAX_ENTITY_LIFETIME);
        storageSize = storageSize * seconds;
        return storageSize;
    }

    public FeeData getContractDeleteTxFeeMatrices(TransactionBody txBody, SigValueObj sigValObj) {
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;

        // calculate BPT - Total Bytes in Transaction
        long txBodySize = 0;
        txBodySize = getCommonTransactionBodyBytes(txBody);

        bpt = txBodySize + BASIC_ENTITY_ID_SIZE + BASIC_ENTITY_ID_SIZE + sigValObj.getSignatureSize();

        // vpt - verifications per transactions
        vpt = sigValObj.getTotalSigCount();

        bpr = INT_SIZE;

        rbs = calculateRbs(txBody);
        long rbsNetwork = getDefaultRbhNetworkSize() + BASIC_ENTITY_ID_SIZE * (RECEIPT_STORAGE_TIME_SEC);

        FeeComponents feeMatricesForTx = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }

    /**
     * Creates fee matrices for a contract call transaction.
     *
     * @param txBody transaction body
     * @param sigValObj signature value object
     * @return fee data
     */
    public FeeData getEthereumTransactionFeeMatrices(TransactionBody txBody, SigValueObj sigValObj) {
        long bpt = 0;
        long vpt = 0;
        long rbs = 0;
        long sbs = 0;
        long gas = 0;
        long tv = 0;
        long bpr = 0;
        long sbpr = 0;
        long txBodySize = 0;
        txBodySize = getCommonTransactionBodyBytes(txBody);

        // bpt - Bytes per Transaction
        bpt = txBodySize + getEthereumTransactionBodyTxSize(txBody) + sigValObj.getSignatureSize();

        // vpt - verifications per transactions, plus one for ECDSA public key recovery
        vpt = sigValObj.getTotalSigCount() + 1L;

        bpr = INT_SIZE;

        rbs = getBaseTransactionRecordSize(txBody) * (RECEIPT_STORAGE_TIME_SEC + THRESHOLD_STORAGE_TIME_SEC);
        long rbsNetwork = getDefaultRbhNetworkSize();

        FeeComponents feeMatricesForTx = FeeComponents.newBuilder()
                .bpt(bpt)
                .vpt(vpt)
                .rbh(rbs)
                .sbh(sbs)
                .gas(gas)
                .tv(tv)
                .bpr(bpr)
                .sbpr(sbpr)
                .build();

        return getFeeDataMatrices(feeMatricesForTx, sigValObj.getPayerAcctSigCount(), rbsNetwork);
    }
}
