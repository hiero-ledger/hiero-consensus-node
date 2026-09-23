// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.STATIC_CALL;
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
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the {@code sendMessage} system contract method that enqueues cross-ledger messages on the CLPR outbound
 * queue.
 *
 * <p>Delegates to {@link ClprServiceApi#sendMessage} for all business logic
 * and state mutations.
 */
public class SendMessageCall extends AbstractCall {

    private static final Logger logger = LogManager.getLogger(SendMessageCall.class);
    private static final long GAS_REQUIREMENT = 100_000L;
    private static final long AUTHORIZE_OUTBOUND_MESSAGE_GAS_LIMIT = 50_000L;
    private static final DispatchMetadata CLPR_DISPATCH_METADATA = new DispatchMetadata(Map.of(
            CLPR_DISPATCH,
            new ClprDispatchMetadata(CLPR_SERVICE_ACCOUNT_ID, CLPR_EVM_ADDRESS_BYTES),
            STATIC_CALL,
            Boolean.TRUE));

    static final byte[] AUTHORIZE_OUTBOUND_MESSAGE_SELECTOR;

    static {
        AUTHORIZE_OUTBOUND_MESSAGE_SELECTOR = Arrays.copyOf(
                MiscCryptoUtils.keccak256DigestOf(
                        "authorizeOutboundMessage(bytes32,bytes,bytes,bytes)".getBytes(StandardCharsets.UTF_8)),
                4);
    }

    private final AccountID senderId;
    private final Address senderAddress;
    private final byte[] channelId;
    private final byte[] connectorId;
    private final byte[] targetApplication;
    private final byte[] messageData;

    public SendMessageCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final AccountID senderId,
            @NonNull final Address senderAddress,
            @NonNull final byte[] channelId,
            @NonNull final byte[] connectorId,
            @NonNull final byte[] targetApplication,
            @NonNull final byte[] messageData) {
        super(gasCalculator, enhancement, false);
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

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        logger.info(
                "[CLPR-DEBUG] SendMessageCall.execute: senderId={} senderAddress={} channelId={} connectorId={} targetApplication={} messageData.len={}",
                senderId,
                senderAddress,
                Bytes.wrap(channelId),
                Bytes.wrap(connectorId),
                Bytes.wrap(targetApplication),
                messageData.length);
        final var nativeOps = nativeOperations();
        final var storeFactory = nativeOps.storeFactory();

        // Step 1: Look up the Connector to obtain its authorization contract address.
        final var connectorStore = storeFactory.readableStore(ReadableConnectorStore.class);
        final var connectorKey = new ClprConnectorKey(Bytes.wrap(channelId), Bytes.wrap(connectorId));
        final var connector = connectorStore.getConnector(connectorKey);
        if (connector == null || !connector.hasConnectorContract()) {
            logger.warn(
                    "[CLPR-DEBUG] SendMessageCall: connector lookup FAILED. connector={} hasContract={}",
                    connector,
                    connector != null && connector.hasConnectorContract());
            return gasOnly(
                    ordinalRevertResult(CLPR_AUTHORIZATION_FAILED, GAS_REQUIREMENT), CLPR_AUTHORIZATION_FAILED, false);
        }
        logger.info(
                "[CLPR-DEBUG] SendMessageCall: connector found. authContract={} lockedStake={} slashCount={} inFlight={}",
                connector.connectorContract(),
                connector.lockedStake(),
                connector.slashCount(),
                connector.inFlightMessageCount());

        // Step 2: Per-message authorization — static sub-call to IClprConnector.authorizeOutboundMessage.
        // A revert or false return blocks the send.
        final var callData = encodeAuthorizeOutboundMessage(
                channelId, targetApplication, senderAddress.getBytes().toArray(), messageData);
        logger.info(
                "[CLPR-DEBUG] SendMessageCall: dispatching authorizeOutboundMessage static call to {} with gas={} callData.len={}",
                connector.connectorContractOrThrow(),
                AUTHORIZE_OUTBOUND_MESSAGE_GAS_LIMIT,
                callData.length);
        final var systemAdminAccountId = nativeOps
                .entityIdFactory()
                .newAccountId(nativeOps
                        .configuration()
                        .getConfigData(AccountsConfig.class)
                        .systemAdmin());
        final var authResult = nativeOps.dispatchReadonlyContractCall(
                systemAdminAccountId,
                connector.connectorContractOrThrow(),
                callData,
                AUTHORIZE_OUTBOUND_MESSAGE_GAS_LIMIT,
                CLPR_DISPATCH_METADATA);
        logger.info(
                "[CLPR-DEBUG] SendMessageCall: authorizeOutboundMessage result: authResult={} (null={}, len={}) decoded={}",
                authResult,
                authResult == null,
                authResult == null ? -1 : authResult.length(),
                authResult != null && decodeBoolResult(authResult));
        if (authResult == null || !decodeBoolResult(authResult)) {
            logger.warn("[CLPR-DEBUG] SendMessageCall: AUTHORIZATION_FAILED - returning revert");
            return gasOnly(
                    ordinalRevertResult(CLPR_AUTHORIZATION_FAILED, GAS_REQUIREMENT), CLPR_AUTHORIZATION_FAILED, false);
        }

        // Step 3: Enqueue the message via the CLPR service API.
        final var clprApi = storeFactory.serviceApi(ClprServiceApi.class);
        try {
            logger.info("[CLPR-DEBUG] SendMessageCall: invoking ClprServiceApi.sendMessage");
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
                            GAS_REQUIREMENT),
                    SUCCESS,
                    false);
        } catch (final HandleException e) {
            logger.warn(
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
