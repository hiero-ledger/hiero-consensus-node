// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessagereply;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Executes node-internal packed delivery of an inbound CLPR response payload.
 */
public class ClprQueueDeliverInboundMessageReplyCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(ClprQueueDeliverInboundMessageReplyCall.class);
    private static final int SUPPORTED_ROUTE_VERSION = 1;

    private static final String HANDLE_MESSAGE_RESPONSE_SIGNATURE =
            "handleMessageResponse((uint64,(bytes),(bytes),(uint8,(uint256,string),(uint256,string),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes))))";
    private static final Function HANDLE_MESSAGE_RESPONSE = new Function(HANDLE_MESSAGE_RESPONSE_SIGNATURE);

    private final byte[] input;
    private final VerificationStrategy verificationStrategy;
    private final AccountID senderId;
    private final AccountsConfig accountsConfig;
    private final long maxGasPerContractCall;

    public ClprQueueDeliverInboundMessageReplyCall(
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
        // TEMP-OBSERVABILITY (delete before production): traces inbound response delivery entry to 0x16e.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_reply_call|stage=execute_start|sender={}",
                senderId);

        if (!accountsConfig.isSuperuser(senderId)) {
            return revertWithReason("CLPR_QUEUE_UNAUTHORIZED_CALLER");
        }

        final byte[] canonicalResponseBytes;
        try {
            canonicalResponseBytes = ClprPackedInputCodec.decodeDeliverInboundMessageReplyPacked(input);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_BAD_CALLDATA");
        }
        // TEMP-OBSERVABILITY (delete before production): traces decoded packed response envelope from bundle handler.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_reply_call|stage=packed_decoded|responseBytes={}",
                canonicalResponseBytes.length);

        final Tuple responseTuple;
        final ClprRouteHeaderCodec.ResponseRouteHeader routeHeader;
        try {
            responseTuple = ClprCanonicalEnvelopeCodec.decodeCanonicalResponseTuple(canonicalResponseBytes);
            routeHeader = ClprRouteHeaderCodec.decodeResponseHeaderFromResponseTuple(responseTuple);
        } catch (final RuntimeException ignore) {
            return revertWithReason("CLPR_QUEUE_BAD_ROUTE_ENVELOPE");
        }
        // TEMP-OBSERVABILITY (delete before production): traces route decode and middleware callback target for
        // response.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_reply_call|stage=route_decoded|routeVersion={}|remoteLedgerId={}|targetMiddleware={}",
                routeHeader.version(),
                Bytes.wrap(routeHeader.remoteLedgerId()).toHexString(),
                routeHeader.targetMiddleware());

        if (routeHeader.version() != SUPPORTED_ROUTE_VERSION) {
            return revertWithReason("CLPR_QUEUE_UNSUPPORTED_ROUTE_VERSION");
        }
        if (routeHeader.remoteLedgerId().length == 0 || isAllZero(routeHeader.remoteLedgerId())) {
            return revertWithReason("CLPR_QUEUE_INVALID_REMOTE_LEDGER_ID");
        }

        final var callbackCallData = toArray(HANDLE_MESSAGE_RESPONSE.encodeCall(Tuple.singleton(responseTuple)));
        final var callbackBody = TransactionBody.newBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(asContractId(
                                nativeOperations().entityIdFactory(),
                                fromHeadlongAddress(routeHeader.targetMiddleware())))
                        .gas(maxGasPerContractCall)
                        .functionParameters(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(callbackCallData))
                        .build())
                .build();

        final var callbackBuilder = systemContractOperations()
                .dispatch(callbackBody, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        // TEMP-OBSERVABILITY (delete before production): traces middleware callback dispatch result for inbound
        // response.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_reply_call|stage=middleware_callback_result|status={}",
                callbackBuilder.status());
        if (callbackBuilder.status() != SUCCESS) {
            return revertWithReason(callbackBuilder.status().protoName());
        }

        // TEMP-OBSERVABILITY (delete before production): confirms successful inbound response delivery to middleware.
        log.info("CLPR_OBS|component=clpr_queue_deliver_inbound_message_reply_call|stage=execute_success");
        return gasOnly(successResult(Bytes.EMPTY, gasCalculator.viewGasRequirement()), SUCCESS, isViewCall);
    }

    private PricedResult revertWithReason(@NonNull final String reason) {
        // TEMP-OBSERVABILITY (delete before production): traces typed failure reason during inbound response delivery.
        log.info(
                "CLPR_OBS|component=clpr_queue_deliver_inbound_message_reply_call|stage=execute_revert|reason={}",
                requireNonNull(reason));
        return gasOnly(
                revertResult(Bytes.wrap(reason.getBytes(UTF_8)), gasCalculator.viewGasRequirement()),
                CONTRACT_REVERT_EXECUTED,
                isViewCall);
    }
}
