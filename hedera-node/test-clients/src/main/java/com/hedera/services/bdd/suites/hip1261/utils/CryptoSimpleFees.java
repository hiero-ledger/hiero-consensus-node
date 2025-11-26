// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.CRYPTO_CREATE_BASE_FEE_TINYCENTS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.CRYPTO_CREATE_INCLUDED_HOOKS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.CRYPTO_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.HOOKS_FEE_TINYCENTS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.KEYS_FEE_TINYCENTS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.NODE_BASE_FEE_TINYCENTS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstants.SIGNATURE_FEE_TINYCENTS;

public class CryptoSimpleFees {

    /** tinycents -> USD */
    public static double tinycentsToUsd(long tinycents) {
        return tinycents / 100_000_000.0 / 100.0;
    }

    /**
     * SimpleFees formula for CryptoCreate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_CREATE_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     *         + HOOKS_FEE * max(0, hooks - includedHooksService)
     * total   = node + network + service
     */
    public static double expectedCryptoCreateUsd(long sigs, long keys, long hooks) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final long nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_TINYCENTS;
        final long nodeFee = NODE_BASE_FEE_TINYCENTS + nodeExtrasFee;

        // ----- network fees -----
        final long networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CRYPTO_CREATE_INCLUDED_KEYS);
        final long hookExtrasService = Math.max(0L, hooks - CRYPTO_CREATE_INCLUDED_HOOKS);
        final long serviceExtrasFee = keyExtrasService * KEYS_FEE_TINYCENTS + hookExtrasService * HOOKS_FEE_TINYCENTS;
        final long serviceFee = CRYPTO_CREATE_BASE_FEE_TINYCENTS + serviceExtrasFee;

        final long totalTinycents = nodeFee + networkFee + serviceFee;
        return tinycentsToUsd(totalTinycents);
    }

    /**
     * Overload when there are no hooks extras.
     */
    public static double expectedCryptoCreateUsd(long sigs, long keys) {
        return expectedCryptoCreateUsd(sigs, keys, 0L);
    }
}
