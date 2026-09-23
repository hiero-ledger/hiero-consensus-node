// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getchannel;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code getChannelQueueState(bytes32 channelId) returns (uint64, uint64)}.
 * Returns (receivedMessageId, ackedMessageId) for the given channel.
 */
public class GetChannelCall extends AbstractCall {
    private static final long GAS_REQUIREMENT = 5_000L;

    private final byte[] channelId;

    public GetChannelCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] channelId) {
        super(gasCalculator, enhancement, true);
        this.channelId = requireNonNull(channelId);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        final var channelStore = nativeOperations().readableChannelStore();
        final var channel = channelStore.getChannel(Bytes.wrap(channelId));
        if (channel == null) {
            return gasOnly(ordinalRevertResult(CLPR_CHANNEL_NOT_FOUND, GAS_REQUIREMENT), CLPR_CHANNEL_NOT_FOUND, false);
        }
        return gasOnly(
                successResult(
                        GetChannelTranslator.GET_CHANNEL_QUEUE_STATE
                                .getOutputs()
                                .encode(Tuple.of(
                                        BigInteger.valueOf(channel.receivedMessageId()),
                                        BigInteger.valueOf(channel.ackedMessageId()))),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }
}
