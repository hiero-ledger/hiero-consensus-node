// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.builder;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCallLocalQuery;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ContractGetBytecodeQuery;
import com.hederahashgraph.api.proto.java.ContractGetInfoQuery;
import com.hederahashgraph.api.proto.java.ContractGetRecordsQuery;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoGetAccountBalanceQuery;
import com.hederahashgraph.api.proto.java.CryptoGetAccountRecordsQuery;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.CryptoGetLiveHashQuery;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileGetContentsQuery;
import com.hederahashgraph.api.proto.java.FileGetInfoQuery;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.GetBySolidityIDQuery;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.LiveHash;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TimestampSeconds;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionGetFastRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionGetReceiptQuery;
import com.hederahashgraph.api.proto.java.TransactionGetRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import java.time.Instant;
import java.util.List;

public final class RequestBuilder {
    private RequestBuilder() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static Transaction getCreateAccountBuilder(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp startTime,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            List<Key> keyList,
            long initBal,
            long sendRecordThreshold,
            long receiveRecordThreshold,
            boolean receiverSign,
            Duration autoRenew) {
        Key keys = Key.newBuilder()
                .setKeyList(KeyList.newBuilder().addAllKeys(keyList).build())
                .build();
        return getCreateAccountBuilder(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                keys,
                initBal,
                sendRecordThreshold,
                receiveRecordThreshold,
                receiverSign,
                autoRenew);
    }

    public static Transaction getCreateAccountBuilder(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp startTime,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            Key key,
            long initBal,
            long sendRecordThreshold,
            long receiveRecordThreshold,
            boolean receiverSign,
            Duration autoRenew) {
        CryptoCreateTransactionBody createAccount = CryptoCreateTransactionBody.newBuilder()
                .setKey(key)
                .setInitialBalance(initBal)
                .setProxyAccountID(getAccountIdBuild(0L, 0L, 0L))
                .setReceiveRecordThreshold(receiveRecordThreshold)
                .setSendRecordThreshold(sendRecordThreshold)
                .setReceiverSigRequired(receiverSign)
                .setAutoRenewPeriod(autoRenew)
                .build();

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo);
        body.setCryptoCreateAccount(createAccount);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static Transaction getAccountUpdateRequest(
            AccountID accountID,
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp startTime,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            Duration autoRenew) {

        CryptoUpdateTransactionBody cryptoUpdate = CryptoUpdateTransactionBody.newBuilder()
                .setAccountIDToUpdate(accountID)
                .setAutoRenewPeriod(autoRenew)
                .build();
        return getAccountUpdateRequest(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                cryptoUpdate);
    }

    /**
     * Generates a transaction with a CryptoUpdateTransactionBody object pre-built by caller.
     *
     * @param payerAccountNum payer account number
     * @param payerRealmNum payer realm number
     * @param payerShardNum payer shard number
     * @param nodeAccountNum node account number
     * @param nodeRealmNum node realm number
     * @param nodeShardNum node shard number
     * @param transactionFee transaction fee
     * @param startTime start time
     * @param transactionDuration transaction duration
     * @param generateRecord generate record boolean
     * @param memo memo
     * @param cryptoUpdate crypto update transaction body
     * @return transaction for account update
     */
    public static Transaction getAccountUpdateRequest(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp startTime,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            CryptoUpdateTransactionBody cryptoUpdate) {

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo);
        body.setCryptoUpdateAccount(cryptoUpdate);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    private static TransactionBody.Builder getTransactionBody(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo) {
        AccountID payerAccountID = getAccountIdBuild(payerAccountNum, payerRealmNum, payerShardNum);
        AccountID nodeAccountID = getAccountIdBuild(nodeAccountNum, nodeRealmNum, nodeShardNum);
        return getTxBodyBuilder(
                transactionFee, timestamp, transactionDuration, generateRecord, memo, payerAccountID, nodeAccountID);
    }

    public static TransactionBody.Builder getTxBodyBuilder(
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            AccountID payerAccountID,
            AccountID nodeAccountID) {
        TransactionID transactionID = getTransactionID(timestamp, payerAccountID);
        return TransactionBody.newBuilder()
                .setTransactionID(transactionID)
                .setNodeAccountID(nodeAccountID)
                .setTransactionFee(transactionFee)
                .setTransactionValidDuration(transactionDuration)
                .setGenerateRecord(generateRecord)
                .setMemo(memo);
    }

