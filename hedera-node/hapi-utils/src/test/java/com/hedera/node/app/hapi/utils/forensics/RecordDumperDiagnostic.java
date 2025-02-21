// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.statusHistograms;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6RecordStreamEntriesIn;

import com.hederahashgraph.api.proto.java.*;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/* "unit test" which dumps all record stream files in a directory, intended to be customized for
  each use.

  To customize:
  * Comment out the `@Disabled` immediately following
  * Set the `recordFilesDirectory` appropriately
  * Adjust `wantThisFunction` and `wantThisStatus` filters to suit you
  * Add more filters of your own if necessary
  * Prints to console
*/
@Disabled("For manual execution only - comment this annotation away to run this diagnostic")
class RecordDumperDiagnostic {

    @Test
    void dumpADirectoryOfRecordStreamFiles() throws IOException {
        final var recordFilesDirectory = "/Users/user/state/2024-06-13";
        final var entries = parseV6RecordStreamEntriesIn(recordFilesDirectory);
        final var histograms = statusHistograms(entries);

        System.out.println(prettyPrint(HederaFunctionality.class, histograms));

        for (final var entry : entries) {
            // Filter away transactions you don't want
            if (!wantThisFunction(entry.function())) continue;
            if (!wantThisStatus(entry.finalStatus())) continue;

            // Dump (or whatever) transactions you're looking for
            dumpTransaction(entry);
        }
    }

    private boolean wantThisFunction(@NonNull final HederaFunctionality function) {
        if (function == HederaFunctionality.EthereumTransaction) return true;
        return false;
    }

    private boolean wantThisStatus(@NonNull final ResponseCodeEnum status) {
        return switch (status) {
            case SUCCESS -> false;
            case FAIL_INVALID -> true;
            default -> false;
        };
    }

    /* Basic dump of a transaction from a record */
    private void dumpTransaction(@NonNull final RecordStreamEntry record) {
        final TransactionID id = record.transactionRecord().getTransactionID();
        System.out.printf(
                "====== %s - %s - %s - body:%n", record.consensusTime(), record.function(), record.finalStatus());
        System.out.println(record.body());
        System.out.printf("++++++ - %s txn record:%n", formatId(id));
        System.out.println(record.txnRecord());
        System.out.println("------");
    }

    /* Pretty-print a map */
    private <K extends Enum<K> & Comparable<K>, V> String prettyPrint(
            @NonNull final Class<K> klass, @NonNull final Map<K, V> map) {
        final var ml = EnumSet.allOf(klass).stream()
                .map(Enum::name)
                .mapToInt(String::length)
                .max()
                .orElseThrow();
        final var fmt = "%%-%ds: %%s".formatted(ml);
        return map.entrySet().stream()
                .sorted(Map.Entry.<K, V>comparingByKey())
                .map(entry -> String.format(fmt, entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("\n"));
    }

    private String formatId(@NonNull final TransactionID id) {
        final var timestamp = id.getTransactionValidStart();
        final var timestampStr = timestamp.getSeconds() + "." + timestamp.getNanos();
        final var account = id.getAccountID();
        final var accountStr = account.getShardNum() + "." + account.getRealmNum() + "." + account.getAccountNum();
        return accountStr + "@" + timestampStr;
    }
}
