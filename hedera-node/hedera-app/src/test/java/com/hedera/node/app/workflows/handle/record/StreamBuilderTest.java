// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings({"DataFlowIssue"})
@ExtendWith(MockitoExtension.class)
public class StreamBuilderTest {
    public static final Instant CONSENSUS_TIME = Instant.now();
    public static final Instant PARENT_CONSENSUS_TIME = CONSENSUS_TIME.plusNanos(1L);
    public static final long TRANSACTION_FEE = 6846513L;
    public static final int ENTROPY_NUMBER = 87372879;
    public static final long TOPIC_SEQUENCE_NUMBER = 928782L;
    public static final long TOPIC_RUNNING_HASH_VERSION = 153513L;
    public static final long NEW_TOTAL_SUPPLY = 34134546L;
    public static final String MEMO = "Yo Memo";
    private @Mock Transaction transaction;
    private @Mock TransactionID transactionID;
    private final Bytes transactionBytes = Bytes.wrap("Hello Tester");
    private @Mock ContractFunctionResult contractCallResult;
    private @Mock ContractFunctionResult contractCreateResult;
    private @Mock TransferList transferList;
    private @Mock TokenTransferList tokenTransfer;
    private @Mock ScheduleID scheduleRef;
    private @Mock AssessedCustomFee assessedCustomFee;
    private @Mock TokenAssociation tokenAssociation;
    private @Mock Bytes alias;
    private @Mock Bytes ethereumHash;
    private @Mock Bytes prngBytes;
    private @Mock AccountAmount accountAmount;
    private @Mock Bytes evmAddress;
    private @Mock ResponseCodeEnum status;
    private @Mock AccountID accountID;
    private @Mock FileID fileID;
    private @Mock ContractID contractID;
    private @Mock ExchangeRateSet exchangeRate;
    private @Mock TopicID topicID;
    private @Mock Bytes topicRunningHash;
    private @Mock TokenID tokenID;
    private @Mock ScheduleID scheduleID;
    private @Mock TransactionID scheduledTransactionID;
    private @Mock ContractStateChanges contractStateChanges;
    private @Mock ContractActions contractActions;
    private @Mock ContractBytecode contractBytecode;

    @ParameterizedTest
    @EnumSource(TransactionRecord.EntropyOneOfType.class)
    void testBuilder(TransactionRecord.EntropyOneOfType entropyOneOfType) {
        if (entropyOneOfType == TransactionRecord.EntropyOneOfType.UNSET) {
            return;
        }

        final List<TokenTransferList> tokenTransferLists = List.of(tokenTransfer);
        final List<AssessedCustomFee> assessedCustomFees = List.of(assessedCustomFee);
        final List<TokenAssociation> automaticTokenAssociations = List.of(tokenAssociation);
        final List<AccountAmount> paidStakingRewards = List.of(accountAmount);
        final List<Long> serialNumbers = List.of(1L, 2L, 3L);

        RecordStreamBuilder singleTransactionRecordBuilder =
                new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);

        singleTransactionRecordBuilder
                .parentConsensus(PARENT_CONSENSUS_TIME)
                .transaction(transaction)
                .transactionBytes(transactionBytes)
                .transactionID(transactionID)
                .memo(MEMO)
                .transactionFee(TRANSACTION_FEE)
                .contractCallResult(contractCallResult)
                .contractCreateResult(contractCreateResult)
                .transferList(transferList)
                .tokenTransferLists(tokenTransferLists)
                .scheduleRef(scheduleRef)
                .assessedCustomFees(assessedCustomFees)
                .automaticTokenAssociations(automaticTokenAssociations)
                .ethereumHash(ethereumHash, false)
                .paidStakingRewards(paidStakingRewards)
                .evmAddress(evmAddress)
                .status(status)
                .accountID(accountID)
                .fileID(fileID)
                .contractID(contractID)
                .exchangeRate(exchangeRate)
                .topicID(topicID)
                .topicSequenceNumber(TOPIC_SEQUENCE_NUMBER)
                .topicRunningHash(topicRunningHash)
                .topicRunningHashVersion(TOPIC_RUNNING_HASH_VERSION)
                .tokenID(tokenID)
                .newTotalSupply(NEW_TOTAL_SUPPLY)
                .scheduleID(scheduleID)
                .scheduledTransactionID(scheduledTransactionID)
                .serialNumbers(serialNumbers)
                .contractStateChanges(List.of(new AbstractMap.SimpleEntry<>(contractStateChanges, false)))
                .addContractActions(contractActions, false)
                .addContractBytecode(contractBytecode, false);

