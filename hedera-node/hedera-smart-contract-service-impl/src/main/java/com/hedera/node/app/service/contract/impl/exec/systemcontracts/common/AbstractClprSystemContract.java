// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.failure.HandleExceptionHaltReason;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * Base class for every CLPR-related native system contract.
 *
 * <p>The master {@code clpr.enabled} check lives here so the CLPR router and every peer-ledger
 * verifier halt consistently while CLPR is disabled.
 */
public abstract class AbstractClprSystemContract extends AbstractNativeSystemContract {

    protected AbstractClprSystemContract(
            @NonNull final String name,
            @NonNull final CallFactory callFactory,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractMetrics contractMetrics) {
        super(name, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    public FullResult computeFully(
            @NonNull final ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);
        if (!FrameUtils.configOf(frame).getConfigData(ClprConfig.class).enabled()) {
            return haltResult(new HandleExceptionHaltReason(CLPR_NOT_ENABLED), frame.getRemainingGas());
        }
        return super.computeFully(contractID, input, frame);
    }
}
