// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getchannel.GetChannelTranslator.GET_CHANNEL_QUEUE_STATE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator.GET_ENDPOINT_MANIFEST;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyBundleTranslator.VERIFY_BUNDLE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyConfigTranslator.VERIFY_CONFIG;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;

/** Pricing shared by the CLPR router and the system contract verifiers. */
public final class ClprCallPricing {
    private static final int SELECTOR_LENGTH = 4;
    private static final long ONE_CENT_IN_TINYCENTS = 100_000_000L;

    private ClprCallPricing() {}

    /**
     * Returns the target price for a CLPR read or verifier call, or zero if no target applies.
     * The peer-specific verifiers use the same selectors as the router's built-in Hiero verifier.
     */
    public static long readPriceInTinycents(@NonNull final Bytes input, final boolean isRouter) {
        final long verifierPrice = verifierPriceInTinycents(input);
        if (verifierPrice != 0) {
            return verifierPrice;
        }
        if (isRouter
                && (hasSelector(input, GET_CHANNEL_QUEUE_STATE.selector())
                        || hasSelector(input, GET_ENDPOINT_MANIFEST.selector()))) {
            return 2 * ONE_CENT_IN_TINYCENTS;
        }
        return 0;
    }

    /** Target price for the verification selectors shared across all native CLPR verifiers. */
    public static long verifierPriceInTinycents(@NonNull final Bytes input) {
        if (hasSelector(input, VERIFY_CONFIG.selector())) {
            return ONE_CENT_IN_TINYCENTS;
        }
        return hasSelector(input, VERIFY_BUNDLE.selector()) ? 2 * ONE_CENT_IN_TINYCENTS : 0;
    }

    /**
     * Converts a CLPR price to gas at the network price carried by the frame, without a dispatch premium.
     * Already charged authorization gas contributes toward the target, while native work retains its own floor.
     * Zero-price gas configurations retain the native-work requirement and use ordinary gas fee handling.
     */
    public static long gasRequirement(
            @NonNull final MessageFrame frame,
            @NonNull final HederaOperations operations,
            final long targetInTinycents,
            final long minimumNativeGas,
            final long alreadyChargedGas) {
        final var gasPriceValue = frame.getGasPrice();
        if (targetInTinycents <= 0 || gasPriceValue.isZero()) {
            return minimumNativeGas;
        }
        final long target;
        try {
            target = operations.valueInTinybars(targetInTinycents);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
        if (target < 0) {
            return Long.MAX_VALUE;
        }
        if (gasPriceValue.compareTo(Wei.of(Long.MAX_VALUE)) > 0) {
            final long totalGas = target == 0 ? 0 : 1;
            return Math.max(minimumNativeGas, totalGas - Math.min(totalGas, alreadyChargedGas));
        }
        final long gasPrice = gasPriceValue.toLong();
        // Ceiling division without adding gasPrice to target, which could overflow.
        final long totalGas = target / gasPrice + (target % gasPrice == 0 ? 0 : 1);
        return Math.max(minimumNativeGas, totalGas - Math.min(totalGas, alreadyChargedGas));
    }

    private static boolean hasSelector(@NonNull final Bytes input, @NonNull final byte[] selector) {
        return input.size() >= SELECTOR_LENGTH
                && Arrays.equals(selector, input.slice(0, SELECTOR_LENGTH).toArrayUnsafe());
    }
}
