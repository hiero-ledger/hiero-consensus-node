// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CURRENT_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.NODE_ACCOUNT_ID_TABLE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.UPGRADE_DIR;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.hapi.node.freeze.FreezeType;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Validates the candidate roster produced the network's nodes upon handling a {@link FreezeType#FREEZE_UPGRADE}
 * passes a given {@link Consumer<Roster>} assertion.
 */
public class NodeAccountIdTableValidationOp extends AbstractLifecycleOp {
    private static final Logger log = LogManager.getLogger(NodeAccountIdTableValidationOp.class);

    private static final Duration CANDIDATE_ROSTER_EXPORT_TIMEOUT = Duration.ofSeconds(10);
    private final Consumer<Map<Long, String>> tableValidator;

    public NodeAccountIdTableValidationOp(
            @NonNull final NodeSelector selector, @NonNull final Consumer<Map<Long, String>> tableValidator) {
        super(selector);
        this.tableValidator = Objects.requireNonNull(tableValidator);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull final HapiSpec spec) {
        final var nodeAccountTableDir = node.metadata()
                .workingDirOrThrow()
                .resolve(UPGRADE_DIR)
                .resolve(CURRENT_DIR)
                .resolve(NODE_ACCOUNT_ID_TABLE);
        try {
            conditionFuture(() -> nodeAccountTableDir.toFile().exists())
                    .get(CANDIDATE_ROSTER_EXPORT_TIMEOUT.toMillis(), MILLISECONDS);
        } catch (Exception e) {
            log.error("Unable to locate {} at '{}')", NODE_ACCOUNT_ID_TABLE, nodeAccountTableDir.toAbsolutePath(), e);
            throw new IllegalStateException(e);
        }

        // create map
        Map<Long, String> nodeAccountIdTable = new HashMap<>();
        try (var lines = Files.lines(nodeAccountTableDir)) {
            log.info("Current node ID to account ID table :");
            lines.forEach(line -> {
                log.info("\t" + line);
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    nodeAccountIdTable.put(Long.parseLong(parts[0].trim()), parts[1].trim());
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // validate
        tableValidator.accept(nodeAccountIdTable);
    }
}
