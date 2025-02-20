// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class DeleteNodeSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(DeleteNodeSuite.class);

    private final Map<String, String> specConfig;
    private final long nodeId;

    @Nullable
    private final String adminKeyLoc;

    public DeleteNodeSuite(
            @NonNull final Map<String, String> specConfig, final long nodeId, @Nullable final String adminKeyLoc) {
        this.specConfig = specConfig;
        this.nodeId = nodeId;
        this.adminKeyLoc = adminKeyLoc;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doDelete());
    }

    final Stream<DynamicTest> doDelete() {
        final var adminKey = "adminKey";
        return HapiSpec.customHapiSpec("DeleteNode")
                .withProperties(specConfig)
                .given(adminKeyLoc == null ? noOp() : keyFromFile(adminKey, adminKeyLoc))
                .when()
                .then(nodeDelete("" + nodeId).signedBy(availableSigners()));
    }

    private String[] availableSigners() {
        if (adminKeyLoc == null) {
            return new String[] {DEFAULT_PAYER};
        } else {
            return new String[] {DEFAULT_PAYER, "adminKey"};
        }
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