        if (entropyOneOfType == TransactionRecord.EntropyOneOfType.PRNG_BYTES) {
            singleTransactionRecordBuilder.entropyBytes(prngBytes);
        } else if (entropyOneOfType == TransactionRecord.EntropyOneOfType.PRNG_NUMBER) {
            singleTransactionRecordBuilder.entropyNumber(ENTROPY_NUMBER);
        } else {
            fail("Unknown entropy type");
        }

        SingleTransactionRecord singleTransactionRecord = singleTransactionRecordBuilder.build();
        assertEquals(
                HapiUtils.asTimestamp(PARENT_CONSENSUS_TIME),
                singleTransactionRecord.transactionRecord().parentConsensusTimestamp());
        assertEquals(transaction, singleTransactionRecord.transaction());

        if (entropyOneOfType == TransactionRecord.EntropyOneOfType.PRNG_BYTES) {
            assertTrue(singleTransactionRecord.transactionRecord().hasPrngBytes());
            assertEquals(prngBytes, singleTransactionRecord.transactionRecord().prngBytes());
        } else {
            assertTrue(singleTransactionRecord.transactionRecord().hasPrngNumber());
            assertEquals(
                    ENTROPY_NUMBER, singleTransactionRecord.transactionRecord().prngNumber());
        }

