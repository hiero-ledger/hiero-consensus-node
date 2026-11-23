// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr;

import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getchannel.GetChannelTranslator.GET_CHANNEL_QUEUE_STATE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator.GET_ENDPOINT_MANIFEST;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageTranslator.SEND_MESSAGE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyBundleTranslator.VERIFY_BUNDLE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyConfigTranslator.VERIFY_CONFIG;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes;
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

    /** Whether a CLPR call can attach value to pay the top-up. */
    public static boolean acceptsValue(@NonNull final Bytes input, final boolean isRouter) {
        return readPriceInTinycents(input, isRouter) != 0 || (isRouter && hasSelector(input, SEND_MESSAGE.selector()));
    }

    /** The portion of the target price still due after gas charged at the frame's transaction gas price. */
    public static long valueTopUp(
            @NonNull final MessageFrame frame,
            @NonNull final HederaOperations operations,
            final long targetInTinycents,
            final long gas) {
        final long target = operations.valueInTinybars(targetInTinycents);
        final long gasCost = clampedMultiply(gas, frame.getGasPrice().toLong());
        return Math.max(0, target - gasCost);
    }

    private static boolean hasSelector(@NonNull final Bytes input, @NonNull final byte[] selector) {
        return input.size() >= SELECTOR_LENGTH
                && Arrays.equals(selector, input.slice(0, SELECTOR_LENGTH).toArrayUnsafe());
    }
}
