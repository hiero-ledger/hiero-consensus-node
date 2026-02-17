// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessage;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec.isAllZero;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec.toArray;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprCanonicalEnvelopeCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprRouteHeaderCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.hapi.interledger.clpr.ClprEnqueueMessageTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageReply;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Executes node-internal packed delivery of an inbound CLPR request payload.
 */
public class ClprQueueDeliverInboundMessageCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(ClprQueueDeliverInboundMessageCall.class);

    private static final int SUPPORTED_ROUTE_VERSION = 1;

    private static final String HANDLE_MESSAGE_SIGNATURE =
            "handleMessage((address,(address,bytes32,(uint256,string),bytes),bytes32,(bool,(uint256,string),bytes),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes)),uint64)";
    private static final Function HANDLE_MESSAGE = new Function(
            HANDLE_MESSAGE_SIGNATURE,
            "((uint64,(bytes),(bytes),(uint8,(uint256,string),(uint256,string),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes))))");

    private final byte[] input;
    private final VerificationStrategy verificationStrategy;
    private final AccountID senderId;
    private final AccountsConfig accountsConfig;
    private final long maxGasPerContractCall;

    public ClprQueueDeliverInboundMessageCall(
            @NonNull final ClprQueueCallAttempt attempt, @NonNull final byte[] input) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.input = requireNonNull(input);
        this.verificationStrategy = requireNonNull(attempt.defaultVerificationStrategy());
        this.senderId = requireNonNull(attempt.senderId());
        this.accountsConfig = requireNonNull(attempt.configuration().getConfigData(AccountsConfig.class));
        final var contractsConfig = requireNonNull(attempt.configuration().getConfigData(ContractsConfig.class));
        this.maxGasPerContractCall = contractsConfig.maxGasPerTransaction();
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        // TEMP-OBSERVABILITY (delete before production): traces inbound request delivery entry to 0x16e.
        log.info("CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=execute_start|sender={}", senderId);

        if (!accountsConfig.isSuperuser(senderId)) {
            return revertWithReason("CLPR_QUEUE_UNAUTHORIZED_CALLER");
        }

        final ClprPackedInputCodec.PackedInboundRequest packed;
        try {
            packed = ClprPackedInputCodec.decodeDeliverInboundMessagePacked(input);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_BAD_CALLDATA");
        }
        // TEMP-OBSERVABILITY (delete before production): traces decoded packed request envelope from bundle handler.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=packed_decoded|sourceLedgerId={}|inboundMessageId={}|messageBytes={}",
                Bytes.wrap(packed.sourceLedgerId()).toHexString(),
                packed.inboundMessageId(),
                packed.messageData().length);

        if (packed.inboundMessageId() <= 0) {
            return revertWithReason("CLPR_QUEUE_INVALID_ORIGINAL_MESSAGE_ID");
        }
        if (isAllZero(packed.sourceLedgerId())) {
            return revertWithReason("CLPR_QUEUE_INVALID_REMOTE_LEDGER_ID");
        }

        final Tuple messageTuple;
        final ClprRouteHeaderCodec.RequestRouteHeader routeHeader;
        try {
            messageTuple = ClprCanonicalEnvelopeCodec.decodeCanonicalMessageTuple(packed.messageData());
            routeHeader = ClprRouteHeaderCodec.decodeRequestHeaderFromMessageTuple(messageTuple);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_BAD_ROUTE_ENVELOPE");
        }
        // TEMP-OBSERVABILITY (delete before production): traces route decode and middleware callback target.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=route_decoded|routeVersion={}|remoteLedgerId={}|destinationMiddleware={}",
                routeHeader.version(),
                Bytes.wrap(routeHeader.remoteLedgerId()).toHexString(),
                routeHeader.destinationMiddleware());

        if (routeHeader.version() != SUPPORTED_ROUTE_VERSION) {
            return revertWithReason("CLPR_QUEUE_UNSUPPORTED_ROUTE_VERSION");
        }
        if (routeHeader.remoteLedgerId().length == 0 || isAllZero(routeHeader.remoteLedgerId())) {
            return revertWithReason("CLPR_QUEUE_INVALID_REMOTE_LEDGER_ID");
        }

        final var callbackCallData = toArray(
                HANDLE_MESSAGE.encodeCall(Tuple.of(messageTuple, BigInteger.valueOf(packed.inboundMessageId()))));
        final var callbackBody = TransactionBody.newBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(asContractId(
                                nativeOperations().entityIdFactory(),
                                fromHeadlongAddress(routeHeader.destinationMiddleware())))
                        .gas(maxGasPerContractCall)
                        .functionParameters(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(callbackCallData))
                        .build())
                .build();

        final var callbackBuilder = systemContractOperations()
                .dispatch(callbackBody, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        // TEMP-OBSERVABILITY (delete before production): traces middleware callback dispatch result for inbound
        // request.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=middleware_callback_result|status={}|inboundMessageId={}",
                callbackBuilder.status(),
                packed.inboundMessageId());
        if (callbackBuilder.status() != SUCCESS) {
            return revertWithReason(callbackBuilder.status().protoName());
        }
        if (!callbackBuilder.hasContractResult()) {
            return revertWithReason("CLPR_QUEUE_CALLBACK_MISSING_RESULT");
        }

        final var callbackResultBytes = callbackBuilder.getEvmCallResult().toByteArray();
        if (callbackResultBytes.length == 0) {
            return revertWithReason("CLPR_QUEUE_INVALID_CALLBACK_RESULT");
        }

        final Tuple handleMessageReturn;
        try {
            handleMessageReturn = HANDLE_MESSAGE.decodeReturn(callbackResultBytes);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_INVALID_CALLBACK_RESULT");
        }

        final var responseTuple = (Tuple) handleMessageReturn.get(0);
        final byte[] responseRouteHeader = ClprRouteHeaderCodec.encodeResponseRouteHeader(
                routeHeader.version(), packed.sourceLedgerId(), routeHeader.sourceMiddleware());
        final Tuple patchedResponseTuple;
        try {
            patchedResponseTuple =
                    ClprCanonicalEnvelopeCodec.injectResponseRouteHeader(responseTuple, responseRouteHeader);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_INVALID_CALLBACK_RESULT");
        }
        final byte[] canonicalResponseBytes =
                ClprCanonicalEnvelopeCodec.encodeCanonicalResponseBytes(patchedResponseTuple);

        final var sourceLedgerId = ClprLedgerId.newBuilder()
                .ledgerId(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(packed.sourceLedgerId()))
                .build();
        final var replyPayload = ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(packed.inboundMessageId())
                        .messageReplyData(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(canonicalResponseBytes))
                        .build())
                .build();

        final var queueMetadata =
                nativeOperations().readableClprMessageQueueMetadataStore().get(sourceLedgerId);
        if (queueMetadata == null) {
            return revertWithReason(CLPR_MESSAGE_QUEUE_NOT_AVAILABLE.protoName());
        }

        final long expectedMessageId = queueMetadata.nextMessageId();
        if (expectedMessageId < 1) {
            return revertWithReason(INVALID_TRANSACTION_BODY.protoName());
        }
        // TEMP-OBSERVABILITY (delete before production): traces synthetic enqueue for response payload.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=dispatch_enqueue_response|sourceLedgerId={}|expectedMessageId={}|inboundMessageId={}",
                Bytes.wrap(packed.sourceLedgerId()).toHexString(),
                expectedMessageId,
                packed.inboundMessageId());

        final var enqueueOp = ClprEnqueueMessageTransactionBody.newBuilder()
                .ledgerId(sourceLedgerId)
                .payload(replyPayload)
                .expectedMessageId(expectedMessageId)
                .build();
        final var enqueueBody =
                TransactionBody.newBuilder().clprEnqueueMessage(enqueueOp).build();
        final var enqueueBuilder = systemContractOperations()
                .dispatch(enqueueBody, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        // TEMP-OBSERVABILITY (delete before production): traces synthetic enqueue status for generated response.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=enqueue_result|status={}|assignedMessageId={}",
                enqueueBuilder.status(),
                expectedMessageId);
        if (enqueueBuilder.status() != SUCCESS) {
            return revertWithReason(enqueueBuilder.status().protoName());
        }

        final var output = ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED
                .getOutputs()
                .encode(Tuple.singleton(BigInteger.valueOf(expectedMessageId)));
        // TEMP-OBSERVABILITY (delete before production): confirms successful end-to-end inbound request handling.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=execute_success|responseMessageId={}|inboundMessageId={}",
                expectedMessageId,
                packed.inboundMessageId());
        return gasOnly(successResult(output, gasCalculator.viewGasRequirement()), SUCCESS, isViewCall);
    }

    private PricedResult revertWithReason(@NonNull final String reason) {
        // TEMP-OBSERVABILITY (delete before production): traces typed failure reason during inbound request delivery.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_call|stage=execute_revert|reason={}",
                requireNonNull(reason));
        return gasOnly(
                revertResult(Bytes.wrap(reason.getBytes(UTF_8)), gasCalculator.viewGasRequirement()),
                CONTRACT_REVERT_EXECUTED,
                isViewCall);
    }
}
