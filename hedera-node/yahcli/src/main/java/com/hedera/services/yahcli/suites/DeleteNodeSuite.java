// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class DeleteNodeSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(DeleteNodeSuite.class);

    private final ConfigManager configManager;
    private final long nodeId;

    @Nullable
    private final String adminKeyLoc;

    public DeleteNodeSuite(
            @NonNull final ConfigManager configManager, final long nodeId, @Nullable final String adminKeyLoc) {
        this.configManager = configManager;
        this.nodeId = nodeId;
        this.adminKeyLoc = adminKeyLoc;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doDelete());
    }

    final Stream<DynamicTest> doDelete() {
        final var adminKey = "adminKey";
        final var spec =
                new HapiSpec("NodeDelete", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {
                    adminKeyLoc == null ? noOp() : keyFromFile(adminKey, adminKeyLoc),
                    nodeDelete("" + nodeId).signedBy(availableSigners())
                });
        return HapiSpecUtils.targeted(spec, configManager);
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
