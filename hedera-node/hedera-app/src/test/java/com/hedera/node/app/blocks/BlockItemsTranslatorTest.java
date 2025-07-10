// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.blocks.BlockItemsTranslator.BLOCK_ITEMS_TRANSLATOR;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.CreateContractOutput;
import com.hedera.hapi.block.stream.output.CreateScheduleOutput;
import com.hedera.hapi.block.stream.output.EthereumOutput;
import com.hedera.hapi.block.stream.output.SignScheduleOutput;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.output.UtilPrngOutput;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.contract.InternalCallContext;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.blocks.impl.contexts.AirdropOpContext;
import com.hedera.node.app.blocks.impl.contexts.BaseOpContext;
import com.hedera.node.app.blocks.impl.contexts.ContractOpContext;
import com.hedera.node.app.blocks.impl.contexts.CryptoOpContext;
import com.hedera.node.app.blocks.impl.contexts.FileOpContext;
import com.hedera.node.app.blocks.impl.contexts.MintOpContext;
import com.hedera.node.app.blocks.impl.contexts.NodeOpContext;
import com.hedera.node.app.blocks.impl.contexts.ScheduleOpContext;
import com.hedera.node.app.blocks.impl.contexts.SubmitOpContext;
import com.hedera.node.app.blocks.impl.contexts.SupplyChangeOpContext;
import com.hedera.node.app.blocks.impl.contexts.TokenOpContext;
import com.hedera.node.app.blocks.impl.contexts.TopicOpContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BlockItemsTranslatorTest {
    private static final long FEE = 12324567890L;
    private static final String MEMO = "MEMO";
    private static final Timestamp CONSENSUS_TIME = new Timestamp(1_234_567, 890);
    private static final Timestamp PARENT_CONSENSUS_TIME = new Timestamp(1_234_567, 0);
    private static final ScheduleID SCHEDULE_REF =
            ScheduleID.newBuilder().scheduleNum(123L).build();
    private static final EvmTransactionResult EVM_TRANSACTION_RESULT = EvmTransactionResult.newBuilder()
            .internalCallContext(InternalCallContext.newBuilder().value(666).build())
            .build();
    private static final ContractFunctionResult FUNCTION_RESULT =
            ContractFunctionResult.newBuilder().amount(666L).build();
    private static final List<AssessedCustomFee> ASSESSED_CUSTOM_FEES = List.of(new AssessedCustomFee(
            1L,
            TokenID.newBuilder().tokenNum(123).build(),
            AccountID.newBuilder().accountNum(98L).build(),
            List.of(AccountID.newBuilder().accountNum(2L).build())));
    private static final TransferList TRANSFER_LIST = TransferList.newBuilder()
            .accountAmounts(
                    AccountAmount.newBuilder()
                            .amount(-1)
                            .accountID(AccountID.newBuilder().accountNum(2L).build())
                            .build(),
                    AccountAmount.newBuilder()
                            .amount(+1)
                            .accountID(AccountID.newBuilder().accountNum(98L).build())
                            .build())
            .build();
    private static final List<AccountAmount> PAID_STAKING_REWARDS = List.of(
            AccountAmount.newBuilder()
                    .amount(-1)
                    .accountID(AccountID.newBuilder().accountNum(800L).build())
                    .build(),
            AccountAmount.newBuilder()
                    .amount(+1)
                    .accountID(AccountID.newBuilder().accountNum(2L).build())
                    .build());
    private static final List<TokenAssociation> AUTO_TOKEN_ASSOCIATIONS = List.of(new TokenAssociation(
            TokenID.newBuilder().tokenNum(123).build(),
            AccountID.newBuilder().accountNum(98L).build()));
    private static final List<PendingAirdropRecord> PENDING_AIRDROP_RECORDS = List.of(new PendingAirdropRecord(
            PendingAirdropId.newBuilder()
                    .nonFungibleToken(
                            new NftID(TokenID.newBuilder().tokenNum(123).build(), 1L))
                    .build(),
            new PendingAirdropValue(666L)));
    private static final List<TokenTransferList> TOKEN_TRANSFER_LISTS = List.of(new TokenTransferList(
            TokenID.newBuilder().tokenNum(123).build(), TRANSFER_LIST.accountAmounts(), List.of(), 0));
    private static final TransactionID TXN_ID = TransactionID.newBuilder()
            .accountID(AccountID.newBuilder().accountNum(2L).build())
            .build();
    private static final ExchangeRateSet RATES = ExchangeRateSet.newBuilder()
            .currentRate(new ExchangeRate(1, 2, TimestampSeconds.DEFAULT))
            .nextRate(new ExchangeRate(3, 4, TimestampSeconds.DEFAULT))
            .build();
    private static final TransactionResult TRANSACTION_RESULT = TransactionResult.newBuilder()
            .consensusTimestamp(CONSENSUS_TIME)
            .parentConsensusTimestamp(PARENT_CONSENSUS_TIME)
            .scheduleRef(SCHEDULE_REF)
            .transactionFeeCharged(FEE)
            .transferList(TRANSFER_LIST)
            .tokenTransferLists(TOKEN_TRANSFER_LISTS)
            .automaticTokenAssociations(AUTO_TOKEN_ASSOCIATIONS)
            .paidStakingRewards(PAID_STAKING_REWARDS)
            .status(SUCCESS)
            .build();
    private static final TransactionReceipt EXPECTED_BASE_RECEIPT =
            TransactionReceipt.newBuilder().exchangeRate(RATES).status(SUCCESS).build();
    private static final TransactionRecord EXPECTED_BASE_RECORD = TransactionRecord.newBuilder()
            .transactionID(TXN_ID)
            .memo(MEMO)
            .transactionHash(Bytes.wrap(noThrowSha384HashOf(
                    Transaction.PROTOBUF.toBytes(Transaction.DEFAULT).toByteArray())))
            .consensusTimestamp(CONSENSUS_TIME)
            .parentConsensusTimestamp(PARENT_CONSENSUS_TIME)
            .scheduleRef(SCHEDULE_REF)
            .transactionFee(FEE)
            .transferList(TRANSFER_LIST)
            .tokenTransferLists(TOKEN_TRANSFER_LISTS)
            .automaticTokenAssociations(AUTO_TOKEN_ASSOCIATIONS)
            .paidStakingRewards(PAID_STAKING_REWARDS)
            .receipt(EXPECTED_BASE_RECEIPT)
            .build();
    private static final TransactionID SCHEDULED_TXN_ID =
            TransactionID.newBuilder().scheduled(true).build();
    private static final ScheduleID SCHEDULE_ID =
            ScheduleID.newBuilder().scheduleNum(666L).build();
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().contractNum(666L).build();
    private static final AccountID ACCOUNT_ID =
            AccountID.newBuilder().accountNum(666L).build();
    private static final TokenID TOKEN_ID = TokenID.newBuilder().tokenNum(666L).build();
    private static final TopicID TOPIC_ID = TopicID.newBuilder().topicNum(666L).build();
    private static final FileID FILE_ID = FileID.newBuilder().fileNum(666L).build();
    private static final long NODE_ID = 666L;
    private static final Bytes ETH_HASH = Bytes.fromHex("01".repeat(32));
    private static final Bytes EVM_ADDRESS = Bytes.fromHex("0101010101010101010101010101010101010101");
    private static final Bytes RUNNING_HASH = Bytes.fromHex("01".repeat(48));
    private static final long RUNNING_HASH_VERSION = 7L;
    private static final long TOPIC_SEQUENCE_NUMBER = 666L;
    private static final long NEW_TOTAL_SUPPLY = 666L;
    private static final List<Long> SERIAL_NOS = List.of(1L, 2L, 3L);

    // --- RECEIPT TRANSLATION TESTS ---
    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {
                "CONTRACT_CALL",
                "CONTRACT_CREATE",
                "CONTRACT_UPDATE",
                "CONTRACT_DELETE",
                "ETHEREUM_TRANSACTION",
                "CRYPTO_CREATE",
                "CRYPTO_UPDATE",
                "FILE_CREATE",
                "NODE_CREATE",
                "TOKEN_CREATE",
                "CONSENSUS_CREATE_TOPIC",
                "SCHEDULE_CREATE",
                "SCHEDULE_SIGN",
                "SCHEDULE_DELETE",
                "CONSENSUS_SUBMIT_MESSAGE",
                "TOKEN_MINT",
                "TOKEN_ACCOUNT_WIPE",
                "TOKEN_BURN",
            })
    void mostOpsUseJustUseBaseOpContextForReceipt(@NonNull final HederaFunctionality function) {
        final var context = new BaseOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, function);

        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT, actualReceipt);
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            names = {
                "CONTRACT_CALL",
                "CONTRACT_CREATE",
                "CONTRACT_UPDATE",
                "CONTRACT_DELETE",
                "ETHEREUM_TRANSACTION",
            })
    void contractOpsUseContractOpContext(@NonNull final HederaFunctionality function) {
        final var context = new ContractOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                function,
                CONTRACT_ID,
                null,
                null,
                null,
                null,
                null,
                Bytes.EMPTY);

        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().contractID(CONTRACT_ID).build(), actualReceipt);
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            names = {
                "CRYPTO_CREATE",
                "CRYPTO_UPDATE",
            })
    void certainCryptoOpsUseCryptoOpContext(@NonNull final HederaFunctionality function) {
        final var context =
                new CryptoOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, function, ACCOUNT_ID, EVM_ADDRESS);
        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().accountID(ACCOUNT_ID).build(), actualReceipt);
    }

    @Test
    void fileCreateUsesFileOpContext() {
        final var context = new FileOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, FILE_CREATE, FILE_ID);
        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().fileID(FILE_ID).build(), actualReceipt);
    }

    @Test
    void nodeCreateUsesNodeOpContext() {
        final var context = new NodeOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, NODE_CREATE, NODE_ID);
        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().nodeId(NODE_ID).build(), actualReceipt);
    }

    @Test
    void tokenCreateUsesTokenOpContext() {
        final var context = new TokenOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, TOKEN_CREATE, TOKEN_ID);
        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().tokenID(TOKEN_ID).build(), actualReceipt);
    }

    @Test
    void topicCreateUsesTopicOpContext() {
        final var context =
                new TopicOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, CONSENSUS_CREATE_TOPIC, TOPIC_ID);
        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().topicID(TOPIC_ID).build(), actualReceipt);
    }

    @Test
    void scheduleCreateUsesCreateScheduleOutputOnlyIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .createSchedule(new CreateScheduleOutput(SCHEDULE_ID, SCHEDULED_TXN_ID))
                .build();
        final var context = new BaseOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, SCHEDULE_CREATE);

        final var actualReceiptNoOutput = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT, actualReceiptNoOutput);

        final var actualReceiptWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT, output);
        assertEquals(
                EXPECTED_BASE_RECEIPT
                        .copyBuilder()
                        .scheduleID(SCHEDULE_ID)
                        .scheduledTransactionID(SCHEDULED_TXN_ID)
                        .build(),
                actualReceiptWithOutput);
    }

    @Test
    void scheduleSignUsesSignScheduleOutputOnlyIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .signSchedule(new SignScheduleOutput(SCHEDULED_TXN_ID))
                .build();
        final var context = new BaseOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, SCHEDULE_SIGN);

        final var actualReceiptNoOutput = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT, actualReceiptNoOutput);

        final var actualReceiptWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT, output);
        assertEquals(
                EXPECTED_BASE_RECEIPT
                        .copyBuilder()
                        .scheduledTransactionID(SCHEDULED_TXN_ID)
                        .build(),
                actualReceiptWithOutput);
    }

    @Test
    void scheduleDeleteUsesScheduleOpContext() {
        final var context =
                new ScheduleOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, SCHEDULE_DELETE, SCHEDULE_ID);

        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(EXPECTED_BASE_RECEIPT.copyBuilder().scheduleID(SCHEDULE_ID).build(), actualReceipt);
    }

    @Test
    void submitMessageUsesSubmitOpContext() {
        final var context = new SubmitOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                CONSENSUS_SUBMIT_MESSAGE,
                RUNNING_HASH,
                RUNNING_HASH_VERSION,
                TOPIC_SEQUENCE_NUMBER);

        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(
                EXPECTED_BASE_RECEIPT
                        .copyBuilder()
                        .topicRunningHash(RUNNING_HASH)
                        .topicRunningHashVersion(RUNNING_HASH_VERSION)
                        .topicSequenceNumber(TOPIC_SEQUENCE_NUMBER)
                        .build(),
                actualReceipt);
    }

    @Test
    void tokenMintUsesMintOpContext() {
        final var context =
                new MintOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, TOKEN_MINT, SERIAL_NOS, NEW_TOTAL_SUPPLY);

        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(
                EXPECTED_BASE_RECEIPT
                        .copyBuilder()
                        .newTotalSupply(NEW_TOTAL_SUPPLY)
                        .serialNumbers(SERIAL_NOS)
                        .build(),
                actualReceipt);
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            names = {
                "TOKEN_ACCOUNT_WIPE",
                "TOKEN_BURN",
            })
    void supplyChangeOpsUseSupplyChangeContext(@NonNull final HederaFunctionality function) {
        final var context =
                new SupplyChangeOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, function, NEW_TOTAL_SUPPLY);
        final var actualReceipt = BLOCK_ITEMS_TRANSLATOR.translateReceipt(context, TRANSACTION_RESULT);
        assertEquals(
                EXPECTED_BASE_RECEIPT
                        .copyBuilder()
                        .newTotalSupply(NEW_TOTAL_SUPPLY)
                        .build(),
                actualReceipt);
    }

    // --- RECORD TRANSLATION TESTS ---

    @Test
    void contractCallUsesResultOutputIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .contractCall(new CallContractOutput(EVM_TRANSACTION_RESULT))
                .build();
        final var context = new ContractOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                CONTRACT_CALL,
                CONTRACT_ID,
                null,
                null,
                null,
                null,
                null,
                Bytes.EMPTY);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .contractCallResult((ContractFunctionResult) null)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordNoOutput);

        final var actualRecordWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, output);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .contractCallResult(FUNCTION_RESULT)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordWithOutput);
    }

    @Test
    void contractCallUsesResultOutputAndTraceLogsIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .contractCall(new CallContractOutput(EVM_TRANSACTION_RESULT))
                .build();
        final var context = new ContractOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                CONTRACT_CALL,
                CONTRACT_ID,
                null,
                null,
                null,
                null,
                null,
                Bytes.EMPTY);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .contractCallResult((ContractFunctionResult) null)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordNoOutput);

        final var aContractId = ContractID.newBuilder().contractNum(123).build();
        final var bContractId = ContractID.newBuilder().contractNum(456).build();
        final var aLog = new EvmTransactionLog(aContractId, Bytes.wrap("Apple"), List.of(Bytes.wrap("A")));
        final var bLog = new EvmTransactionLog(bContractId, Bytes.wrap("Banana"), List.of(Bytes.wrap("B")));
        final var logs = List.of(aLog, bLog);
        final var actualRecordWithOutputAndLogs =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, logs, output);
        assertEquals(
                2,
                actualRecordWithOutputAndLogs
                        .contractCallResultOrThrow()
                        .logInfo()
                        .size());
    }

    @Test
    void contractCreateUsesResultOutputIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .contractCreate(new CreateContractOutput(EVM_TRANSACTION_RESULT))
                .build();
        final var context = new ContractOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                CONTRACT_CREATE,
                CONTRACT_ID,
                null,
                null,
                null,
                null,
                null,
                Bytes.EMPTY);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .contractCreateResult((ContractFunctionResult) null)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordNoOutput);

        final var actualRecordWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, output);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .contractCreateResult(FUNCTION_RESULT)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordWithOutput);
    }

    @Test
    void ethTxCallUsesResultOutputIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .ethereumCall(EthereumOutput.newBuilder()
                        .ethereumHash(ETH_HASH)
                        .evmCallTransactionResult(EVM_TRANSACTION_RESULT)
                        .build())
                .build();
        final var context = new ContractOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                ETHEREUM_TRANSACTION,
                CONTRACT_ID,
                null,
                null,
                null,
                null,
                null,
                ETH_HASH);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .ethereumHash(ETH_HASH)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordNoOutput);

        final var actualRecordWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, output);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .ethereumHash(ETH_HASH)
                        .contractCallResult(FUNCTION_RESULT)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordWithOutput);
    }

    @Test
    void ethTxCreateUsesResultOutputIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .ethereumCall(EthereumOutput.newBuilder()
                        .ethereumHash(ETH_HASH)
                        .evmCreateTransactionResult(EVM_TRANSACTION_RESULT)
                        .build())
                .build();
        final var context = new ContractOpContext(
                MEMO,
                RATES,
                TXN_ID,
                Transaction.DEFAULT,
                ETHEREUM_TRANSACTION,
                CONTRACT_ID,
                null,
                null,
                null,
                null,
                null,
                ETH_HASH);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .ethereumHash(ETH_HASH)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordNoOutput);

        final var actualRecordWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, output);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .ethereumHash(ETH_HASH)
                        .contractCreateResult(FUNCTION_RESULT)
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .contractID(CONTRACT_ID)
                                .build())
                        .build(),
                actualRecordWithOutput);
    }

    @Test
    void cryptoTransferUsesSynthResultOutputIfPresent() {
        final var output = TransactionOutput.newBuilder()
                .contractCall(new CallContractOutput(EVM_TRANSACTION_RESULT))
                .build();
        final var context = new BaseOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, CRYPTO_TRANSFER);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(EXPECTED_BASE_RECORD, actualRecordNoOutput);

        final var actualRecordWithOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, output);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .contractCallResult(FUNCTION_RESULT)
                        .build(),
                actualRecordWithOutput);
    }

    @Test
    void cryptoTransferUsesCustomFeesIfPresent() {
        final var resultWithCustomFees = TRANSACTION_RESULT
                .copyBuilder()
                .assessedCustomFees(ASSESSED_CUSTOM_FEES)
                .build();
        final var context = new BaseOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, CRYPTO_TRANSFER);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(EXPECTED_BASE_RECORD, actualRecordNoOutput);

        final var actualRecordWithOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, resultWithCustomFees, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .assessedCustomFees(ASSESSED_CUSTOM_FEES)
                        .build(),
                actualRecordWithOutput);
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            names = {
                "CRYPTO_CREATE",
                "CRYPTO_UPDATE",
            })
    void certainCryptoOpsUseEvmAddressFromContext(@NonNull final HederaFunctionality function) {
        final var context =
                new CryptoOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, function, ACCOUNT_ID, EVM_ADDRESS);
        final var actualRecord = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .receipt(EXPECTED_BASE_RECEIPT
                                .copyBuilder()
                                .accountID(ACCOUNT_ID)
                                .build())
                        .evmAddress(EVM_ADDRESS)
                        .build(),
                actualRecord);
    }

    @Test
    void tokenAirdropUsesPendingFromContext() {
        final var context =
                new AirdropOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, TOKEN_AIRDROP, PENDING_AIRDROP_RECORDS);
        final var actualRecord = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(
                EXPECTED_BASE_RECORD
                        .copyBuilder()
                        .newPendingAirdrops(PENDING_AIRDROP_RECORDS)
                        .build(),
                actualRecord);
    }

    @Test
    void utilPrngUsesOutputIfPresent() {
        final var numberOutput = TransactionOutput.newBuilder()
                .utilPrng(UtilPrngOutput.newBuilder().prngNumber(123).build())
                .build();
        final var seedOutput = TransactionOutput.newBuilder()
                .utilPrng(UtilPrngOutput.newBuilder().prngBytes(RUNNING_HASH).build())
                .build();
        final var context = new BaseOpContext(MEMO, RATES, TXN_ID, Transaction.DEFAULT, UTIL_PRNG);

        final var actualRecordNoOutput = BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null);
        assertEquals(EXPECTED_BASE_RECORD, actualRecordNoOutput);

        final var actualRecordWithNumberOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, numberOutput);
        assertEquals(EXPECTED_BASE_RECORD.copyBuilder().prngNumber(123).build(), actualRecordWithNumberOutput);

        final var actualRecordWithSeedOutput =
                BLOCK_ITEMS_TRANSLATOR.translateRecord(context, TRANSACTION_RESULT, null, seedOutput);
        assertEquals(EXPECTED_BASE_RECORD.copyBuilder().prngBytes(RUNNING_HASH).build(), actualRecordWithSeedOutput);
    }
}