        final Bytes transactionHash;
        try {
            final MessageDigest digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            transactionHash = Bytes.wrap(digest.digest(transactionBytes.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        assertEquals(
                transactionHash, singleTransactionRecord.transactionRecord().transactionHash());
        // Consensus timestamp will be set at end of handle workflow
        assertEquals(
                Timestamp.DEFAULT, singleTransactionRecord.transactionRecord().consensusTimestamp());
        assertEquals(transactionID, singleTransactionRecord.transactionRecord().transactionID());
        assertEquals(MEMO, singleTransactionRecord.transactionRecord().memo());
        assertEquals(
                TRANSACTION_FEE, singleTransactionRecord.transactionRecord().transactionFee());
        assertEquals(transferList, singleTransactionRecord.transactionRecord().transferList());
        assertEquals(
                tokenTransferLists, singleTransactionRecord.transactionRecord().tokenTransferLists());
        assertEquals(scheduleRef, singleTransactionRecord.transactionRecord().scheduleRef());
        assertEquals(
                assessedCustomFees, singleTransactionRecord.transactionRecord().assessedCustomFees());
        assertEquals(
                automaticTokenAssociations,
                singleTransactionRecord.transactionRecord().automaticTokenAssociations());
        assertEquals(
                HapiUtils.asTimestamp(PARENT_CONSENSUS_TIME),
                singleTransactionRecord.transactionRecord().parentConsensusTimestamp());
        assertEquals(ethereumHash, singleTransactionRecord.transactionRecord().ethereumHash());
        assertEquals(
                paidStakingRewards, singleTransactionRecord.transactionRecord().paidStakingRewards());
        assertEquals(evmAddress, singleTransactionRecord.transactionRecord().evmAddress());

        assertTransactionReceiptProps(
                singleTransactionRecord.transactionRecord().receipt(), serialNumbers);
        // Consensus timestamp will be set at end of handle workflow
        final var expectedTransactionSidecarRecords = List.of(
                new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(
                                TransactionSidecarRecord.SidecarRecordsOneOfType.STATE_CHANGES, contractStateChanges)),
                new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.ACTIONS, contractActions)),
                new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.BYTECODE, contractBytecode)));
        assertEquals(expectedTransactionSidecarRecords, singleTransactionRecord.transactionSidecarRecords());
    }

    private void assertTransactionReceiptProps(TransactionReceipt receipt, List<Long> serialNumbers) {
        assertEquals(status, receipt.status());
        assertNull(receipt.accountID());
        assertEquals(fileID, receipt.fileID());
        assertEquals(contractID, receipt.contractID());
        assertNull(receipt.exchangeRate());
        assertEquals(topicID, receipt.topicID());
        assertEquals(TOPIC_SEQUENCE_NUMBER, receipt.topicSequenceNumber());
        assertEquals(topicRunningHash, receipt.topicRunningHash());
        assertEquals(tokenID, receipt.tokenID());
        assertEquals(NEW_TOTAL_SUPPLY, receipt.newTotalSupply());
        assertEquals(scheduleID, receipt.scheduleID());
        assertEquals(scheduledTransactionID, receipt.scheduledTransactionID());
        assertEquals(serialNumbers, receipt.serialNumbers());
    }

    @Test
    void testTopLevelRecordBuilder() {
        RecordStreamBuilder singleTransactionRecordBuilder =
                new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);

        singleTransactionRecordBuilder.transaction(transaction);

        assertNull(singleTransactionRecordBuilder.parentConsensusTimestamp());
        assertEquals(ResponseCodeEnum.OK, singleTransactionRecordBuilder.status());

        SingleTransactionRecord singleTransactionRecord = singleTransactionRecordBuilder.build();

        assertEquals(transaction, singleTransactionRecord.transaction());
        // Consensus timestamp will be set at end of handle workflow
        assertEquals(
                Timestamp.DEFAULT, singleTransactionRecord.transactionRecord().consensusTimestamp());
        assertNull(singleTransactionRecord.transactionRecord().parentConsensusTimestamp());
        assertEquals(
                ResponseCodeEnum.OK,
                singleTransactionRecord.transactionRecord().receipt().status());
    }

    @Test
    void testBuilderWithAddMethods() {
        RecordStreamBuilder singleTransactionRecordBuilder =
                new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);

        SingleTransactionRecord singleTransactionRecord = singleTransactionRecordBuilder
                .transaction(transaction)
                .addTokenTransferList(tokenTransfer)
                .addAssessedCustomFee(assessedCustomFee)
                .addAutomaticTokenAssociation(tokenAssociation)
                .addPaidStakingReward(accountAmount)
                .addSerialNumber(1L)
                .addContractStateChanges(contractStateChanges, false)
                .addContractActions(contractActions, false)
                .addContractBytecode(contractBytecode, false)
                .build();

        assertEquals(transaction, singleTransactionRecord.transaction());
        // Consensus timestamp will be set at end of handle workflow
        assertEquals(
                Timestamp.DEFAULT, singleTransactionRecord.transactionRecord().consensusTimestamp());
        assertNull(singleTransactionRecord.transactionRecord().parentConsensusTimestamp());
        assertEquals(
                ResponseCodeEnum.OK,
                singleTransactionRecord.transactionRecord().receipt().status());
        assertEquals(
                List.of(tokenTransfer),
                singleTransactionRecord.transactionRecord().tokenTransferLists());
        assertEquals(
                List.of(assessedCustomFee),
                singleTransactionRecord.transactionRecord().assessedCustomFees());
        assertEquals(
                List.of(tokenAssociation),
                singleTransactionRecord.transactionRecord().automaticTokenAssociations());
        assertEquals(
                List.of(accountAmount),
                singleTransactionRecord.transactionRecord().paidStakingRewards());
        assertEquals(
                List.of(1L),
                singleTransactionRecord.transactionRecord().receipt().serialNumbers());

        final var expectedTransactionSidecarRecords = List.of(
                // Consensus timestamp will be set at end of handle workflow
                new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(
                                TransactionSidecarRecord.SidecarRecordsOneOfType.STATE_CHANGES, contractStateChanges)),
                new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.ACTIONS, contractActions)),
                // Consensus timestamp will be set at end of handle workflow
                new TransactionSidecarRecord(
                        Timestamp.DEFAULT,
                        false,
                        new OneOf<>(TransactionSidecarRecord.SidecarRecordsOneOfType.BYTECODE, contractBytecode)));
        assertEquals(expectedTransactionSidecarRecords, singleTransactionRecord.transactionSidecarRecords());
    }
}
