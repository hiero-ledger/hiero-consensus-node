// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.builder;

import com.google.common.base.Strings;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.contract.ContractGetInfoQuery;
import com.hedera.hapi.node.contract.ContractGetRecordsQuery;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.contract.GetBySolidityIDQuery;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.FileGetContentsQuery;
import com.hedera.hapi.node.file.FileGetInfoQuery;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceQuery;
import com.hedera.hapi.node.token.CryptoGetAccountRecordsQuery;
import com.hedera.hapi.node.token.CryptoGetInfoQuery;
import com.hedera.hapi.node.token.CryptoGetLiveHashQuery;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.token.LiveHash;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionGetFastRecordQuery;
import com.hedera.hapi.node.transaction.TransactionGetReceiptQuery;
import com.hedera.hapi.node.transaction.TransactionGetRecordQuery;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
                .keyList(KeyList.newBuilder().keys(keyList).build())
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
                .key(key)
                .initialBalance(initBal)
                .proxyAccountID(getAccountIdBuild(0L, 0L, 0L))
                .receiveRecordThreshold(receiveRecordThreshold)
                .sendRecordThreshold(sendRecordThreshold)
                .receiverSigRequired(receiverSign)
                .autoRenewPeriod(autoRenew)
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
        body.cryptoCreateAccount(createAccount);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
                .accountIDToUpdate(accountID)
                .autoRenewPeriod(autoRenew)
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
        body.cryptoUpdateAccount(cryptoUpdate);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
                .transactionID(transactionID)
                .nodeAccountID(nodeAccountID)
                .transactionFee(transactionFee)
                .transactionValidDuration(transactionDuration)
                .generateRecord(generateRecord)
                .memo(memo);
    }

    public static AccountID getAccountIdBuild(Long accountNum, Long realmNum, Long shardNum) {
        return AccountID.newBuilder()
                .accountNum(accountNum)
                .realmNum(realmNum)
                .shardNum(shardNum)
                .build();
    }

    public static AccountID getAccountIdBuild(Bytes alias, Long realmNum, Long shardNum) {
        return AccountID.newBuilder()
                .alias(alias)
                .realmNum(realmNum)
                .shardNum(shardNum)
                .build();
    }

    public static FileID getFileIdBuild(Long accountNum, Long realmNum, Long shardNum) {
        return FileID.newBuilder()
                .fileNum(accountNum)
                .realmNum(realmNum)
                .shardNum(shardNum)
                .build();
    }

    public static ContractID getContractIdBuild(Long accountNum, Long realmNum, Long shardNum) {
        return ContractID.newBuilder()
                .contractNum(accountNum)
                .realmNum(realmNum)
                .shardNum(shardNum)
                .build();
    }

    public static TransactionID getTransactionID(Timestamp timestamp, AccountID payerAccountID) {
        return TransactionID.newBuilder()
                .accountID(payerAccountID)
                .transactionValidStart(timestamp)
                .build();
    }

    public static TransactionRecord.Builder getTransactionRecord(
            long txFee, String memo, TransactionID transactionID, Timestamp consensusTime, TransactionReceipt receipt) {
        return TransactionRecord.newBuilder()
                .consensusTimestamp(consensusTime)
                .transactionID(transactionID)
                .memo(memo)
                .transactionFee(txFee)
                .receipt(receipt);
    }

    public static Timestamp getTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .nanos(instant.getNano())
                .seconds(instant.getEpochSecond())
                .build();
    }

    public static Duration getDuration(long seconds) {
        return Duration.newBuilder().seconds(seconds).build();
    }

    public static Query getCryptoGetInfoQuery(AccountID accountID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .cryptoGetInfo(
                        CryptoGetInfoQuery.newBuilder().accountID(accountID).header(queryHeader))
                .build();
    }

    public static Query getCryptoGetBalanceQuery(
            AccountID accountID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .cryptogetAccountBalance(CryptoGetAccountBalanceQuery.newBuilder()
                        .accountID(accountID)
                        .header(queryHeader))
                .build();
    }

    public static Query getFileContentQuery(FileID fileID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .fileGetContents(
                        FileGetContentsQuery.newBuilder().fileID(fileID).header(queryHeader))
                .build();
    }

    public static Query getTransactionGetRecordQuery(
            TransactionID transactionID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .transactionGetRecord(TransactionGetRecordQuery.newBuilder()
                        .transactionID(transactionID)
                        .header(queryHeader))
                .build();
    }

    public static Query getAccountRecordsQuery(
            AccountID accountID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .cryptoGetAccountRecords(CryptoGetAccountRecordsQuery.newBuilder()
                        .accountID(accountID)
                        .header(queryHeader))
                .build();
    }

    public static Query getAccountLiveHashQuery(
            AccountID accountID, byte[] hash, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .cryptoGetLiveHash(CryptoGetLiveHashQuery.newBuilder()
                        .accountID(accountID)
                        .hash(Bytes.wrap(hash))
                        .header(queryHeader))
                .build();
    }

    public static Query getContractRecordsQuery(
            ContractID contractID, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .contractGetRecords(ContractGetRecordsQuery.newBuilder()
                        .contractID(contractID)
                        .header(queryHeader))
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
            Bytes fileData,
            Timestamp fileExpirationTime,
            List<Key> waclKeyList) {
        FileCreateTransactionBody fileCreateTransactionBody = FileCreateTransactionBody.newBuilder()
                .expirationTime(fileExpirationTime)
                .keys(KeyList.newBuilder().keys(waclKeyList).build())
                .contents(fileData)
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
        body.fileCreate(fileCreateTransactionBody);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
            Bytes fileData,
            FileID fileId) {
        FileAppendTransactionBody.Builder builder =
                FileAppendTransactionBody.newBuilder().contents(fileData);
        builder.fileID(fileId);
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
        body.fileAppend(builder);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
            Bytes data,
            FileID fid,
            KeyList keys) {
        FileUpdateTransactionBody.Builder builder = FileUpdateTransactionBody.newBuilder()
                .contents(data)
                .fileID(fid)
                .expirationTime(fileExpTime)
                .keys(keys);

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
        body.fileUpdate(builder);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
                FileDeleteTransactionBody.newBuilder().fileID(fileID).build();
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
        body.fileDelete(fileDeleteTransaction);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
        return getAsTransaction(bodyBytes);
    }

    public static Query getFileGetContentBuilder(Transaction payment, FileID fileID, ResponseType responseType) {

        QueryHeader queryHeader = QueryHeader.newBuilder()
                .payment(payment)
                .responseType(responseType)
                .build();

        FileGetContentsQuery fileGetContentsQuery = FileGetContentsQuery.newBuilder()
                .header(queryHeader)
                .fileID(fileID)
                .build();

        return Query.newBuilder().fileGetContents(fileGetContentsQuery).build();
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
                .payment(payment)
                .responseType(responseType)
                .build();

        FileGetInfoQuery fileGetInfoQuery =
                FileGetInfoQuery.newBuilder().header(queryHeader).fileID(fileID).build();

        return Query.newBuilder().fileGetInfo(fileGetInfoQuery).build();
    }

    public static Timestamp getExpirationTime(Instant startTime, Duration autoRenewalTime) {
        Instant autoRenewPeriod = startTime.plusSeconds(autoRenewalTime.seconds());

        return getTimestamp(autoRenewPeriod);
    }

    public static Instant convertProtoTimeStamp(com.hedera.hapi.node.base.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    public static ResponseHeader getResponseHeader(
            ResponseCodeEnum code, long cost, ResponseType type, Bytes stateProof) {
        return ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(code)
                .cost(cost)
                .responseType(type)
                .stateProof(stateProof)
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
            Bytes constructorParameters,
            long initialBalance,
            Duration autoRenewalPeriod,
            String contractMemo,
            Key adminKey) {

        ContractCreateTransactionBody.Builder contractCreateInstance = ContractCreateTransactionBody.newBuilder()
                .gas(gas)
                .proxyAccountID(getAccountIdBuild(0L, 0L, 0L))
                .autoRenewPeriod(autoRenewalPeriod);
        if (fileId != null) {
            contractCreateInstance = contractCreateInstance.fileID(fileId);
        }

        if (constructorParameters != null) {
            contractCreateInstance = contractCreateInstance.constructorParameters(constructorParameters);
        }
        if (initialBalance != 0) {
            contractCreateInstance = contractCreateInstance.initialBalance(initialBalance);
        }

        if (!Strings.isNullOrEmpty(contractMemo)) {
            contractCreateInstance = contractCreateInstance.memo(contractMemo);
        }

        if (adminKey != null) {
            contractCreateInstance = contractCreateInstance.adminKey(adminKey);
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
        body.contractCreateInstance(contractCreateInstance);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
            Bytes receivingAlias,
            Long amountReceived) {

        AccountAmount a1 = AccountAmount.newBuilder()
                .accountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .amount(amountSend)
                .build();
        AccountAmount a2 = AccountAmount.newBuilder()
                .accountID(getAccountIdBuild(receivingAlias, 0L, 0L))
                .amount(amountReceived)
                .build();
        TransferList transferList =
                TransferList.newBuilder().accountAmounts(a1, a2).build();
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
            Bytes receivingAlias,
            Long amountReceived) {

        AccountAmount a1 = AccountAmount.newBuilder()
                .accountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .amount(amountSend)
                .build();
        AccountAmount a2 = AccountAmount.newBuilder()
                .accountID(getAccountIdBuild(receivingAlias, 0L, 0L))
                .amount(amountReceived)
                .build();
        NftTransfer a3 = NftTransfer.newBuilder()
                .receiverAccountID(AccountID.newBuilder().alias(receivingAlias).build())
                .senderAccountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .serialNumber(1)
                .build();
        TokenTransferList tokenTransferList = TokenTransferList.newBuilder()
                .token(TokenID.newBuilder().tokenNum(tokenNum).build())
                .transfers(a1, a2)
                .nftTransfers(a3)
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
                .transfers(transferList)
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
        body.cryptoTransfer(cryptoTransferTransaction);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
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
                .accountID(getAccountIdBuild(senderActNum, 0L, 0L))
                .amount(amountSend)
                .build();
        AccountAmount a2 = AccountAmount.newBuilder()
                .accountID(getAccountIdBuild(receiverAcctNum, 0L, 0L))
                .amount(amountReceived)
                .build();
        TransferList transferList =
                TransferList.newBuilder().accountAmounts(a1, a2).build();
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
                .tokenTransfers(tokenTransferList)
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
        body.cryptoTransfer(cryptoTransferTransaction);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
        return getAsTransaction(bodyBytes);
    }

    public static TransactionGetReceiptQuery getTransactionGetReceiptQuery(
            TransactionID transactionID, ResponseType responseType) {
        QueryHeader queryHeader =
                QueryHeader.newBuilder().responseType(responseType).build();
        return TransactionGetReceiptQuery.newBuilder()
                .header(queryHeader)
                .transactionID(transactionID)
                .build();
    }

    public static TransactionGetFastRecordQuery getFastTransactionRecordQuery(
            TransactionID transactionID, ResponseType responseType) {
        QueryHeader queryHeader =
                QueryHeader.newBuilder().responseType(responseType).build();
        return TransactionGetFastRecordQuery.newBuilder()
                .header(queryHeader)
                .transactionID(transactionID)
                .build();
    }

    public static LiveHash getLiveHash(
            AccountID accountIdBuild, Duration transactionDuration, KeyList keyList, byte[] hash) {
        return LiveHash.newBuilder()
                .accountId(accountIdBuild)
                .hash(Bytes.wrap(hash))
                .duration(transactionDuration)
                .keys(keyList)
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
            Bytes functionData,
            long value) {
        ContractCallTransactionBody.Builder contractCall = ContractCallTransactionBody.newBuilder()
                .contractID(contractId)
                .gas(gas)
                .functionParameters(functionData)
                .amount(value);

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
        body.contractCall(contractCall);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
        return getAsTransaction(bodyBytes);
    }

    public static Query getContractCallLocalQuery(
            ContractID contractId,
            long gas,
            Bytes functionData,
            long maxResultSize,
            Transaction transaction,
            ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .contractCallLocal(ContractCallLocalQuery.newBuilder()
                        .contractID(contractId)
                        .gas(gas)
                        .functionParameters(functionData)
                        .maxResultSize(maxResultSize)
                        .header(queryHeader))
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(
            AccountID accountID, ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .accountID(accountID)
                .status(status)
                .exchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .status(status)
                .exchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(
            FileID fileID, ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .fileID(fileID)
                .status(status)
                .exchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(
            ContractID contractID, ResponseCodeEnum status, ExchangeRateSet exchangeRateSet) {
        return TransactionReceipt.newBuilder()
                .contractID(contractID)
                .status(status)
                .exchangeRate(exchangeRateSet)
                .build();
    }

    public static TransactionReceipt getTransactionReceipt(ResponseCodeEnum status) {
        return TransactionReceipt.newBuilder().status(status).build();
    }

    public static Query getContractGetInfoQuery(
            ContractID contractId, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .contractGetInfo(
                        ContractGetInfoQuery.newBuilder().contractID(contractId).header(queryHeader))
                .build();
    }

    public static Query getContractGetBytecodeQuery(
            ContractID contractId, Transaction transaction, ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .contractGetBytecode(ContractGetBytecodeQuery.newBuilder()
                        .contractID(contractId)
                        .header(queryHeader))
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

        contractUpdateBld = contractUpdateBld.contractID(contractId);
        if (autoRenewPeriod != null) {
            contractUpdateBld = contractUpdateBld.autoRenewPeriod(autoRenewPeriod);
        }

        if (adminKey != null) {
            contractUpdateBld = contractUpdateBld.adminKey(adminKey);
        }

        if (proxyAccount != null) {
            contractUpdateBld = contractUpdateBld.proxyAccountID(proxyAccount);
        }

        if (expirationTime != null) {
            contractUpdateBld = contractUpdateBld.expirationTime(expirationTime);
        }
        if (!Strings.isNullOrEmpty(contractMemo)) {
            contractUpdateBld = contractUpdateBld.memo(contractMemo);
        }

        TransactionBody.Builder body = getTransactionBody(
                payerAccount.accountNum(),
                payerAccount.realmNum(),
                payerAccount.shardNum(),
                nodeAccount.accountNum(),
                nodeAccount.realmNum(),
                nodeAccount.shardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo);
        body.contractUpdateInstance(contractUpdateBld);
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(body.build());
        return getAsTransaction(bodyBytes);
    }

    public static Query getBySolidityIdQuery(
            final String solidityId, final Transaction transaction, final ResponseType responseType) {
        QueryHeader queryHeader = QueryHeader.newBuilder()
                .responseType(responseType)
                .payment(transaction)
                .build();
        return Query.newBuilder()
                .getBySolidityID(
                        GetBySolidityIDQuery.newBuilder().solidityID(solidityId).header(queryHeader))
                .build();
    }

    public static ExchangeRate getExchangeRateBuilder(int hbarEquivalent, int centEquivalent, long expirationSeconds) {
        return ExchangeRate.newBuilder()
                .hbarEquiv(hbarEquivalent)
                .centEquiv(centEquivalent)
                .expirationTime(
                        TimestampSeconds.newBuilder().seconds(expirationSeconds).build())
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
                .currentRate(
                        getExchangeRateBuilder(currentHbarEquivalent, currentCentEquivalent, currentExpirationSeconds))
                .nextRate(getExchangeRateBuilder(nextHbarEquivalent, nextCentEquivalent, nextExpirationSeconds))
                .build();
    }

    private static Transaction getAsTransaction(Bytes bodyBytes) {
        return Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(
                        SignedTransaction.newBuilder().bodyBytes(bodyBytes).build()))
                .build();
    }
}
