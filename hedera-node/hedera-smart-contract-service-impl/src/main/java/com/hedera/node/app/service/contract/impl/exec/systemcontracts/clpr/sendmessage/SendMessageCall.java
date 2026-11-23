// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_STATE_BYTE_FEE_TINYCENTS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallPricing.gasRequirement;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ClprServiceApi;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.infra.ContractCodeCache;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the {@code sendMessage} system contract method that enqueues cross-ledger messages on the CLPR outbound
 * queue.
 *
 * <p>Delegates to {@link ClprServiceApi#sendMessage} for all business logic
 * and state mutations.
 *
 * <p>A call is priced at {@link ClprConfig#sendMessageFeeTinycents()}, plus the CLPR state-byte rate for each
 * message data byte beyond {@link ClprConfig#sendMessageIncludedBytes()}. Successful sends pay this price entirely
 * through gas, including gas already consumed by connector authorization. No attached value is required.
 */
public class SendMessageCall extends AbstractCall {

    private static final Logger logger = LogManager.getLogger(SendMessageCall.class);
    /**
     * The CLPR fee proposal's gas estimate for native {@code sendMessage} work. EVM authorization
     * gas is tracked separately from this estimate.
     */
    private static final long GAS_REQUIREMENT = 127_520L;

    private static final long AUTHORIZE_OUTBOUND_MESSAGE_GAS_LIMIT = 50_000L;
    private static final int MAX_STACK_DEPTH = 1024;

    static final byte[] AUTHORIZE_OUTBOUND_MESSAGE_SELECTOR;

    static {
        AUTHORIZE_OUTBOUND_MESSAGE_SELECTOR = Arrays.copyOf(
                MiscCryptoUtils.keccak256DigestOf(
                        "authorizeOutboundMessage(bytes32,bytes,bytes,bytes)".getBytes(StandardCharsets.UTF_8)),
                4);
    }

    private final ContractCodeCache codeCache;
    private final AccountID senderId;
    private final Address senderAddress;
    private final byte[] channelId;
    private final byte[] connectorId;
    private final byte[] targetApplication;
    private final byte[] messageData;
    private boolean authorized;
    private long authorizationGasUsed;

    public SendMessageCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final ContractCodeCache codeCache,
            @NonNull final AccountID senderId,
            @NonNull final Address senderAddress,
            @NonNull final byte[] channelId,
            @NonNull final byte[] connectorId,
            @NonNull final byte[] targetApplication,
            @NonNull final byte[] messageData) {
        super(gasCalculator, enhancement, false);
        this.codeCache = requireNonNull(codeCache);
        this.senderId = requireNonNull(senderId);
        this.senderAddress = requireNonNull(senderAddress);
        this.channelId = requireNonNull(channelId);
        this.connectorId = requireNonNull(connectorId);
        this.targetApplication = requireNonNull(targetApplication);
        this.messageData = requireNonNull(messageData);
    }

    @Override
    public boolean allowsStaticFrame() {
        return false;
    }

    /**
     * Schedules the connector's {@code authorizeOutboundMessage} as a child call frame on the sending transaction's
     * own frame stack, with the CLPR system contract as {@code msg.sender}. The child can use up to 50,000
     * of the caller's gas remaining after reserving the native cost. Because the child shares the transaction's context,
     * the connector observes its {@code tx.origin}, transient storage, and warm addresses; and when the sender is an EVM
     * hook, the hook's restrictions (e.g. no {@code DELEGATECALL} or {@code CALLCODE}) also apply to the connector.
     * The connector may update its own state and emit logs; these changes revert if the send or an enclosing call fails.
     */
    @Override
    public boolean scheduleChildFrame(@NonNull final MessageFrame frame, @NonNull final Runnable continuation) {
        requireNonNull(continuation);
        final var connectorStore = nativeOperations().storeFactory().readableStore(ReadableConnectorStore.class);
        final var connectorKey = new ClprConnectorKey(Bytes.wrap(channelId), Bytes.wrap(connectorId));
        final var connector = connectorStore.getConnector(connectorKey);
        if (connector == null || !connector.hasConnectorContract() || frame.getDepth() >= MAX_STACK_DEPTH) {
            return false;
        }
        // Quote the whole send before running application authorization or changing queue state.
        if (frame.getRemainingGas() < pricedGasRequirement(frame, 0)) {
            return false;
        }
        final long gasBudget =
                Math.min(AUTHORIZE_OUTBOUND_MESSAGE_GAS_LIMIT, frame.getRemainingGas() - GAS_REQUIREMENT);
        if (gasBudget <= 0) {
            return false;
        }
        final var contract = proxyUpdaterFor(frame).getHederaAccount(connector.connectorContractOrThrow());
        if (contract == null) {
            return false;
        }
        final var callData = encodeAuthorizeOutboundMessage(
                channelId, targetApplication, senderAddress.getBytes().toArray(), messageData);
        // As with CREATE, build() pushes a child using the parent's world updater and message-frame stack.
        MessageFrame.builder()
                .parentMessageFrame(frame)
                .type(MessageFrame.Type.MESSAGE_CALL)
                .initialGas(gasBudget)
                .address(contract.getAddress())
                .contract(contract.getAddress())
                .inputData(org.apache.tuweni.bytes.Bytes.wrap(callData))
                .sender(pbjToBesuAddress(CLPR_EVM_ADDRESS_BYTES))
                .value(Wei.ZERO)
                .apparentValue(Wei.ZERO)
                .code(codeCache.getCodeFromTuweni(contract.getCode()))
                .isStatic(frame.isStatic())
                .completer(child -> completeAuthorization(frame, child, gasBudget, continuation))
                .build();
        frame.setState(MessageFrame.State.CODE_SUSPENDED);
        return true;
    }

    private void completeAuthorization(
            @NonNull final MessageFrame frame,
            @NonNull final MessageFrame child,
            final long gasBudget,
            @NonNull final Runnable continuation) {
        authorizationGasUsed = gasBudget - child.getRemainingGas();
        // Charge the child once here. The result's gas requirement is only the native work,
        // which the message processor separately charges and records as system contract work.
        frame.decrementRemainingGas(authorizationGasUsed);
        authorized = child.getState() == MessageFrame.State.COMPLETED_SUCCESS
                && decodeBoolResult(tuweniToPbjBytes(child.getOutputData()));
        // Merge the child's logs as a CALL's completer would; if authorization fails the send reverts, clearing them
        frame.addLogs(child.getLogs());
        frame.setState(MessageFrame.State.CODE_EXECUTING);
        continuation.run();
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        // Only the native work counts toward the ops-duration throttle; the rest of the price is a fee
        final long pricedGas = pricedGasRequirement(frame, authorizationGasUsed);
        if (frame.getRemainingGas() < pricedGas) {
            return gasOnly(
                    haltResult(ExceptionalHaltReason.INSUFFICIENT_GAS, GAS_REQUIREMENT)
                            .pricedAt(pricedGas),
                    INSUFFICIENT_GAS,
                    false);
        }
        if (!authorized) {
            return gasOnly(
                    ordinalRevertResult(CLPR_AUTHORIZATION_FAILED, GAS_REQUIREMENT), CLPR_AUTHORIZATION_FAILED, false);
        }
        final var storeFactory = nativeOperations().storeFactory();
        // The child has completed and committed/reverted its updater before this continuation runs.
        final var clprApi = storeFactory.serviceApi(ClprServiceApi.class);
        try {
            logger.info("[CLPR-DEBUG] SendMessageCall: invoking ClprServiceApi.sendMessage for senderId={}", senderId);
            final var assignedMessageId = clprApi.sendMessage(
                    Bytes.wrap(channelId),
                    Bytes.wrap(connectorId),
                    Bytes.wrap(targetApplication),
                    Bytes.wrap(senderAddress.getBytes().toArray()),
                    Bytes.wrap(messageData));
            logger.info("[CLPR-DEBUG] SendMessageCall: SUCCESS assignedMessageId={}", assignedMessageId);
            return gasOnly(
                    successResult(
                                    SendMessageTranslator.SEND_MESSAGE
                                            .getOutputs()
                                            .encode(Tuple.singleton(BigInteger.valueOf(assignedMessageId))),
                                    GAS_REQUIREMENT)
                            .pricedAt(pricedGas),
                    SUCCESS,
                    false);
        } catch (final HandleException e) {
            logger.debug(
                    "[CLPR-DEBUG] SendMessageCall: HandleException from ClprServiceApi.sendMessage status={}",
                    e.getStatus(),
                    e);
            return gasOnly(ordinalRevertResult(e.getStatus(), GAS_REQUIREMENT), e.getStatus(), false);
        } catch (final RuntimeException e) {
            logger.error(
                    "[CLPR-DEBUG] SendMessageCall: unexpected RuntimeException from ClprServiceApi.sendMessage", e);
            throw e;
        }
    }

    private long pricedGasRequirement(@NonNull final MessageFrame frame, final long chargedGas) {
        final var clprConfig = configOf(frame).getConfigData(ClprConfig.class);
        final long extraBytes = Math.max(0, (long) messageData.length - clprConfig.sendMessageIncludedBytes());
        final long target = clampedAdd(
                clprConfig.sendMessageFeeTinycents(), clampedMultiply(extraBytes, CLPR_STATE_BYTE_FEE_TINYCENTS));
        return gasRequirement(frame, operations(), target, GAS_REQUIREMENT, chargedGas);
    }

    /**
     * ABI-encodes the {@code authorizeOutboundMessage(bytes32,bytes,bytes,bytes)} call.
     * Layout: selector(4) | channel_id(32) | offset_target(32) | offset_sender(32) |
     *         offset_data(32) | len_target(32) | target_padded | len_sender(32) |
     *         sender_padded | len_data(32) | data_padded
     */
    public static byte[] encodeAuthorizeOutboundMessage(
            @NonNull final byte[] channelId,
            @NonNull final byte[] targetApplication,
            @NonNull final byte[] sender,
            @NonNull final byte[] messageData) {
        requireNonNull(channelId);
        requireNonNull(targetApplication);
        requireNonNull(sender);
        requireNonNull(messageData);
        final int targetPadded = padded(targetApplication.length);
        final int senderPadded = padded(sender.length);
        final int dataPadded = padded(messageData.length);

        // Head layout (4 * 32 bytes):
        //   slot 0: bytes32 channelId (static)
        //   slot 1: offset to target     (dynamic bytes)
        //   slot 2: offset to sender     (dynamic bytes)
        //   slot 3: offset to data       (dynamic bytes)
        final int headSize = 4 * 32;
        final int offsetTarget = headSize;
        final int offsetSender = offsetTarget + 32 + targetPadded;
        final int offsetData = offsetSender + 32 + senderPadded;

        final var buf = ByteBuffer.allocate(4 + headSize + 32 + targetPadded + 32 + senderPadded + 32 + dataPadded);
        buf.put(AUTHORIZE_OUTBOUND_MESSAGE_SELECTOR);
        putBytes32(buf, channelId);
        putUint256(buf, offsetTarget);
        putUint256(buf, offsetSender);
        putUint256(buf, offsetData);
        // targetApplication bytes
        putUint256(buf, targetApplication.length);
        buf.put(targetApplication);
        padTo32(buf, targetApplication.length);
        // sender bytes
        putUint256(buf, sender.length);
        buf.put(sender);
        padTo32(buf, sender.length);
        // messageData bytes
        putUint256(buf, messageData.length);
        buf.put(messageData);
        padTo32(buf, messageData.length);
        return buf.array();
    }

    /**
     * Decodes a single {@code bool} return value from a 32-byte ABI word.
     * Returns {@code false} if the output is too short or the last byte is zero.
     */
    public static boolean decodeBoolResult(@NonNull final Bytes output) {
        if (output.length() < 32) {
            return false;
        }
        return output.getByte(31) != 0;
    }

    private static int padded(final int len) {
        return ((len + 31) / 32) * 32;
    }

    private static void putUint256(@NonNull final ByteBuffer buf, final int value) {
        buf.put(new byte[28]);
        buf.putInt(value);
    }

    private static void putBytes32(@NonNull final ByteBuffer buf, @NonNull final byte[] value) {
        if (value.length >= 32) {
            buf.put(value, 0, 32);
        } else {
            buf.put(value);
            buf.put(new byte[32 - value.length]);
        }
    }

    private static void padTo32(@NonNull final ByteBuffer buf, final int dataLen) {
        final int remainder = dataLen % 32;
        if (remainder != 0) {
            buf.put(new byte[32 - remainder]);
        }
    }
}
