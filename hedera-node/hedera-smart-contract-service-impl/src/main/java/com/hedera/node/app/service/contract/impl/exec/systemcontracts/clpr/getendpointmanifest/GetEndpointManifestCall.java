// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code getEndpointManifest() returns (bytes)}.
 * Returns the PBJ-serialized {@link ClprEndpointManifest} singleton bytes (spec §6.5).
 * Public read: any caller may retrieve the local ledger's endpoint manifest to build
 * {@code endpoint_manifest_proof_bytes} for channel completion or manifest-recovery bundles.
 */
public class GetEndpointManifestCall extends AbstractCall {
    private static final long GAS_REQUIREMENT = 5_000L;

    public GetEndpointManifestCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator) {
        super(gasCalculator, enhancement, true);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        final var manifest = nativeOperations().readableEndpointManifestStore().get();
        final byte[] manifestBytes =
                ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        return gasOnly(
                successResult(
                        GetEndpointManifestTranslator.GET_ENDPOINT_MANIFEST
                                .getOutputs()
                                .encode(Tuple.singleton(manifestBytes)),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }
}
