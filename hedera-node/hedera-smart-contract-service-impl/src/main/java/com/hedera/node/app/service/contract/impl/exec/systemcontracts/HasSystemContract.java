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

    public static boolean isPayloadEligibleForHasProxyRedirect(Bytes payload) {
        final int prefix = payload.size() >= FUNCTION_SELECTOR_LENGTH ? payload.getInt(0) : 0;
        return HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES.contains(prefix);
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
    public FullResult computeFully(
            @NonNull ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);

        // Check if calls to hedera account service is enabled
        if (!contractsConfigOf(frame).systemContractAccountServiceEnabled()) {
            return haltResult(NOT_SUPPORTED, frame.getRemainingGas());
        }

        return super.computeFully(contractID, input, frame);
    }
}
