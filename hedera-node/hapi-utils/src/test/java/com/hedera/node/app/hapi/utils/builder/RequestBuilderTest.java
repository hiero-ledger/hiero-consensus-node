// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestBuilderTest {
    private final long transactionFee = 1234L;
    private final long gas = 1234L;
    private final Bytes transactionBody = Bytes.wrap("0x00120");
    private final ResponseType responseType = ResponseType.ANSWER_STATE_PROOF;
    private final Bytes hash = Bytes.wrap("952e79e36a5fe25bd015c3a2ce85f318690c2c1fcb834c89ef06a84ba4d179c0");
    private final Key validED25519Key = Key.newBuilder()
            .ed25519(
                    Bytes.wrap(
                            "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771"))
            .build();
    private final KeyList keyList = KeyList.newBuilder().keys(validED25519Key).build();
    private final Timestamp startTime = Timestamp.newBuilder().seconds(1234L).build();
    private final Duration transactionDuration =
            Duration.newBuilder().seconds(30L).build();
    private final Duration autoRenew = Duration.newBuilder().seconds(30L).build();
    private final boolean generateRecord = false;
    private final String memo = "memo";
    private final String contractMemo = "contractMemo";

    private final AccountID accountId =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1002).build();

    private final FileID fileID =
            FileID.newBuilder().shardNum(0).realmNum(0).fileNum(6667).build();

    private final ContractID contractId =
            ContractID.newBuilder().shardNum(0).realmNum(0).contractNum(3337).build();

    private final AccountID nodeId =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(3).build();

    private final TransactionID transactionId =
            TransactionID.newBuilder().accountID(accountId).build();

    private final ResponseCodeEnum responseCodeEnum = ResponseCodeEnum.SUCCESS;
    private final ExchangeRate exchangeRate =
            ExchangeRate.newBuilder().hbarEquiv(1000).centEquiv(100).build();
    private final ExchangeRateSet exchangeRateSet =
            ExchangeRateSet.newBuilder().currentRate(exchangeRate).build();
    private final Transaction transaction =
            Transaction.newBuilder().bodyBytes(transactionBody).build();

    @Test
    void testExpirationTime() {
        final var now = Instant.now();

        final var expirationTime = RequestBuilder.getExpirationTime(now, transactionDuration);
        assertNotNull(expirationTime);

        final var expirationInstant = RequestBuilder.convertProtoTimeStamp(expirationTime);
        final var between = java.time.Duration.between(now, expirationInstant);
        assertEquals(transactionDuration.seconds(), between.getSeconds());
    }

    @Test
    void testGetFileDeleteBuilder() throws ParseException {
        final var payerAccountNum = 1L;
        final var realmNum = 0L;
        final var shardNum = 0L;
        final var nodeAccountNum = 2L;
        final var fileNo = 3L;
        final var transactionFee = 100L;
        final var timestamp = Timestamp.newBuilder().seconds(500L).nanos(500).build();
        final var duration = RequestBuilder.getDuration(500L);
        final var generateRecord = false;
        final var fileId = FileID.newBuilder()
                .fileNum(fileNo)
                .realmNum(realmNum)
                .shardNum(shardNum)
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
        assertEquals(fileId, transactionBody.fileDelete().fileID());
        assertEquals(
                payerAccountNum, transactionBody.transactionID().accountID().accountNum());
        assertEquals(timestamp, transactionBody.transactionID().transactionValidStart());
        assertEquals(realmNum, transactionBody.transactionID().accountID().realmNum());
        assertEquals(shardNum, transactionBody.transactionID().accountID().shardNum());
        assertEquals(nodeAccountNum, transactionBody.nodeAccountID().accountNum());
        assertEquals(duration, transactionBody.transactionValidDuration());
        assertEquals(generateRecord, transactionBody.generateRecord());
        assertEquals(memo, transactionBody.memo());
    }

    @Test
    void assertcryptoGetInfoQuery() {
        var infoQuery = RequestBuilder.getCryptoGetInfoQuery(accountId, transaction, responseType);

        assertEquals(accountId, infoQuery.cryptoGetInfo().accountID());
        assertEquals(responseType, infoQuery.cryptoGetInfo().header().responseType());
        assertEquals(transaction, infoQuery.cryptoGetInfo().header().payment());
    }

    @Test
    void assertcryptoBalanceQuery() {
        var infoQuery = RequestBuilder.getCryptoGetBalanceQuery(accountId, transaction, responseType);

        assertEquals(accountId, infoQuery.cryptogetAccountBalance().accountID());
        assertEquals(responseType, infoQuery.cryptogetAccountBalance().header().responseType());
        assertEquals(transaction, infoQuery.cryptogetAccountBalance().header().payment());
    }

    @Test
    void assertGetFileContentQuery() {
        var infoQuery = RequestBuilder.getFileContentQuery(fileID, transaction, responseType);

        assertEquals(fileID, infoQuery.fileGetContents().fileID());
        assertEquals(responseType, infoQuery.fileGetContents().header().responseType());
        assertEquals(transaction, infoQuery.fileGetContents().header().payment());
    }

    @Test
    void assertGetFileGetContentBuilder() {
        var infoQuery = RequestBuilder.getFileGetContentBuilder(transaction, fileID, responseType);

        assertEquals(fileID, infoQuery.fileGetContents().fileID());
        assertEquals(responseType, infoQuery.fileGetContents().header().responseType());
        assertEquals(transaction, infoQuery.fileGetContents().header().payment());
    }

    @Test
    void assertGetTransactionGetRecordQuery() {
        var infoQuery = RequestBuilder.getTransactionGetRecordQuery(transactionId, transaction, responseType);

        assertEquals(transactionId, infoQuery.transactionGetRecord().transactionID());
        assertEquals(responseType, infoQuery.transactionGetRecord().header().responseType());
        assertEquals(transaction, infoQuery.transactionGetRecord().header().payment());
    }

    @Test
    void assertGetAccountRecordsQuery() {
        var infoQuery = RequestBuilder.getAccountRecordsQuery(accountId, transaction, responseType);

        assertEquals(accountId, infoQuery.cryptoGetAccountRecords().accountID());
        assertEquals(responseType, infoQuery.cryptoGetAccountRecords().header().responseType());
        assertEquals(transaction, infoQuery.cryptoGetAccountRecords().header().payment());
    }

    @Test
    void assertGetFileGetInfoBuilder() {
        var infoQuery = RequestBuilder.getFileGetInfoBuilder(transaction, fileID, responseType);

        assertEquals(fileID, infoQuery.fileGetInfo().fileID());
        assertEquals(responseType, infoQuery.fileGetInfo().header().responseType());
        assertEquals(transaction, infoQuery.fileGetInfo().header().payment());
    }

    @Test
    void assertGetContractCallLocalQuery() {
        var maxResultSize = 123_456L;
        var functionResult = Bytes.wrap("functionResult");
        var infoQuery = RequestBuilder.getContractCallLocalQuery(
                contractId, gas, functionResult, maxResultSize, transaction, responseType);

        assertEquals(contractId, infoQuery.contractCallLocal().contractID());
        assertEquals(gas, infoQuery.contractCallLocal().gas());
        assertEquals(maxResultSize, infoQuery.contractCallLocal().maxResultSize());
        assertEquals(responseType, infoQuery.contractCallLocal().header().responseType());
        assertEquals(transaction, infoQuery.contractCallLocal().header().payment());
    }

    @Test
    void assertGetTransactionReceipt() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(accountId, responseCodeEnum, exchangeRateSet);

        assertEquals(accountId, transactionReceipt.accountID());
        assertEquals(exchangeRateSet, transactionReceipt.exchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.status());
    }

    @Test
    void assertGetTransactionReceiptByResponseCodeAndExchangeRate() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(responseCodeEnum, exchangeRateSet);

        assertEquals(exchangeRateSet, transactionReceipt.exchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.status());
    }

    @Test
    void assertGetTransactionReceiptByFileId() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(fileID, responseCodeEnum, exchangeRateSet);

        assertEquals(fileID, transactionReceipt.fileID());
        assertEquals(exchangeRateSet, transactionReceipt.exchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.status());
    }

    @Test
    void assertGetTransactionReceiptByContractId() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(contractId, responseCodeEnum, exchangeRateSet);

        assertEquals(contractId, transactionReceipt.contractID());
        assertEquals(exchangeRateSet, transactionReceipt.exchangeRate());
        assertEquals(responseCodeEnum, transactionReceipt.status());
    }

    @Test
    void assertGetTransactionReceiptByResponseCodeEnum() {
        var transactionReceipt = RequestBuilder.getTransactionReceipt(responseCodeEnum);
        assertEquals(responseCodeEnum, transactionReceipt.status());
    }

    @Test
    void assertGetBySolidityIDQuery() {
        var solidityId = "solidityId";
        var infoQuery = RequestBuilder.getBySolidityIdQuery(solidityId, transaction, responseType);

        assertEquals(solidityId, infoQuery.getBySolidityID().solidityID());
        assertEquals(responseType, infoQuery.getBySolidityID().header().responseType());
        assertEquals(transaction, infoQuery.getBySolidityID().header().payment());
    }

    @Test
    void assertGetAccountLiveHashQuery() {
        var infoQuery =
                RequestBuilder.getAccountLiveHashQuery(accountId, hash.toByteArray(), transaction, responseType);

        assertEquals(accountId, infoQuery.cryptoGetLiveHash().accountID());
        assertEquals(hash, infoQuery.cryptoGetLiveHash().hash());
        assertEquals(responseType, infoQuery.cryptoGetLiveHash().header().responseType());
        assertEquals(transaction, infoQuery.cryptoGetLiveHash().header().payment());
    }

    @Test
    void assertGetContractGetInfoQuery() {
        var contractGetInfoQuery = RequestBuilder.getContractGetInfoQuery(contractId, transaction, responseType);

        assertEquals(contractId, contractGetInfoQuery.contractGetInfo().contractID());
        assertEquals(
                responseType, contractGetInfoQuery.contractGetInfo().header().responseType());
        assertEquals(
                transaction, contractGetInfoQuery.contractGetInfo().header().payment());
    }

    @Test
    void assertGetContractGetBytecodeQuery() {
        var contractGetInfoQuery = RequestBuilder.getContractGetBytecodeQuery(contractId, transaction, responseType);

        assertEquals(contractId, contractGetInfoQuery.contractGetBytecode().contractID());
        assertEquals(
                responseType,
                contractGetInfoQuery.contractGetBytecode().header().responseType());
        assertEquals(
                transaction, contractGetInfoQuery.contractGetBytecode().header().payment());
    }

    @Test
    void assertGetContractRecordsQuery() {
        var contractGetInfoQuery = RequestBuilder.getContractRecordsQuery(contractId, transaction, responseType);

        assertEquals(contractId, contractGetInfoQuery.contractGetRecords().contractID());
        assertEquals(
                responseType, contractGetInfoQuery.contractGetRecords().header().responseType());
        assertEquals(
                transaction, contractGetInfoQuery.contractGetRecords().header().payment());
    }

    @Test
    void assertGetTransactionGetReceiptQuery() {
        var transactionGetReceiptQuery = RequestBuilder.getTransactionGetReceiptQuery(transactionId, responseType);

        assertEquals(responseType, transactionGetReceiptQuery.header().responseType());
        assertEquals(transactionId, transactionGetReceiptQuery.transactionID());
    }

    @Test
    void assertGetFastTransactionRecordQuery() {
        var fastRecordQuery = RequestBuilder.getFastTransactionRecordQuery(transactionId, responseType);

        assertEquals(responseType, fastRecordQuery.header().responseType());
        assertEquals(transactionId, fastRecordQuery.transactionID());
    }

    @Test
    void assertGetLiveHash() {
        var liveHash = RequestBuilder.getLiveHash(accountId, transactionDuration, keyList, hash.toByteArray());

        assertEquals(hash, liveHash.hash());
        assertEquals(transactionDuration, liveHash.duration());
        assertEquals(accountId, liveHash.accountId());
        assertEquals(keyList, liveHash.keys());
    }

    @Test
    void assertGetResponseHeader() {
        var cost = 1234L;
        var responseHeader = RequestBuilder.getResponseHeader(responseCodeEnum, cost, responseType, hash);

        assertEquals(responseType, responseHeader.responseType());
        assertEquals(cost, responseHeader.cost());
        assertEquals(hash, responseHeader.stateProof());
        assertEquals(responseCodeEnum, responseHeader.nodeTransactionPrecheckCode());
    }

    @Test
    void assertGetTransactionRecord() {
        var consensusTime = Instant.now();
        var transactionReceipt =
                TransactionReceipt.newBuilder().accountID(accountId).build();
        var transactionRecord =
                RequestBuilder.getTransactionRecord(transactionFee, memo, transactionId, startTime, transactionReceipt);

        assertEquals(transactionFee, transactionRecord.build().transactionFee());
        assertEquals(memo, transactionRecord.build().memo());
        assertEquals(transactionId, transactionRecord.build().transactionID());
        assertEquals(startTime, transactionRecord.build().consensusTimestamp());
        assertEquals(transactionReceipt, transactionRecord.build().receipt());
    }

    @Test
    void assertGetFileIdBuild() {
        var fileId = RequestBuilder.getFileIdBuild(fileID.fileNum(), fileID.realmNum(), fileID.shardNum());
        assertEquals(fileID.shardNum(), fileId.shardNum());
        assertEquals(fileID.realmNum(), fileId.realmNum());
        assertEquals(fileID.fileNum(), fileId.fileNum());
    }

    @Test
    void assertGetContractIdBuild() {
        var contractID = RequestBuilder.getContractIdBuild(
                contractId.contractNum(), contractId.realmNum(), contractId.shardNum());
        assertEquals(contractId.shardNum(), contractID.shardNum());
        assertEquals(contractId.realmNum(), contractID.realmNum());
        assertEquals(contractId.contractNum(), contractID.contractNum());
    }

    @Test
    void assertexchangeRateBuilder() {
        var hbarEquivalent = 1000;
        var centEquivalent = 100;
        var expirationSeconds = 1234L;
        var exchangeRate = RequestBuilder.getExchangeRateBuilder(hbarEquivalent, centEquivalent, expirationSeconds);
        assertEquals(hbarEquivalent, exchangeRate.hbarEquiv());
        assertEquals(centEquivalent, exchangeRate.centEquiv());
        assertEquals(expirationSeconds, exchangeRate.expirationTime().seconds());
    }

    @Test
    void assertexchangeRateSetBuilder() {
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

        assertEquals(currentHbarEquivalent, exchangeRateSet.currentRate().hbarEquiv());
        assertEquals(currentCentEquivalent, exchangeRateSet.currentRate().centEquiv());
        assertEquals(
                currentExpirationSeconds,
                exchangeRateSet.currentRate().expirationTime().seconds());
        assertEquals(nextHbarEquivalent, exchangeRateSet.nextRate().hbarEquiv());
        assertEquals(nextCentEquivalent, exchangeRateSet.nextRate().centEquiv());
        assertEquals(
                nextExpirationSeconds,
                exchangeRateSet.nextRate().expirationTime().seconds());
    }

    @Test
    void assertGetCreateAccountBuilder() throws ParseException {
        int thresholdValue = 3;
        List<Key> keyList = List.of(validED25519Key);
        long initBal = 300_000L;
        long sendRecordThreshold = 5L;
        long receiveRecordThreshold = 5L;
        boolean receiverSign = true;

        var transaction = RequestBuilder.getCreateAccountBuilder(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
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
        assertEquals(memo, tb.memo());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
    }

    @Test
    void assertGetTxBodyBuilder() {
        var transactionBody = RequestBuilder.getTxBodyBuilder(
                transactionFee, startTime, transactionDuration, generateRecord, memo, accountId, nodeId);

        assertEquals(memo, transactionBody.build().memo());
        assertEquals(transactionFee, transactionBody.build().transactionFee());
        assertEquals(startTime, transactionBody.build().transactionID().transactionValidStart());
        assertEquals(accountId, transactionBody.build().transactionID().accountID());
        assertEquals(generateRecord, transactionBody.build().generateRecord());
        assertEquals(transactionDuration, transactionBody.build().transactionValidDuration());
        assertEquals(nodeId, transactionBody.build().nodeAccountID());
    }

    @Test
    void assertGetTransactionBody() throws ParseException {
        var accId =
                AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1005).build();

        var transaction = RequestBuilder.getAccountUpdateRequest(
                accId,
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                autoRenew);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.memo());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
    }

    @Test
    void assertGetFileCreateBuilder() throws ParseException {
        Timestamp fileExpiration = Timestamp.newBuilder().seconds(123_456L).build();
        List<Key> keyList = List.of(validED25519Key);
        var transaction = RequestBuilder.getFileCreateBuilder(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                hash,
                fileExpiration,
                keyList);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.memo());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(hash, tb.fileCreate().contents());
        assertEquals(fileExpiration, tb.fileCreate().expirationTime());

        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
    }

    @Test
    void assertGetFileAppendBuilder() throws ParseException {
        var transaction = RequestBuilder.getFileAppendBuilder(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                generateRecord,
                memo,
                hash,
                fileID);

        var tb = buildSignedTransactionBody(transaction);
        assertEquals(memo, tb.memo());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(hash, tb.fileAppend().contents());
        assertEquals(fileID, tb.fileAppend().fileID());

        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
    }

    @Test
    void assertGetFileUpdateBuilder() throws ParseException {
        Timestamp fileExpiration = Timestamp.newBuilder().seconds(123_456L).build();
        var transaction = RequestBuilder.getFileUpdateBuilder(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
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
        assertEquals(memo, tb.memo());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(hash, tb.fileUpdate().contents());
        assertEquals(fileID, tb.fileUpdate().fileID());
        assertEquals(keyList, tb.fileUpdate().keys());
        assertEquals(fileExpiration, tb.fileUpdate().expirationTime());
        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
    }

    @Test
    void assertcryptoTransferRequest() throws ParseException {
        Long senderAccountNum = 1001L;
        Long amountSend = 1500L;
        Long receiverAccountNum = 1010L;
        Long amountReceived = 1500L;

        var transaction = RequestBuilder.getCryptoTransferRequest(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
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
        assertEquals(memo, tb.memo());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
        assertEquals(
                senderAccountNum,
                tb.cryptoTransfer()
                        .transfers()
                        .accountAmounts()
                        .getFirst()
                        .accountID()
                        .accountNum());
        assertEquals(
                amountSend,
                tb.cryptoTransfer().transfers().accountAmounts().getFirst().amount());
        assertEquals(
                receiverAccountNum,
                tb.cryptoTransfer()
                        .transfers()
                        .accountAmounts()
                        .get(1)
                        .accountID()
                        .accountNum());
        assertEquals(
                amountReceived,
                tb.cryptoTransfer().transfers().accountAmounts().get(1).amount());
    }

    @Test
    void assertGetContractCallRequest() throws ParseException {
        long value = 1500L;
        var transaction = RequestBuilder.getContractCallRequest(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
                transactionFee,
                startTime,
                transactionDuration,
                gas,
                contractId,
                hash,
                value);

        var tb = buildSignedTransactionBody(transaction);

        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(transactionDuration, tb.transactionValidDuration());
        assertEquals(contractId, tb.contractCall().contractID());
        assertEquals(gas, tb.contractCall().gas());
        assertEquals(value, tb.contractCall().amount());
    }

    @Test
    void assertGetContractUpdateRequest() throws ParseException {
        var proxyAccountID =
                AccountID.newBuilder().accountNum(1010L).realmNum(0).shardNum(0).build();
        Timestamp expirationTime = Timestamp.newBuilder().seconds(124_56L).build();
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

        assertEquals(nodeId, tb.nodeAccountID());
        assertEquals(proxyAccountID, tb.contractUpdateInstance().proxyAccountID());
        assertEquals(accountId, tb.transactionID().accountID());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(memo, tb.memo());
        assertEquals(contractId, tb.contractUpdateInstance().contractID());
        assertEquals(autoRenew, tb.contractUpdateInstance().autoRenewPeriod());
        assertEquals(validED25519Key, tb.contractUpdateInstance().adminKey());
        assertEquals(expirationTime, tb.contractUpdateInstance().expirationTime());
        assertEquals(contractMemo, tb.contractUpdateInstance().memo());
    }

    @Test
    void assertGetCreateContractRequest() throws ParseException {
        var initialBalance = 300_000L;

        var transaction = RequestBuilder.getCreateContractRequest(
                accountId.accountNum(),
                accountId.realmNum(),
                accountId.shardNum(),
                nodeId.accountNum(),
                nodeId.realmNum(),
                nodeId.shardNum(),
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

        assertEquals(nodeId.accountNum(), tb.nodeAccountID().accountNum());
        assertEquals(nodeId.realmNum(), tb.nodeAccountID().realmNum());
        assertEquals(nodeId.shardNum(), tb.nodeAccountID().shardNum());
        assertEquals(accountId.accountNum(), tb.transactionID().accountID().accountNum());
        assertEquals(accountId.realmNum(), tb.transactionID().accountID().realmNum());
        assertEquals(accountId.shardNum(), tb.transactionID().accountID().shardNum());
        assertEquals(transactionFee, tb.transactionFee());
        assertEquals(startTime, tb.transactionID().transactionValidStart());
        assertEquals(generateRecord, tb.generateRecord());
        assertEquals(memo, tb.memo());
        assertEquals(fileID, tb.contractCreateInstance().fileID());
        assertEquals(hash, tb.contractCreateInstance().constructorParameters());
        assertEquals(initialBalance, tb.contractCreateInstance().initialBalance());
        assertEquals(autoRenew, tb.contractCreateInstance().autoRenewPeriod());
        assertEquals(contractMemo, tb.contractCreateInstance().memo());
        assertEquals(validED25519Key, tb.contractCreateInstance().adminKey());
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
                Timestamp.DEFAULT,
                Duration.DEFAULT,
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
                Timestamp.DEFAULT,
                Duration.DEFAULT,
                false,
                "MEMO",
                5678l,
                -70000l,
                Bytes.wrap("ALIAS"),
                70000l));
        assertNotNull(RequestBuilder.getTokenTransferRequestToAlias(
                1234l,
                0l,
                0l,
                3l,
                0l,
                0l,
                100_000_000L,
                Timestamp.DEFAULT,
                Duration.DEFAULT,
                false,
                "MEMO",
                5678l,
                5555l,
                -70000l,
                Bytes.wrap("aaaa"),
                70000l));
    }

    private TransactionBody buildSignedTransactionBody(Transaction transaction) throws ParseException {
        var signedTxn = SignedTransaction.PROTOBUF.parse(transaction.signedTransactionBytes());
        var transactionBody = TransactionBody.PROTOBUF.parse(signedTxn.bodyBytes());
        return transactionBody;
    }
}