    public static AccountID getAccountIdBuild(Long accountNum, Long realmNum, Long shardNum) {
        return AccountID.newBuilder()
                .setAccountNum(accountNum)
                .setRealmNum(realmNum)
                .setShardNum(shardNum)
                .build();
    }

    public static AccountID getAccountIdBuild(ByteString alias, Long realmNum, Long shardNum) {
        return AccountID.newBuilder()
                .setAlias(alias)
                .setRealmNum(realmNum)
                .setShardNum(shardNum)
                .build();
    }

    public static FileID getFileIdBuild(Long accountNum, Long realmNum, Long shardNum) {
        return FileID.newBuilder()
                .setFileNum(accountNum)
                .setRealmNum(realmNum)
                .setShardNum(shardNum)
                .build();
    }

    public static ContractID getContractIdBuild(Long accountNum, Long realmNum, Long shardNum) {
        return ContractID.newBuilder()
                .setContractNum(accountNum)
                .setRealmNum(realmNum)
                .setShardNum(shardNum)
                .build();
    }

    public static TransactionID getTransactionID(Timestamp timestamp, AccountID payerAccountID) {
        return TransactionID.newBuilder()
                .setAccountID(payerAccountID)
                .setTransactionValidStart(timestamp)
                .build();
    }

    public static TransactionRecord.Builder getTransactionRecord(
            long txFee, String memo, TransactionID transactionID, Timestamp consensusTime, TransactionReceipt receipt) {
        return TransactionRecord.newBuilder()
                .setConsensusTimestamp(consensusTime)
                .setTransactionID(transactionID)
                .setMemo(memo)
                .setTransactionFee(txFee)
                .setReceipt(receipt);
    }

