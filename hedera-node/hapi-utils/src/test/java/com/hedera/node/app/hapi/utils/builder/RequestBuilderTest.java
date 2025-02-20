// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestBuilderTest {
    private final long transactionFee = 1234L;
    private final long gas = 1234L;
    private final ByteString transactionBody = ByteString.copyFromUtf8("0x00120");
    private final ResponseType responseType = ResponseType.ANSWER_STATE_PROOF;
    private final ByteString hash =
            ByteString.copyFromUtf8("952e79e36a5fe25bd015c3a2ce85f318690c2c1fcb834c89ef06a84ba4d179c0");
    private final Key validED25519Key = Key.newBuilder()
            .setEd25519(
                    ByteString.copyFromUtf8(
                            "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771"))
            .build();
    private final KeyList keyList =
            KeyList.newBuilder().addKeys(validED25519Key).build();
    private final Timestamp startTime = Timestamp.newBuilder().setSeconds(1234L).build();
    private final Duration transactionDuration =
            Duration.newBuilder().setSeconds(30L).build();
    private final Duration autoRenew = Duration.newBuilder().setSeconds(30L).build();
    private final boolean generateRecord = false;
    private final String memo = "memo";
    private final String contractMemo = "contractMemo";

    private final AccountID accountId = AccountID.newBuilder()
            .setShardNum(0)
            .setRealmNum(0)
            .setAccountNum(1002)
            .build();

    private final FileID fileID =
            FileID.newBuilder().setShardNum(0).setRealmNum(0).setFileNum(6667).build();

    private final ContractID contractId = ContractID.newBuilder()
            .setShardNum(0)
            .setRealmNum(0)
            .setContractNum(3337)
            .build();

    private final AccountID nodeId = AccountID.newBuilder()
            .setShardNum(0)
            .setRealmNum(0)
            .setAccountNum(3)
            .build();

    private final TransactionID transactionId =
            TransactionID.newBuilder().setAccountID(accountId).build();

    private final ResponseCodeEnum responseCodeEnum = ResponseCodeEnum.SUCCESS;
    private final ExchangeRate exchangeRate =
            ExchangeRate.newBuilder().setHbarEquiv(1000).setCentEquiv(100).build();
    private final ExchangeRateSet exchangeRateSet =
            ExchangeRateSet.newBuilder().setCurrentRate(exchangeRate).build();
    private final Transaction transaction =
            Transaction.newBuilder().setBodyBytes(transactionBody).build();

    @Test
    void testExpirationTime() {
        final var now = Instant.now();

        final var expirationTime = RequestBuilder.getExpirationTime(now, transactionDuration);
        assertNotNull(expirationTime);

        final var expirationInstant = RequestBuilder.convertProtoTimeStamp(expirationTime);
        final var between = java.time.Duration.between(now, expirationInstant);
        assertEquals(transactionDuration.getSeconds(), between.getSeconds());
    }

    @Test
    void testGetFileDeleteBuilder() throws InvalidProtocolBufferException {
        final var payerAccountNum = 1L;
        final var realmNum = 0L;
        final var shardNum = 0L;
        final var nodeAccountNum = 2L;
        final var fileNo = 3L;
        final var transactionFee = 100L;
        final var timestamp =
                Timestamp.newBuilder().setSeconds(500L).setNanos(500).build();
        final var duration = RequestBuilder.getDuration(500L);
        final var generateRecord = false;
        final var fileId = FileID.newBuilder()
                .setFileNum(fileNo)
                .setRealmNum(realmNum)
                .setShardNum(shardNum)
                .build();

        final var transaction = RequestBuilder.getFileDeleteBuilder(
                payerAccountNum,
                realmNum,
                shardNum,
                nodeAccountNum,
                realmNum,
                shardNum,
                transactionFee,
                timestamp,
                duration,
                generateRecord,
                memo,
                fileId);
        final var transactionBody = buildSignedTransactionBody(transaction);
        assertEquals(fileId, transactionBody.getFileDelete().getFileID());
        assertEquals(
                payerAccountNum,
                transactionBody.getTransactionID().getAccountID().getAccountNum());
        assertEquals(timestamp, transactionBody.getTransactionID().getTransactionValidStart());
        assertEquals(realmNum, transactionBody.getTransactionID().getAccountID().getRealmNum());
        assertEquals(shardNum, transactionBody.getTransactionID().getAccountID().getShardNum());
        assertEquals(nodeAccountNum, transactionBody.getNodeAccountID().getAccountNum());
        assertEquals(duration, transactionBody.getTransactionValidDuration());
        assertEquals(generateRecord, transactionBody.getGenerateRecord());
        assertEquals(memo, transactionBody.getMemo());
    }

    @Test
    void assertGetCryptoGetInfoQuery() {
        var infoQuery = RequestBuilder.getCryptoGetInfoQuery(accountId, transaction, responseType);

        assertEquals(accountId, infoQuery.getCryptoGetInfo().getAccountID());
        assertEquals(responseType, infoQuery.getCryptoGetInfo().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getCryptoGetInfo().getHeader().getPayment());
    }

    @Test
    void assertGetCryptoBalanceQuery() {
        var infoQuery = RequestBuilder.getCryptoGetBalanceQuery(accountId, transaction, responseType);

        assertEquals(accountId, infoQuery.getCryptogetAccountBalance().getAccountID());
        assertEquals(
                responseType, infoQuery.getCryptogetAccountBalance().getHeader().getResponseType());
        assertEquals(
                transaction, infoQuery.getCryptogetAccountBalance().getHeader().getPayment());
    }

    @Test
    void assertGetFileContentQuery() {
        var infoQuery = RequestBuilder.getFileContentQuery(fileID, transaction, responseType);

        assertEquals(fileID, infoQuery.getFileGetContents().getFileID());
        assertEquals(responseType, infoQuery.getFileGetContents().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getFileGetContents().getHeader().getPayment());
    }

    @Test
    void assertGetFileGetContentBuilder() {
        var infoQuery = RequestBuilder.getFileGetContentBuilder(transaction, fileID, responseType);

        assertEquals(fileID, infoQuery.getFileGetContents().getFileID());
        assertEquals(responseType, infoQuery.getFileGetContents().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getFileGetContents().getHeader().getPayment());
    }

    @Test
    void assertGetTransactionGetRecordQuery() {
        var infoQuery = RequestBuilder.getTransactionGetRecordQuery(transactionId, transaction, responseType);

        assertEquals(transactionId, infoQuery.getTransactionGetRecord().getTransactionID());
        assertEquals(
                responseType, infoQuery.getTransactionGetRecord().getHeader().getResponseType());
        assertEquals(
                transaction, infoQuery.getTransactionGetRecord().getHeader().getPayment());
    }

    @Test
    void assertGetAccountRecordsQuery() {
        var infoQuery = RequestBuilder.getAccountRecordsQuery(accountId, transaction, responseType);

        assertEquals(accountId, infoQuery.getCryptoGetAccountRecords().getAccountID());
        assertEquals(
                responseType, infoQuery.getCryptoGetAccountRecords().getHeader().getResponseType());
        assertEquals(
                transaction, infoQuery.getCryptoGetAccountRecords().getHeader().getPayment());
    }

    @Test
    void assertGetFileGetInfoBuilder() {
        var infoQuery = RequestBuilder.getFileGetInfoBuilder(transaction, fileID, responseType);

        assertEquals(fileID, infoQuery.getFileGetInfo().getFileID());
        assertEquals(responseType, infoQuery.getFileGetInfo().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getFileGetInfo().getHeader().getPayment());
    }

    @Test
    void assertGetContractCallLocalQuery() {
        var maxResultSize = 123_456L;
        var functionResult = ByteString.copyFromUtf8("functionResult");
        var infoQuery = RequestBuilder.getContractCallLocalQuery(
                contractId, gas, functionResult, maxResultSize, transaction, responseType);

        assertEquals(contractId, infoQuery.getContractCallLocal().getContractID());
        assertEquals(gas, infoQuery.getContractCallLocal().getGas());
        assertEquals(maxResultSize, infoQuery.getContractCallLocal().getMaxResultSize());
        assertEquals(responseType, infoQuery.getContractCallLocal().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getContractCallLocal().getHeader().getPayment());
    }

    @Test
    void assertGetTransactionReceipt() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(accountId, responseCodeEnum, exchangeRateSet);

        assertEquals(accountId, transactionReceipt.getAccountID());
        assertEquals(exchangeRateSet, transactionReceipt.getExchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.getStatus());
    }

    @Test
    void assertGetTransactionReceiptByResponseCodeAndExchangeRate() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(responseCodeEnum, exchangeRateSet);

        assertEquals(exchangeRateSet, transactionReceipt.getExchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.getStatus());
    }

    @Test
    void assertGetTransactionReceiptByFileId() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(fileID, responseCodeEnum, exchangeRateSet);

        assertEquals(fileID, transactionReceipt.getFileID());
        assertEquals(exchangeRateSet, transactionReceipt.getExchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.getStatus());
    }

    @Test
    void assertGetTransactionReceiptByContractId() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(contractId, responseCodeEnum, exchangeRateSet);

        assertEquals(contractId, transactionReceipt.getContractID());
        assertEquals(exchangeRateSet, transactionReceipt.getExchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.getStatus());
    }

    @Test
    void assertGetTransactionReceiptByResponseCodeEnum() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(responseCodeEnum);
        assertEquals(responseCodeEnum, transactionReceipt.getStatus());
    }

    @Test
    void assertGetBySolidityIDQuery() {
        var solidityId = "solidityId";
        var infoQuery = RequestBuilder.getBySolidityIdQuery(solidityId, transaction, responseType);

        assertEquals(solidityId, infoQuery.getGetBySolidityID().getSolidityID());
        assertEquals(responseType, infoQuery.getGetBySolidityID().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getGetBySolidityID().getHeader().getPayment());
    }

    @Test
    void assertGetAccountLiveHashQuery() {
        var infoQuery =
                RequestBuilder.getAccountLiveHashQuery(accountId, hash.toByteArray(), transaction, responseType);

        assertEquals(accountId, infoQuery.getCryptoGetLiveHash().getAccountID());
        assertEquals(hash, infoQuery.getCryptoGetLiveHash().getHash());
        assertEquals(responseType, infoQuery.getCryptoGetLiveHash().getHeader().getResponseType());
        assertEquals(transaction, infoQuery.getCryptoGetLiveHash().getHeader().getPayment());
    }

    @Test
    void assertGetContractGetInfoQuery() {
        var contractGetInfoQuery = RequestBuilder.getContractGetInfoQuery(contractId, transaction, responseType);

        assertEquals(contractId, contractGetInfoQuery.getContractGetInfo().getContractID());
        assertEquals(
                responseType,
                contractGetInfoQuery.getContractGetInfo().getHeader().getResponseType());
        assertEquals(
                transaction,
                contractGetInfoQuery.getContractGetInfo().getHeader().getPayment());
    }

    @Test
    void assertGetContractGetBytecodeQuery() {
        var contractGetInfoQuery = RequestBuilder.getContractGetBytecodeQuery(contractId, transaction, responseType);

        assertEquals(contractId, contractGetInfoQuery.getContractGetBytecode().getContractID());
        assertEquals(
                responseType,
                contractGetInfoQuery.getContractGetBytecode().getHeader().getResponseType());
        assertEquals(
                transaction,
                contractGetInfoQuery.getContractGetBytecode().getHeader().getPayment());
    }

    @Test
    void assertGetContractRecordsQuery() {
        var contractGetInfoQuery = RequestBuilder.getContractRecordsQuery(contractId, transaction, responseType);

        assertEquals(contractId, contractGetInfoQuery.getContractGetRecords().getContractID());
        assertEquals(
                responseType,
                contractGetInfoQuery.getContractGetRecords().getHeader().getResponseType());
        assertEquals(
                transaction,
                contractGetInfoQuery.getContractGetRecords().getHeader().getPayment());
    }

    @Test
    void assertGetTransactionGetReceiptQuery() {
        var transactionGetReceiptQuery = RequestBuilder.getTransactionGetReceiptQuery(transactionId, responseType);

        assertEquals(responseType, transactionGetReceiptQuery.getHeader().getResponseType());
        assertEquals(transactionId, transactionGetReceiptQuery.getTransactionID());
    }

    @Test
    void assertGetFastTransactionRecordQuery() {
        var fastRecordQuery = RequestBuilder.getFastTransactionRecordQuery(transactionId, responseType);

        assertEquals(responseType, fastRecordQuery.getHeader().getResponseType());
        assertEquals(transactionId, fastRecordQuery.getTransactionID());
    }

    @Test
    void assertGetLiveHash() {
        var liveHash = RequestBuilder.getLiveHash(accountId, transactionDuration, keyList, hash.toByteArray());

        assertEquals(hash, liveHash.getHash());
        assertEquals(transactionDuration, liveHash.getDuration());
        assertEquals(accountId, liveHash.getAccountId());
        assertEquals(keyList, liveHash.getKeys());
    }

    @Test
    void assertGetResponseHeader() {
        var cost = 1234L;
        var responseHeader = RequestBuilder.getResponseHeader(responseCodeEnum, cost, responseType, hash);

        assertEquals(responseType, responseHeader.getResponseType());
        assertEquals(cost, responseHeader.getCost());
        assertEquals(hash, responseHeader.getStateProof());
        assertEquals(responseCodeEnum, responseHeader.getNodeTransactionPrecheckCode());
    }

    @Test
    void assertGetTransactionRecord() {
        var consensusTime = Instant.now();
        var transactionReceipt =
                TransactionReceipt.newBuilder().setAccountID(accountId).build();
        var transactionRecord =
                RequestBuilder.getTransactionRecord(transactionFee, memo, transactionId, startTime, transactionReceipt);

        assertEquals(transactionFee, transactionRecord.getTransactionFee());
        assertEquals(memo, transactionRecord.getMemo());
        assertEquals(transactionId, transactionRecord.getTransactionID());
        assertEquals(startTime, transactionRecord.getConsensusTimestamp());
        assertEquals(transactionReceipt, transactionRecord.getReceipt());
    }

    @Test
    void assertGetFileIdBuild() {
        var fileId = RequestBuilder.getFileIdBuild(fileID.getFileNum(), fileID.getRealmNum(), fileID.getShardNum());
        assertEquals(fileID.getShardNum(), fileId.getShardNum());
        assertEquals(fileID.getRealmNum(), fileId.getRealmNum());
        assertEquals(fileID.getFileNum(), fileId.getFileNum());
    }

    @Test
    void assertGetContractIdBuild() {
        var contractID = RequestBuilder.getContractIdBuild(
                contractId.getContractNum(), contractId.getRealmNum(), contractId.getShardNum());
        assertEquals(contractId.getShardNum(), contractID.getShardNum());
        assertEquals(contractId.getRealmNum(), contractID.getRealmNum());
        assertEquals(contractId.getContractNum(), contractID.getContractNum());
    }

    @Test
    void assertGetExchangeRateBuilder() {
        var hbarEquivalent = 1000;
        var centEquivalent = 100;
        var expirationSeconds = 1234L;
        var exchangeRate = RequestBuilder.getExchangeRateBuilder(hbarEquivalent, centEquivalent, expirationSeconds);
        assertEquals(hbarEquivalent, exchangeRate.getHbarEquiv());
        assertEquals(centEquivalent, exchangeRate.getCentEquiv());
        assertEquals(expirationSeconds, exchangeRate.getExpirationTime().getSeconds());
    }

    @Test
    void assertGetExchangeRateSetBuilder() {
        var currentHbarEquivalent = 1000;
        var nextHbarEquivalent = 1000;
        var currentCentEquivalent = 100;
        var nextCentEquivalent = 100;
        var currentExpirationSeconds = 1234L;
        var nextExpirationSeconds = 123_456L;

        var exchangeRateSet = RequestBuilder.getExchangeRateSetBuilder(
                currentHbarEquivalent,
                currentCentEquivalent,
                currentExpirationSeconds,
                nextHbarEquivalent,
                nextCentEquivalent,
                nextExpirationSeconds);

        assertEquals(currentHbarEquivalent, exchangeRateSet.getCurrentRate().getHbarEquiv());
        assertEquals(currentCentEquivalent, exchangeRateSet.getCurrentRate().getCentEquiv());
        assertEquals(
                currentExpirationSeconds,
                exchangeRateSet.getCurrentRate().getExpirationTime().getSeconds());
        assertEquals(nextHbarEquivalent, exchangeRateSet.getNextRate().getHbarEquiv());
        assertEquals(nextCentEquivalent, exchangeRateSet.getNextRate().getCentEquiv());
        assertEquals(
                nextExpirationSeconds,
                exchangeRateSet.getNextRate().getExpirationTime().getSeconds());
    }

    @Test
    void assertGetCreateAccountBuilder() throws InvalidProtocolBufferException {
        int thresholdValue = 3;
        List<Key> keyList = List.of(validED25519Key);
        long initBal = 300_000L;
        long sendRecordThreshold = 5L;
        long receiveRecordThreshold = 5L;
        boolean receiverSign = true;

        var transaction = RequestBuilder.getCreateAccountBuilder(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                keyList,
                initBal,
                sendRecordThreshold,
                receiveRecordThreshold,
                receiverSign,
                autoRenew);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.getMemo());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
    }

    @Test
    void assertGetTxBodyBuilder() {
        var transactionBody = RequestBuilder.getTxBodyBuilder(
                transactionFee, startTime, transactionDuration, generateRecord, memo, accountId, nodeId);

        assertEquals(memo, transactionBody.getMemo());
        assertEquals(transactionFee, transactionBody.getTransactionFee());
        assertEquals(startTime, transactionBody.getTransactionID().getTransactionValidStart());
        assertEquals(accountId, transactionBody.getTransactionID().getAccountID());
        assertEquals(generateRecord, transactionBody.getGenerateRecord());
        assertEquals(transactionDuration, transactionBody.getTransactionValidDuration());
        assertEquals(nodeId, transactionBody.getNodeAccountID());
    }

    @Test
    void assertGetTransactionBody() throws InvalidProtocolBufferException {
        var accId = AccountID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setAccountNum(1005)
                .build();

        var transaction = RequestBuilder.getAccountUpdateRequest(
                accId,
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                autoRenew);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.getMemo());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
    }

    @Test
    void assertGetFileCreateBuilder() throws InvalidProtocolBufferException {
        Timestamp fileExpiration = Timestamp.newBuilder().setSeconds(123_456L).build();
        List<Key> keyList = List.of(validED25519Key);
        var transaction = RequestBuilder.getFileCreateBuilder(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                hash,
                fileExpiration,
                keyList);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.getMemo());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(hash, tb.getFileCreate().getContents());
        assertEquals(fileExpiration, tb.getFileCreate().getExpirationTime());

        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
    }

    @Test
    void assertGetFileAppendBuilder() throws InvalidProtocolBufferException {
        var transaction = RequestBuilder.getFileAppendBuilder(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                hash,
                fileID);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.getMemo());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(hash, tb.getFileAppend().getContents());
        assertEquals(fileID, tb.getFileAppend().getFileID());

        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
    }

    @Test
    void assertGetFileUpdateBuilder() throws InvalidProtocolBufferException {
        Timestamp fileExpiration = Timestamp.newBuilder().setSeconds(123_456L).build();
        var transaction = RequestBuilder.getFileUpdateBuilder(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                fileExpiration,
                transactionDuration,
                generateRecord,
                memo,
                hash,
                fileID,
                keyList);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.getMemo());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(hash, tb.getFileUpdate().getContents());
        assertEquals(fileID, tb.getFileUpdate().getFileID());
        assertEquals(keyList, tb.getFileUpdate().getKeys());
        assertEquals(fileExpiration, tb.getFileUpdate().getExpirationTime());
        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
    }

    @Test
    void assertGetCryptoTransferRequest() throws InvalidProtocolBufferException {
        Long senderAccountNum = 1001L;
        Long amountSend = 1500L;
        Long receiverAccountNum = 1010L;
        Long amountReceived = 1500L;

        var transaction = RequestBuilder.getCryptoTransferRequest(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                senderAccountNum,
                amountSend,
                receiverAccountNum,
                amountReceived);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.getMemo());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
        assertEquals(
                senderAccountNum,
                tb.getCryptoTransfer()
                        .getTransfers()
                        .getAccountAmounts(0)
                        .getAccountID()
                        .getAccountNum());
        assertEquals(
                amountSend,
                tb.getCryptoTransfer().getTransfers().getAccountAmounts(0).getAmount());
        assertEquals(
                receiverAccountNum,
                tb.getCryptoTransfer()
                        .getTransfers()
                        .getAccountAmounts(1)
                        .getAccountID()
                        .getAccountNum());
        assertEquals(
                amountReceived,
                tb.getCryptoTransfer().getTransfers().getAccountAmounts(1).getAmount());
    }

    @Test
    void assertGetContractCallRequest() throws InvalidProtocolBufferException {
        long value = 1500L;
        var transaction = RequestBuilder.getContractCallRequest(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                gas,
                contractId,
                hash,
                value);

        var tb = buildSignedTransactionBody(transaction);

        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(transactionDuration, tb.getTransactionValidDuration());
        assertEquals(contractId, tb.getContractCall().getContractID());
        assertEquals(gas, tb.getContractCall().getGas());
        assertEquals(value, tb.getContractCall().getAmount());
    }

    @Test
    void assertGetContractUpdateRequest() throws InvalidProtocolBufferException {
        var proxyAccountID = AccountID.newBuilder()
                .setAccountNum(1010L)
                .setRealmNum(0)
                .setShardNum(0)
                .build();
        Timestamp expirationTime = Timestamp.newBuilder().setSeconds(124_56L).build();
        var transaction = RequestBuilder.getContractUpdateRequest(
                accountId,
                nodeId,
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                contractId,
                autoRenew,
                validED25519Key,
                proxyAccountID,
                expirationTime,
                contractMemo);

        var tb = buildSignedTransactionBody(transaction);

        assertEquals(nodeId, tb.getNodeAccountID());
        assertEquals(proxyAccountID, tb.getContractUpdateInstance().getProxyAccountID());
        assertEquals(accountId, tb.getTransactionID().getAccountID());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(memo, tb.getMemo());
        assertEquals(contractId, tb.getContractUpdateInstance().getContractID());
        assertEquals(autoRenew, tb.getContractUpdateInstance().getAutoRenewPeriod());
        assertEquals(validED25519Key, tb.getContractUpdateInstance().getAdminKey());
        assertEquals(expirationTime, tb.getContractUpdateInstance().getExpirationTime());
        assertEquals(contractMemo, tb.getContractUpdateInstance().getMemo());
    }

    @Test
    void assertGetCreateContractRequest() throws InvalidProtocolBufferException {
        var initialBalance = 300_000L;

        var transaction = RequestBuilder.getCreateContractRequest(
                accountId.getAccountNum(),
                accountId.getRealmNum(),
                accountId.getShardNum(),
                nodeId.getAccountNum(),
                nodeId.getRealmNum(),
                nodeId.getShardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                gas,
                fileID,
                hash,
                initialBalance,
                autoRenew,
                contractMemo,
                validED25519Key);

        var tb = buildSignedTransactionBody(transaction);

        assertEquals(nodeId.getAccountNum(), tb.getNodeAccountID().getAccountNum());
        assertEquals(nodeId.getRealmNum(), tb.getNodeAccountID().getRealmNum());
        assertEquals(nodeId.getShardNum(), tb.getNodeAccountID().getShardNum());
        assertEquals(
                accountId.getAccountNum(), tb.getTransactionID().getAccountID().getAccountNum());
        assertEquals(
                accountId.getRealmNum(), tb.getTransactionID().getAccountID().getRealmNum());
        assertEquals(
                accountId.getShardNum(), tb.getTransactionID().getAccountID().getShardNum());
        assertEquals(transactionFee, tb.getTransactionFee());
        assertEquals(startTime, tb.getTransactionID().getTransactionValidStart());
        assertEquals(generateRecord, tb.getGenerateRecord());
        assertEquals(memo, tb.getMemo());
        assertEquals(fileID, tb.getContractCreateInstance().getFileID());
        assertEquals(hash, tb.getContractCreateInstance().getConstructorParameters());
        assertEquals(initialBalance, tb.getContractCreateInstance().getInitialBalance());
        assertEquals(autoRenew, tb.getContractCreateInstance().getAutoRenewPeriod());
        assertEquals(contractMemo, tb.getContractCreateInstance().getMemo());
        assertEquals(validED25519Key, tb.getContractCreateInstance().getAdminKey());
    }

    @Test
    void assertConstructorThrowsException() throws NoSuchMethodException {
        Constructor<RequestBuilder> constructor = RequestBuilder.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> {
            constructor.newInstance();
        });
    }

    @Test
    void xferConvenienceBuildersDontThrow() {

        assertNotNull(RequestBuilder.getCryptoTransferRequest(
                1234l,
                0l,
                0l,
                3l,
                0l,
                0l,
                100_000_000L,
                Timestamp.getDefaultInstance(),
                Duration.getDefaultInstance(),
                false,
                "MEMO",
                5678l,
                -70000l,
                5679l,
                70000l));
        assertNotNull(RequestBuilder.getHbarCryptoTransferRequestToAlias(
                1234l,
                0l,
                0l,
                3l,
                0l,
                0l,
                100_000_000L,
                Timestamp.getDefaultInstance(),
                Duration.getDefaultInstance(),
                false,
                "MEMO",
                5678l,
                -70000l,
                ByteString.copyFromUtf8("ALIAS"),
                70000l));
        assertNotNull(RequestBuilder.getTokenTransferRequestToAlias(
                1234l,
                0l,
                0l,
                3l,
                0l,
                0l,
                100_000_000L,
                Timestamp.getDefaultInstance(),
                Duration.getDefaultInstance(),
                false,
                "MEMO",
                5678l,
                5555l,
                -70000l,
                ByteString.copyFromUtf8("aaaa"),
                70000l));
    }

    private TransactionBody buildSignedTransactionBody(Transaction transaction) throws InvalidProtocolBufferException {
        var signedTxn = SignedTransaction.parseFrom(transaction.getSignedTransactionBytes());
        var transactionBody = TransactionBody.parseFrom(signedTxn.getBodyBytes());
        return transactionBody;
    }
}
