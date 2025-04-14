// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;

import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Provides feature flags used to customize behavior of Hedera {@link org.hyperledger.besu.evm.operation.Operation} overrides.
 */
public interface FeatureFlags {
    /**
     * Whether the {@code CREATE2} operation should be enabled for the given {@code frame}.
     *
     * @param frame the {@link MessageFrame} to check
     * @return whether {@code CREATE2} should be enabled
     */
    boolean isCreate2Enabled(@NonNull MessageFrame frame);

    /**
     * Whether the sidecar of a given type is enabled.
     *
     * @param frame the {@link MessageFrame} to check
     * @param sidecarType the type of the sidecar
     * @return whether the given sidecar type is enabled
     */
    default boolean isSidecarEnabled(@NonNull MessageFrame frame, @NonNull SidecarType sidecarType) {
        return contractsConfigOf(frame).sidecars().contains(sidecarType);
    }

    /**
     * Whether "implicit creation" of accounts via sending value or targeting a {@code CREATE2} to an EIP-1014 address
     * should be enabled for the given {@code frame}.
     *
     * @return whether implicit creation should be enabled
     */
    boolean isImplicitCreationEnabled();

    /**
     * If true calls to non-existing contract addresses will result in a successful NOOP.  If false,
     * calls such calls will result in a revert with status {@code INVALID_SOLIDITY_ADDRESS}.
     * @param config the contract configuration for the transaction
     * @param possiblyGrandFatheredEntityNum the account number to check for grandfathering
     * @return true if calls to non-existing contract addresses will result in a successful NOOP.
     */
    default boolean isAllowCallsToNonContractAccountsEnabled(
            @NonNull final ContractsConfig config, @Nullable Long possiblyGrandFatheredEntityNum) {
        return false;
    }

    /**
     *  If true, charge intrinsic gas for calls that fail with a pre-EVM exception.
     * @param config the active configuration
     * @return true whether to  charge intrinsic gas for calls that fail with a pre-EVM exception.
     */
    default boolean isChargeGasOnPreEvmException(@NonNull Configuration config) {
        return false;
    }

    /**
     *  If true, enable calls to the Hedera Account Service system contract
     * @param config the active configuration
     * @return true whether calls to Hedera Account Service system contract are enabled.
     */
    default boolean isHederaAccountServiceEnabled(@NonNull Configuration config) {
        return false;
    }

    /**
     * @param config the active configuration
     * @return true whether authorized raw method is enabled.
     */
    default boolean isAuthorizedRawMethodEnabled(@NonNull Configuration config) {
        return false;
    }
}
