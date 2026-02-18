// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessage.ClprQueueDeliverInboundMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessagereply.ClprQueueDeliverInboundMessageReplyTranslator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.ClprHandleMessagePayloadTransactionBody;

/**
 * Handles internal-only delivery of a single CLPR payload to local middleware via the CLPR queue system contract.
 */
@Singleton
public class ClprMessagePayloadHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(ClprMessagePayloadHandler.class);
    private static final long CLPR_QUEUE_SYSTEM_CONTRACT_NUM = 0x16eL;
    private static final int LEDGER_ID_LENGTH = 32;
    private static final int INBOUND_MESSAGE_ID_LENGTH = Long.BYTES;
    private static final int LENGTH_PREFIX_SIZE = Integer.BYTES;

    @Inject
    public ClprMessagePayloadHandler() {
        // Dagger2
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprHandleMessagePayloadOrThrow();
        validateOpPreCheck(op);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        validateTruePreCheck(clprEnabled(context), NOT_SUPPORTED);
        validateFalsePreCheck(context.isUserTransaction(), NOT_SUPPORTED);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        validateTrue(clprEnabled(context), NOT_SUPPORTED);

        final var op = context.body().clprHandleMessagePayloadOrThrow();
        validateOpHandle(op);

        final byte[] callData;
        final var payload = op.payloadOrThrow();
        // TEMP-OBSERVABILITY (delete before production): traces internal payload-handler invocation and payload kind.
        log.info(
                "CLPR_OBS|component=clpr_message_payload_handler|stage=handle_start|sourceLedgerId={}|inboundMessageId={}|payloadType={}",
                op.sourceLedgerIdOrThrow().ledgerId(),
                op.inboundMessageId(),
                payload.hasMessage() ? "message" : "messageReply");
        if (payload.hasMessage()) {
            callData = encodeDeliverInboundMessagePacked(
                    op.sourceLedgerIdOrThrow().ledgerId().toByteArray(),
                    op.inboundMessageId(),
                    payload.messageOrThrow().messageData().toByteArray());
        } else {
            callData = encodeDeliverInboundMessageReplyPacked(
                    payload.messageReplyOrThrow().messageReplyData().toByteArray());
        }

        // TEMP-OBSERVABILITY (delete before production): traces synthetic 0x16e contract-call dispatch from payload
        // handler.
        log.info(
                "CLPR_OBS|component=clpr_message_payload_handler|stage=dispatch_contract_call|callDataBytes={}",
                callData.length);
        final var streamBuilder = context.dispatch(stepDispatch(
                context.payer(),
                TransactionBody.newBuilder()
                        .contractCall(ContractCallTransactionBody.newBuilder()
                                .contractID(clprQueueContractId(context))
                                .gas(maxGasPerContractCall(context))
                                .functionParameters(Bytes.wrap(callData))
                                .build())
                        .build(),
                StreamBuilder.class,
                NOOP_SIGNED_TX_CUSTOMIZER));
        // TEMP-OBSERVABILITY (delete before production): traces 0x16e contract-call dispatch result from payload
        // handler.
        log.info(
                "CLPR_OBS|component=clpr_message_payload_handler|stage=dispatch_result|status={}",
                streamBuilder.status());
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
        // TEMP-OBSERVABILITY (delete before production): confirms payload handler completion.
        log.info("CLPR_OBS|component=clpr_message_payload_handler|stage=handle_success");
    }

    @Override
    @NonNull
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        return Fees.FREE;
    }

    private static void validateOpPreCheck(@NonNull final ClprHandleMessagePayloadTransactionBody op)
            throws PreCheckException {
        requireNonNull(op);
        validateTruePreCheck(op.hasSourceLedgerId(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasPayload(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.inboundMessageId() > 0, INVALID_TRANSACTION_BODY);

        final var sourceLedgerId = op.sourceLedgerIdOrThrow().ledgerId();
        validateTruePreCheck(sourceLedgerId.length() == LEDGER_ID_LENGTH, CLPR_INVALID_LEDGER_ID);

        final var payload = op.payloadOrThrow();
        validateTruePreCheck(payload.hasMessage() || payload.hasMessageReply(), INVALID_TRANSACTION_BODY);
    }

    private static void validateOpHandle(@NonNull final ClprHandleMessagePayloadTransactionBody op)
            throws HandleException {
        requireNonNull(op);
        validateTrue(op.hasSourceLedgerId(), INVALID_TRANSACTION_BODY);
        validateTrue(op.hasPayload(), INVALID_TRANSACTION_BODY);
        validateTrue(op.inboundMessageId() > 0, INVALID_TRANSACTION_BODY);

        final var sourceLedgerId = op.sourceLedgerIdOrThrow().ledgerId();
        validateTrue(sourceLedgerId.length() == LEDGER_ID_LENGTH, CLPR_INVALID_LEDGER_ID);

        final var payload = op.payloadOrThrow();
        validateTrue(payload.hasMessage() || payload.hasMessageReply(), INVALID_TRANSACTION_BODY);
    }

    private static byte[] encodeDeliverInboundMessagePacked(
            @NonNull final byte[] sourceLedgerId, final long inboundMessageId, @NonNull final byte[] messageData) {
        final var selector = ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED.selector();
        final var packed = ByteBuffer.allocate(selector.length
                + LEDGER_ID_LENGTH
                + INBOUND_MESSAGE_ID_LENGTH
                + LENGTH_PREFIX_SIZE
                + messageData.length);
        packed.put(selector);
        packed.put(sourceLedgerId);
        packed.putLong(inboundMessageId);
        packed.putInt(messageData.length);
        packed.put(messageData);
        return packed.array();
    }

    private static byte[] encodeDeliverInboundMessageReplyPacked(@NonNull final byte[] replyData) {
        final var selector =
                ClprQueueDeliverInboundMessageReplyTranslator.DELIVER_INBOUND_MESSAGE_REPLY_PACKED.selector();
        final var packed = ByteBuffer.allocate(selector.length + LENGTH_PREFIX_SIZE + replyData.length);
        packed.put(selector);
        packed.putInt(replyData.length);
        packed.put(replyData);
        return packed.array();
    }

    private static long maxGasPerContractCall(@NonNull final HandleContext context) {
        return context.configuration().getConfigData(ContractsConfig.class).maxGasPerTransaction();
    }

    private static ContractID clprQueueContractId(@NonNull final HandleContext context) {
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);
        return ContractID.newBuilder()
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .contractNum(CLPR_QUEUE_SYSTEM_CONTRACT_NUM)
                .build();
    }

    private static boolean clprEnabled(@NonNull final PreHandleContext context) {
        return context.configuration().getConfigData(ClprConfig.class).clprEnabled();
    }

    private static boolean clprEnabled(@NonNull final HandleContext context) {
        return context.configuration().getConfigData(ClprConfig.class).clprEnabled();
    }
}
