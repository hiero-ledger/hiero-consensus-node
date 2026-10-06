// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Cross-Ledger Protocol Router (CLPR) service.
 */
@Singleton
public class ClprSystemContract extends AbstractClprSystemContract implements HederaSystemContract {
    public static final String CLPR_SYSTEM_CONTRACT_NAME = "CLPR";

    @Inject
    public ClprSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ClprCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(CLPR_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }
}
