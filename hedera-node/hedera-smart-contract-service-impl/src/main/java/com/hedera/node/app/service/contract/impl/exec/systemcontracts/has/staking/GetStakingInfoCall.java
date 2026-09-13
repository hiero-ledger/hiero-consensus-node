// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.StakingInfo;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Reads an account's staking state, as specified by
 * <a href="https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1522">HIP-1522</a>.
 *
 * <p>Backs {@code getStakingInfo()} on the {@code IHRC632} facade and {@code getStakingInfo(address)} on
 * {@code IHederaAccountService}. This dispatches nothing and mutates nothing, so it produces no child record and
 * requires no authorization — every field it returns is already public over HAPI and the mirror node.
 *
 * <p>The six fields are exactly those HAPI reports through {@code CryptoGetInfo}, so the two views of an account
 * agree. Because Solidity has no {@code oneof}, the protobuf's {@code staked_id} is flattened into two fields
 * carrying the same sentinels the mutating functions accept: {@code -1} for "no node" and the zero address for
 * "no account".
 */
public class GetStakingInfoCall extends AbstractCall {
    /** The struct an unresolvable account reports: every field at its "not staking" value. */
    private static final Tuple ZEROED_INFO =
            Tuple.of(false, 0L, 0L, 0L, SENTINEL_NODE_ID, asHeadlongAddress(new byte[20]));

    private final SystemContractMethod method;

    @Nullable
    private final AccountID target;

    /**
     * @param attempt the call attempt
     * @param method the declared method, used to encode the return tuple
     * @param target the account to read, or null if the call named one that could not be resolved
     */
    public GetStakingInfoCall(
            @NonNull final HasCallAttempt attempt,
            @NonNull final SystemContractMethod method,
            @Nullable final AccountID target) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), true);
        this.method = requireNonNull(method);
        this.target = target;
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @NonNull
    @Override
    public PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        final var gasRequirement = gasCalculator.viewGasRequirement();

        final var account = target == null ? null : nativeOperations().getAccount(target);
        if (account == null) {
            // Non-reverting, per the HAS convention: the response code carries the failure
            return gasOnly(
                    successResult(encoded(INVALID_ACCOUNT_ID, ZEROED_INFO), gasRequirement), INVALID_ACCOUNT_ID, true);
        }

        final var info = nativeOperations().stakingInfoOf(account);
        return gasOnly(successResult(encoded(SUCCESS, asTuple(info)), gasRequirement), SUCCESS, true);
    }

    private Tuple asTuple(@NonNull final StakingInfo info) {
        return Tuple.of(
                info.declineReward(),
                info.hasStakePeriodStart() ? info.stakePeriodStartOrThrow().seconds() : 0L,
                info.pendingReward(),
                info.stakedToMe(),
                info.hasStakedNodeId() ? info.stakedNodeIdOrThrow() : SENTINEL_NODE_ID,
                stakedAccountAddress(info));
    }

    /**
     * Renders {@code staked_account_id} as the account's priority EVM address — its alias when it has one, and
     * the long-zero address otherwise. That is the form the EVM presents for an account, so the returned value
     * compares equal to {@code msg.sender} for an aliased caller and to {@code address(this)} for a contract
     * created by an Ethereum transaction. Falls back to long-zero if the staked-to account has since gone away.
     */
    private Address stakedAccountAddress(@NonNull final StakingInfo info) {
        if (!info.hasStakedAccountId()) {
            return ZEROED_INFO.get(5);
        }
        final var stakedAccountId = info.stakedAccountIdOrThrow();
        final var stakedAccount = nativeOperations().getAccount(stakedAccountId);
        return stakedAccount != null ? headlongAddressOf(stakedAccount) : headlongAddressOf(stakedAccountId);
    }

    private ByteBuffer encoded(@NonNull final ResponseCodeEnum status, @NonNull final Tuple info) {
        return method.getOutputs().encode(Tuple.of((long) status.protoOrdinal(), info));
    }
}
