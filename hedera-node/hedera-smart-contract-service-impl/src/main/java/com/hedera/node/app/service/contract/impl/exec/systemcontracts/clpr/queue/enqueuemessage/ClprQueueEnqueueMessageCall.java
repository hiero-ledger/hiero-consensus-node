// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessage;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec.isAllZero;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec.stripSelector;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprRouteHeaderCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.hapi.interledger.clpr.ClprEnqueueMessageTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Executes {@code enqueueMessage(...)} by adapting calldata into native CLPR queue state updates.
 */
public class ClprQueueEnqueueMessageCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(ClprQueueEnqueueMessageCall.class);
    private static final int SUPPORTED_ROUTE_VERSION = 1;

    private final byte[] callData;
    private final Tuple clprMessage;
    private final VerificationStrategy verificationStrategy;
    private final AccountID senderId;

    public ClprQueueEnqueueMessageCall(
            @NonNull final ClprQueueCallAttempt attempt,
            @NonNull final byte[] callData,
            @NonNull final Tuple clprMessage) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.callData = requireNonNull(callData);
        this.clprMessage = requireNonNull(clprMessage);
        this.verificationStrategy = requireNonNull(attempt.defaultVerificationStrategy());
        this.senderId = requireNonNull(attempt.senderId());
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        // TEMP-OBSERVABILITY (delete before production): traces queue-system-contract entry for CLPR request enqueue.
        log.info("CLPR_OBS|component=clpr_queue_enqueue_message_call|stage=execute_start|sender={}", senderId);
        final ClprRouteHeaderCodec.RequestRouteHeader routeHeader;
        try {
            routeHeader = ClprRouteHeaderCodec.decodeRequestHeaderFromMessageTuple(clprMessage);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_BAD_ROUTE_ENVELOPE");
        }
        // TEMP-OBSERVABILITY (delete before production): traces route decoding before queue lookup/dispatch.
        log.info(
                "CLPR_OBS|component=clpr_queue_enqueue_message_call|stage=route_decoded|remoteLedgerId={}|routeVersion={}",
                Bytes.wrap(routeHeader.remoteLedgerId()).toHexString(),
                routeHeader.version());

        if (routeHeader.version() != SUPPORTED_ROUTE_VERSION) {
            return revertWithReason("CLPR_QUEUE_UNSUPPORTED_ROUTE_VERSION");
        }
        if (routeHeader.remoteLedgerId().length == 0 || isAllZero(routeHeader.remoteLedgerId())) {
            return revertWithReason("CLPR_QUEUE_INVALID_REMOTE_LEDGER_ID");
        }

        final var remoteLedgerId = ClprLedgerId.newBuilder()
                .ledgerId(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(routeHeader.remoteLedgerId()))
                .build();
        final var canonicalMessageData = stripSelector(callData);
        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(canonicalMessageData))
                        .build())
                .build();

        final var queueMetadata =
                nativeOperations().readableClprMessageQueueMetadataStore().get(remoteLedgerId);
        if (queueMetadata == null) {
            return revertWithReason(CLPR_MESSAGE_QUEUE_NOT_AVAILABLE.protoName());
        }
        final long expectedMessageId = queueMetadata.nextMessageId();
        if (expectedMessageId < 1) {
            return revertWithReason(INVALID_TRANSACTION_BODY.protoName());
        }
        // TEMP-OBSERVABILITY (delete before production): traces synthetic enqueue dispatch intent with expected message
        // id.
        log.info(
                "CLPR_OBS|component=clpr_queue_enqueue_message_call|stage=dispatch_enqueue|remoteLedgerId={}|expectedMessageId={}",
                Bytes.wrap(routeHeader.remoteLedgerId()).toHexString(),
                expectedMessageId);

        final var enqueueOp = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(remoteLedgerId)
                .payload(payload)
                .expectedMessageId(expectedMessageId)
                .build();
        final var synthBody =
                TransactionBody.newBuilder().clprEnqueueMessage(enqueueOp).build();
        final var recordBuilder = systemContractOperations()
                .dispatch(synthBody, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        // TEMP-OBSERVABILITY (delete before production): traces synthetic enqueue dispatch completion status.
        log.info(
                "CLPR_OBS|component=clpr_queue_enqueue_message_call|stage=dispatch_result|status={}",
                recordBuilder.status());
        if (recordBuilder.status() != SUCCESS) {
            return revertWithReason(recordBuilder.status().protoName());
        }
        final var output = ClprQueueEnqueueMessageTranslator.ENQUEUE_MESSAGE
                .getOutputs()
                .encode(Tuple.singleton(BigInteger.valueOf(expectedMessageId)));
        // TEMP-OBSERVABILITY (delete before production): confirms successful queue enqueue response to middleware.
        log.info(
                "CLPR_OBS|component=clpr_queue_enqueue_message_call|stage=execute_success|assignedMessageId={}",
                expectedMessageId);
        return gasOnly(successResult(output, gasCalculator.viewGasRequirement()), SUCCESS, isViewCall);
    }

    private PricedResult revertWithReason(@NonNull final String reason) {
        // TEMP-OBSERVABILITY (delete before production): traces typed CLPR queue reverts for demo diagnostics.
        log.info(
                "CLPR_OBS|component=clpr_queue_enqueue_message_call|stage=execute_revert|reason={}",
                requireNonNull(reason));
        return gasOnly(
                revertResult(Bytes.wrap(reason.getBytes(UTF_8)), gasCalculator.viewGasRequirement()),
                CONTRACT_REVERT_EXECUTED,
                isViewCall);
    }
}
