// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;

import com.hedera.node.app.service.clpr.impl.verifier.BlsSignatureVerifier;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Selects the {@link BlsSignatureVerifier} for the Ethereum verifier system contract.
 *
 * <p>Until a real BLS12-381 implementation is wired in (see #24542), the only alternative to failing
 * closed is the insecure {@link FakeBlsSignatureVerifier}, which accepts every signature. That stub is
 * returned <b>only</b> when {@code clpr.allowInsecureBlsVerifier=true} is set explicitly (intended for
 * test networks); otherwise, including when the configuration cannot be read, signatures are rejected.
 */
public final class EthereumBlsVerifiers {
    private static final Logger log = LogManager.getLogger(EthereumBlsVerifiers.class);

    private EthereumBlsVerifiers() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns the BLS verifier to use for the given frame.
     *
     * @param frame the current message frame
     * @return the insecure stub if explicitly allowed by configuration, otherwise a fail-closed verifier
     */
    public static @NonNull BlsSignatureVerifier forFrame(@NonNull final MessageFrame frame) {
        final boolean allowInsecure;
        try {
            allowInsecure = configOf(frame).getConfigData(ClprConfig.class).allowInsecureBlsVerifier();
        } catch (final RuntimeException e) {
            return FailClosedBlsSignatureVerifier.INSTANCE;
        }
        if (allowInsecure) {
            log.warn("[EthereumVerifier] clpr.allowInsecureBlsVerifier=true; BLS signatures are NOT verified");
            return FakeBlsSignatureVerifier.INSTANCE;
        }
        return FailClosedBlsSignatureVerifier.INSTANCE;
    }
}
