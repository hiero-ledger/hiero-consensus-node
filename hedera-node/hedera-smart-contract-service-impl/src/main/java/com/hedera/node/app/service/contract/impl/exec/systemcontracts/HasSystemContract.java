// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractNativeSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

@Singleton
public class HasSystemContract extends AbstractNativeSystemContract implements HederaSystemContract {
    public static final String HAS_SYSTEM_CONTRACT_NAME = "HAS";
    public static final String HAS_EVM_ADDRESS = "0x16a";
    // The system contract ID always uses shard 0 and realm 0 so we cannot use ConversionUtils methods for this
    public static final ContractID HAS_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(HAS_EVM_ADDRESS)))
            .build();

    /**
     * A set of call data prefixes (i.e. function selectors in the realm of Solidity)
     * that are eligible for proxy redirection to the Hedera Account Service system contract.
     */
    private static final Set<Integer> HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES = Set.of(
            0xbbee989e, // hbarAllowance(address spender)
            0x86aff07c, // hbarApprove(address spender, int256 amount)
            0xf5677e99); // setUnlimitedAutomaticAssociations(bool enableAutoAssociations)

    /**
     * The {@code IHRC632} staking facade selectors of HIP-1522, eligible for proxy redirection only while
     * {@code contracts.systemContract.accountService.stakingEnabled} is on. The redirect takes priority over an
     * EIP-7702 code delegation, and several of these names ({@code unstake()}, {@code stakeToNode(int64)},
     * {@code setDeclineReward(bool)}) are ordinary DeFi vocabulary; so with the feature off, calldata carrying
     * them must still reach a delegated account's own code exactly as it did before the feature existed.
     */
    private static final Set<Integer> HAS_STAKING_PROXY_ELIGIBLE_CALL_DATA_PREFIXES = Set.of(
            0x5fbd84d5, // stakeToNode(int64 nodeId)
            0xa69431fe, // stakeToAccount(address account)
            0x2def6620, // unstake()
            0x293d496f, // setDeclineReward(bool decline)
            0xfad3a941, // stakeToNodeAndDeclineReward(int64 nodeId, bool decline)
            0xb40cd21d); // getStakingInfo()

    /**
     * Returns whether the given payload is eligible for proxy redirection to the Hedera Account Service.
     *
     * @param payload the call data
     * @param stakingEnabled whether the HIP-1522 staking functions are enabled; consulted only for their selectors
     * @return whether the payload is eligible for the redirect
     */
    public static boolean isPayloadEligibleForHasProxyRedirect(
            @NonNull final Bytes payload, @NonNull final BooleanSupplier stakingEnabled) {
        final int prefix = payload.size() >= FUNCTION_SELECTOR_LENGTH ? payload.getInt(0) : 0;
        return HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES.contains(prefix)
                || (HAS_STAKING_PROXY_ELIGIBLE_CALL_DATA_PREFIXES.contains(prefix) && stakingEnabled.getAsBoolean());
    }

    @Inject
    public HasSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final HasCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(HAS_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }

    @Override
    public void computeFully(
            @NonNull ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final MessageFrame frame,
            @NonNull final Consumer<FullResult> completion) {
        requireNonNull(input);
        requireNonNull(frame);

        // Check if calls to hedera account service is enabled
        if (!contractsConfigOf(frame).systemContractAccountServiceEnabled()) {
            completion.accept(haltResult(NOT_SUPPORTED, frame.getRemainingGas()));
            return;
        }

        super.computeFully(contractID, input, frame, completion);
    }
}