    public static Timestamp getTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setNanos(instant.getNano())
                .setSeconds(instant.getEpochSecond())
                .build();
    }

    public static Duration getDuration(long seconds) {
        return Duration.newBuilder().setSeconds(seconds).build();
    }

    public static Query getCryptoGetInfoQuery(AccountID accountID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setCryptoGetInfo(
                        CryptoGetInfoQuery.newBuilder().setAccountID(accountID).setHeader(queryHeader))
                .build();
    }

    public static Query getCryptoGetBalanceQuery(
            AccountID accountID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setCryptogetAccountBalance(CryptoGetAccountBalanceQuery.newBuilder()
                        .setAccountID(accountID)
                        .setHeader(queryHeader))
                .build();
    }

    public static Query getFileContentQuery(FileID fileID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setFileGetContents(
                        FileGetContentsQuery.newBuilder().setFileID(fileID).setHeader(queryHeader))
                .build();
    }

    public static Query getTransactionGetRecordQuery(
            TransactionID transactionID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setTransactionGetRecord(TransactionGetRecordQuery.newBuilder()
                        .setTransactionID(transactionID)
                        .setHeader(queryHeader))
                .build();
    }

    public static Query getAccountRecordsQuery(
            AccountID accountID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setCryptoGetAccountRecords(CryptoGetAccountRecordsQuery.newBuilder()
                        .setAccountID(accountID)
                        .setHeader(queryHeader))
                .build();
    }

    public static Query getAccountLiveHashQuery(
            AccountID accountID, byte[] hash, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setCryptoGetLiveHash(CryptoGetLiveHashQuery.newBuilder()
                        .setAccountID(accountID)
                        .setHash(ByteString.copyFrom(hash))
                        .setHeader(queryHeader))
                .build();
    }

    public static Query getContractRecordsQuery(
            ContractID contractID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setContractGetRecords(ContractGetRecordsQuery.newBuilder()
                        .setContractID(contractID)
                        .setHeader(queryHeader))
                .build();
    }

    /**
     * Builds a file create transaction.
     *
     * @param payerAccountNum payer account number
     * @param payerRealmNum payer realm number
     * @param payerShardNum payer shard number
     * @param nodeAccountNum node account number
     * @param nodeRealmNum node realm number
     * @param nodeShardNum node shard number
     * @param transactionFee transaction fee
     * @param timestamp timestamp
     * @param transactionDuration transaction duration
     * @param generateRecord generate record boolean
     * @param memo memo
     * @param fileData content of the file
     * @param fileExpirationTime expiration for the file
     * @param waclKeyList WACL keys
     * @return transaction for file create
     */
    public static Transaction getFileCreateBuilder(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            ByteString fileData,
            Timestamp fileExpirationTime,
            List<Key> waclKeyList) {
        FileCreateTransactionBody fileCreateTransactionBody = FileCreateTransactionBody.newBuilder()
                .setExpirationTime(fileExpirationTime)
                .setKeys(KeyList.newBuilder().addAllKeys(waclKeyList).build())
                .setContents(fileData)
                .build();

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo);
        body.setFileCreate(fileCreateTransactionBody);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    /**
     * Builds a file append transaction.
     *
     * @param payerAccountNum payer account number
     * @param payerRealmNum payer realm number
     * @param payerShardNum payer shard number
     * @param nodeAccountNum node account number
     * @param nodeRealmNum node realm number
     * @param nodeShardNum node shard number
     * @param transactionFee transaction fee
     * @param timestamp timestamp
     * @param transactionDuration transaction duration
     * @param generateRecord generate record boolean
     * @param memo memo
     * @param fileData file data to be appended
     * @param fileId bile ID or hash of the transaction that created the file
     * @return transaction for file append
     */
    public static Transaction getFileAppendBuilder(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            ByteString fileData,
            FileID fileId) {
        FileAppendTransactionBody.Builder builder =
                FileAppendTransactionBody.newBuilder().setContents(fileData);
        builder.setFileID(fileId);
        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo);
        body.setFileAppend(builder);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    /**
     * Builds a file update transaction.
     *
     * @param payerAccountNum payer account number
     * @param payerRealmNum payer realm number
     * @param payerShardNum payer shard number
     * @param nodeAccountNum node account number
     * @param nodeRealmNum node realm number
     * @param nodeShardNum node shard number
     * @param transactionFee transaction fee
     * @param timestamp timestamp
     * @param fileExpTime file expiration time
     * @param transactionDuration transaction duration
     * @param generateRecord generate record boolean
     * @param memo memo
     * @param data data
     * @param fid file ID
     * @param keys key list
     * @return transaction for file update
     */
    public static Transaction getFileUpdateBuilder(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Timestamp fileExpTime,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            ByteString data,
            FileID fid,
            KeyList keys) {
        FileUpdateTransactionBody.Builder builder = FileUpdateTransactionBody.newBuilder()
                .setContents(data)
                .setFileID(fid)
                .setExpirationTime(fileExpTime)
                .setKeys(keys);

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo);
        body.setFileUpdate(builder);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    /**
     * Builds a file deletion transaction.
     *
     * @param payerAccountNum payer account number
     * @param payerRealmNum payer realm number
     * @param payerShardNum payer shard number
     * @param nodeAccountNum node account number
     * @param nodeRealmNum node realm number
     * @param nodeShardNum node shard number
     * @param transactionFee transaction fee
     * @param timestamp timestamp
     * @param transactionDuration transaction duration
     * @param generateRecord generate record boolean
     * @param memo memo
     * @param fileID file ID
     * @return transaction for file deletion
     */
    public static Transaction getFileDeleteBuilder(
            final Long payerAccountNum,
            final Long payerRealmNum,
            final Long payerShardNum,
            final Long nodeAccountNum,
            final Long nodeRealmNum,
            final Long nodeShardNum,
            final long transactionFee,
            final Timestamp timestamp,
            final Duration transactionDuration,
            final boolean generateRecord,
            final String memo,
            final FileID fileID) {
        final var fileDeleteTransaction =
                FileDeleteTransactionBody.newBuilder().setFileID(fileID).build();
        final var body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo);
        body.setFileDelete(fileDeleteTransaction);
        final var bodyBytesArr = body.build().toByteArray();
        final var bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static Query getFileGetContentBuilder(Transaction payment, FileID fileID, ResponseType responseType) {

        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setPayment(payment)
                .setResponseType(responseType)
                .build();

        FileGetContentsQuery fileGetContentsQuery = FileGetContentsQuery.newBuilder()
                .setHeader(queryHeader)
                .setFileID(fileID)
                .build();

        return Query.newBuilder().setFileGetContents(fileGetContentsQuery).build();
    }

    /**
     * Get file get info builder.
     *
     * @param payment payment
     * @param fileID file ID
     * @param responseType response type
     * @return query
     */
    public static Query getFileGetInfoBuilder(Transaction payment, FileID fileID, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setPayment(payment)
                .setResponseType(responseType)
                .build();

        FileGetInfoQuery fileGetInfoQuery = FileGetInfoQuery.newBuilder()
                .setHeader(queryHeader)
                .setFileID(fileID)
                .build();

        return Query.newBuilder().setFileGetInfo(fileGetInfoQuery).build();
    }

    public static Timestamp getExpirationTime(Instant startTime, Duration autoRenewalTime) {
        Instant autoRenewPeriod = startTime.plusSeconds(autoRenewalTime.getSeconds());

        return getTimestamp(autoRenewPeriod);
    }

    public static Instant convertProtoTimeStamp(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static ResponseHeader getResponseHeader(
            ResponseCodeEnum code, long cost, ResponseType type, ByteString stateProof) {
        return ResponseHeader.newBuilder()
                .setNodeTransactionPrecheckCode(code)
                .setCost(cost)
                .setResponseType(type)
                .setStateProof(stateProof)
                .build();
    }

    public static Transaction getCreateContractRequest(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration txDuration,
            boolean generateRecord,
            String txMemo,
            long gas,
            FileID fileId,
            ByteString constructorParameters,
            long initialBalance,
            Duration autoRenewalPeriod,
            String contractMemo,
            Key adminKey) {

        ContractCreateTransactionBody.Builder contractCreateInstance = ContractCreateTransactionBody.newBuilder()
                .setGas(gas)
                .setProxyAccountID(getAccountIdBuild(0L, 0L, 0L))
                .setAutoRenewPeriod(autoRenewalPeriod);
        if (fileId != null && fileId.isInitialized()) {
            contractCreateInstance = contractCreateInstance.setFileID(fileId);
        }

        if (constructorParameters != null) {
            contractCreateInstance = contractCreateInstance.setConstructorParameters(constructorParameters);
        }
        if (initialBalance != 0) {
            contractCreateInstance = contractCreateInstance.setInitialBalance(initialBalance);
        }

        if (!Strings.isNullOrEmpty(contractMemo)) {
            contractCreateInstance = contractCreateInstance.setMemo(contractMemo);
        }

        if (adminKey != null) {
            contractCreateInstance = contractCreateInstance.setAdminKey(adminKey);
        }
        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                txDuration,
                generateRecord,
                txMemo);
        body.setContractCreateInstance(contractCreateInstance);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static Transaction getHbarCryptoTransferRequestToAlias(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            Long senderActNum,
            Long amountSend,
            ByteString receivingAlias,
            Long amountReceived) {

        AccountAmount a1 = AccountAmount.newBuilder()
                .setAccountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .setAmount(amountSend)
                .build();
        AccountAmount a2 = AccountAmount.newBuilder()
                .setAccountID(getAccountIdBuild(receivingAlias, 0L, 0L))
                .setAmount(amountReceived)
                .build();
        TransferList transferList = TransferList.newBuilder()
                .addAccountAmounts(a1)
                .addAccountAmounts(a2)
                .build();
        return getCryptoTransferRequest(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo,
                transferList);
    }

    public static Transaction getTokenTransferRequestToAlias(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            Long senderActNum,
            Long tokenNum,
            Long amountSend,
            ByteString receivingAlias,
            Long amountReceived) {

        AccountAmount a1 = AccountAmount.newBuilder()
                .setAccountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .setAmount(amountSend)
                .build();
        AccountAmount a2 = AccountAmount.newBuilder()
                .setAccountID(getAccountIdBuild(receivingAlias, 0L, 0L))
                .setAmount(amountReceived)
                .build();
        NftTransfer a3 = NftTransfer.newBuilder()
                .setReceiverAccountID(
                        AccountID.newBuilder().setAlias(receivingAlias).build())
                .setSenderAccountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .setSerialNumber(1)
                .build();
        TokenTransferList tokenTransferList = TokenTransferList.newBuilder()
                .setToken(TokenID.newBuilder().setTokenNum(tokenNum).build())
                .addTransfers(a1)
                .addTransfers(a2)
                .addNftTransfers(a3)
                .build();
        return getTokenTransferRequest(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo,
                tokenTransferList);
    }

    public static Transaction getCryptoTransferRequest(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            TransferList transferList) {
        CryptoTransferTransactionBody cryptoTransferTransaction = CryptoTransferTransactionBody.newBuilder()
                .setTransfers(transferList)
                .build();

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo);
        body.setCryptoTransfer(cryptoTransferTransaction);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static Transaction getCryptoTransferRequest(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            Long senderActNum,
            Long amountSend,
            Long receiverAcctNum,
            Long amountReceived) {

        AccountAmount a1 = AccountAmount.newBuilder()
                .setAccountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .setAmount(amountSend)
                .build();
        AccountAmount a2 = AccountAmount.newBuilder()
                .setAccountID(getAccountIdBuild(receiverAcctNum, 0L, 0L))
                .setAmount(amountReceived)
                .build();
        TransferList transferList = TransferList.newBuilder()
                .addAccountAmounts(a1)
                .addAccountAmounts(a2)
                .build();
        return getCryptoTransferRequest(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo,
                transferList);
    }

    public static Transaction getTokenTransferRequest(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            TokenTransferList tokenTransferList) {
        CryptoTransferTransactionBody cryptoTransferTransaction = CryptoTransferTransactionBody.newBuilder()
                .addTokenTransfers(tokenTransferList)
                .build();

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                transactionDuration,
                generateRecord,
                memo);
        body.setCryptoTransfer(cryptoTransferTransaction);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static TransactionGetReceiptQuery getTransactionGetReceiptQuery(
            TransactionID transactionID, ResponseType responseType) {
        QueryHeader queryHeader =
                QueryHeader.newBuilder().setResponseType(responseType).build();
        return TransactionGetReceiptQuery.newBuilder()
                .setHeader(queryHeader)
                .setTransactionID(transactionID)
                .build();
    }

    public static TransactionGetFastRecordQuery getFastTransactionRecordQuery(
            TransactionID transactionID, ResponseType responseType) {
        QueryHeader queryHeader =
                QueryHeader.newBuilder().setResponseType(responseType).build();
        return TransactionGetFastRecordQuery.newBuilder()
                .setHeader(queryHeader)
                .setTransactionID(transactionID)
                .build();
    }

    public static LiveHash getLiveHash(
            AccountID accountIdBuild, Duration transactionDuration, KeyList keyList, byte[] hash) {
        return LiveHash.newBuilder()
                .setAccountId(accountIdBuild)
                .setHash(ByteString.copyFrom(hash))
                .setDuration(transactionDuration)
                .setKeys(keyList)
                .build();
    }

    public static Transaction getContractCallRequest(
            Long payerAccountNum,
            Long payerRealmNum,
            Long payerShardNum,
            Long nodeAccountNum,
            Long nodeRealmNum,
            Long nodeShardNum,
            long transactionFee,
            Timestamp timestamp,
            Duration txDuration,
            long gas,
            ContractID contractId,
            ByteString functionData,
            long value) {
        ContractCallTransactionBody.Builder contractCall = ContractCallTransactionBody.newBuilder()
                .setContractID(contractId)
                .setGas(gas)
                .setFunctionParameters(functionData)
                .setAmount(value);

        TransactionBody.Builder body = getTransactionBody(
                payerAccountNum,
                payerRealmNum,
                payerShardNum,
                nodeAccountNum,
                nodeRealmNum,
                nodeShardNum,
                transactionFee,
                timestamp,
                txDuration,
                true,
                "");
        body.setContractCall(contractCall);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static Query getContractCallLocalQuery(
            ContractID contractId,
            long gas,
            ByteString functionData,
            long maxResultSize,
            Transaction transaction,
            ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setContractCallLocal(ContractCallLocalQuery.newBuilder()
                        .setContractID(contractId)
                        .setGas(gas)
                        .setFunctionParameters(functionData)
                        .setMaxResultSize(maxResultSize)
                        .setHeader(queryHeader))
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(
            AccountID accountID, ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .setAccountID(accountID)
                .setStatus(status)
                .setExchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .setStatus(status)
                .setExchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(
            FileID fileID, ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .setFileID(fileID)
                .setStatus(status)
                .setExchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(
            ContractID contractID, ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .setContractID(contractID)
                .setStatus(status)
                .setExchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(ResponseCodeEnum status) {
        return TransactionReceipt.newBuilder().setStatus(status).build();
    }

    public static Query getContractGetInfoQuery(
            ContractID contractId, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setContractGetInfo(ContractGetInfoQuery.newBuilder()
                        .setContractID(contractId)
                        .setHeader(queryHeader))
                .build();
    }

    public static Query getContractGetBytecodeQuery(
            ContractID contractId, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setContractGetBytecode(ContractGetBytecodeQuery.newBuilder()
                        .setContractID(contractId)
                        .setHeader(queryHeader))
                .build();
    }

    public static Transaction getContractUpdateRequest(
            AccountID payerAccount,
            AccountID nodeAccount,
            long transactionFee,
            Timestamp startTime,
            Duration transactionDuration,
            boolean generateRecord,
            String memo,
            ContractID contractId,
            Duration autoRenewPeriod,
            Key adminKey,
            AccountID proxyAccount,
            Timestamp expirationTime,
            String contractMemo) {

        ContractUpdateTransactionBody.Builder contractUpdateBld = ContractUpdateTransactionBody.newBuilder();

        contractUpdateBld = contractUpdateBld.setContractID(contractId);
        if (autoRenewPeriod != null && autoRenewPeriod.isInitialized()) {
            contractUpdateBld = contractUpdateBld.setAutoRenewPeriod(autoRenewPeriod);
        }

        if (adminKey != null) {
            contractUpdateBld = contractUpdateBld.setAdminKey(adminKey);
        }

        if (proxyAccount != null && proxyAccount.isInitialized()) {
            contractUpdateBld = contractUpdateBld.setProxyAccountID(proxyAccount);
        }

        if (expirationTime != null && expirationTime.isInitialized()) {
            contractUpdateBld = contractUpdateBld.setExpirationTime(expirationTime);
        }
        if (!Strings.isNullOrEmpty(contractMemo)) {
            contractUpdateBld = contractUpdateBld.setMemo(contractMemo);
        }

        TransactionBody.Builder body = getTransactionBody(
                payerAccount.getAccountNum(),
                payerAccount.getRealmNum(),
                payerAccount.getShardNum(),
                nodeAccount.getAccountNum(),
                nodeAccount.getRealmNum(),
                nodeAccount.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo);
        body.setContractUpdateInstance(contractUpdateBld);
        byte[] bodyBytesArr = body.build().toByteArray();
        ByteString bodyBytes = ByteString.copyFrom(bodyBytesArr);
        return getAsTransaction(bodyBytes);
    }

    public static Query getBySolidityIdQuery(
            final String solidityId, final Transaction transaction, final ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .setResponseType(responseType)
                .setPayment(transaction)
                .build();
        return Query.newBuilder()
                .setGetBySolidityID(GetBySolidityIDQuery.newBuilder()
                        .setSolidityID(solidityId)
                        .setHeader(queryHeader))
                .build();
    }

    public static ExchangeRate getExchangeRateBuilder(int hbarEquivalent, int centEquivalent, long expirationSeconds) {
        return ExchangeRate.newBuilder()
                .setHbarEquiv(hbarEquivalent)
                .setCentEquiv(centEquivalent)
                .setExpirationTime(TimestampSeconds.newBuilder()
                        .setSeconds(expirationSeconds)
                        .build())
                .build();
    }

    public static ExchangeRateSet getExchangeRateSetBuilder(
            int currentHbarEquivalent,
            int currentCentEquivalent,
            long currentExpirationSeconds,
            int nextHbarEquivalent,
            int nextCentEquivalent,
            long nextExpirationSeconds) {
        return ExchangeRateSet.newBuilder()
                .setCurrentRate(
                        getExchangeRateBuilder(currentHbarEquivalent, currentCentEquivalent, currentExpirationSeconds))
                .setNextRate(getExchangeRateBuilder(nextHbarEquivalent, nextCentEquivalent, nextExpirationSeconds))
                .build();
    }

    private static Transaction getAsTransaction(ByteString bodyBytes) {
        return Transaction.newBuilder()
                .setSignedTransactionBytes(SignedTransaction.newBuilder()
                        .setBodyBytes(bodyBytes)
                        .build()
                        .toByteString())
                .build();
    }
}
