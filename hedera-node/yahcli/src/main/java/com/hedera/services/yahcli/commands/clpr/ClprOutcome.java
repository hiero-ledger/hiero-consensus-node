// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;

/**
 * Common success/failure reporting for CLPR yahcli commands. Surfaces the
 * underlying precheck and handler response codes so failures are diagnosable
 * without re-running at a higher log level.
 */
final class ClprOutcome {
    private ClprOutcome() {}

    /**
     * Reports a transaction outcome. Returns {@code 0} on success, {@code 1} on failure.
     *
     * @param config        the yahcli config (used for output)
     * @param suite         the suite that was just executed
     * @param op            the transaction op the suite wrapped
     * @param successAction short human-readable description for the success message
     * @param failureAction short human-readable description for the failure message
     */
    static int reportTxn(
            final ConfigManager config,
            final HapiSuite suite,
            final HapiTxnOp<?> op,
            final String successAction,
            final String failureAction) {
        final var spec = suite.getFinalSpecs().getFirst();
        final var precheck = op.getActualPrecheck();
        // getActualStatus() reads lastReceipt; if the txn never made it to consensus
        // (precheck rejected, network unreachable, etc.) the receipt is null. Guard
        // against that so the failure cause is reported rather than swallowed by an NPE.
        final String status = op.getLastReceipt() != null ? op.getActualStatus().toString() : "n/a";
        if (spec.getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - " + successAction + " (precheck=" + precheck + ", status=" + status + ")");
            return 0;
        }
        final var msg = "FAILED - " + failureAction + " (precheck=" + precheck + ", status=" + status + ")";
        config.output().warn(msg);
        return 1;
    }
}
