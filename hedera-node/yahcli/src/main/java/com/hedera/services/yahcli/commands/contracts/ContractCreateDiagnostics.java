// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.contracts;

import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.yahcli.suites.ContractCreateSuite;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Produces a one-line description of which step in a {@link ContractCreateSuite} failed.
 * The suite chains FileCreate -&gt; FileUpdate/Append -&gt; ContractCreate; this helper inspects
 * each tracked op's precheck and lastReceipt so failures can be diagnosed without re-running
 * at a higher log level.
 */
final class ContractCreateDiagnostics {
    private ContractCreateDiagnostics() {}

    static String describe(final ContractCreateSuite suite) {
        final var sb = new StringBuilder();
        sb.append("[fileCreate=");
        sb.append(opStatus(suite.getFileCreateOp()));
        sb.append(", contractCreate=");
        sb.append(opStatus(suite.getContractCreateOp()));
        sb.append("]");
        return sb.toString();
    }

    private static String opStatus(@Nullable final HapiTxnOp<?> op) {
        if (op == null) {
            return "not-attempted";
        }
        final var precheck = op.getActualPrecheck();
        if (precheck == null) {
            return "not-submitted";
        }
        final String status = op.getLastReceipt() != null ? op.getActualStatus().toString() : "n/a";
        return "precheck=" + precheck + ",status=" + status;
    }
}
