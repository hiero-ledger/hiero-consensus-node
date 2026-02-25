// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CANDIDATE_ROSTER_JSON;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.hapi.node.freeze.FreezeType;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Validates the candidate roster produced the network's nodes upon handling a {@link FreezeType#FREEZE_UPGRADE}
 * passes a given {@link Consumer<Roster>} assertion.
 */
public class CandidateRosterValidationOp extends AbstractLifecycleOp {
    private static final Logger log = LogManager.getLogger(CandidateRosterValidationOp.class);

    private static final Duration CANDIDATE_ROSTER_EXPORT_TIMEOUT = Duration.ofSeconds(10);
    private final Consumer<Roster> rosterValidator;

    public CandidateRosterValidationOp(
            @NonNull final NodeSelector selector, @NonNull final Consumer<Roster> rosterValidator) {
        super(selector);
        this.rosterValidator = Objects.requireNonNull(rosterValidator);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull final HapiSpec spec) {
        final Roster candidateRoster;
        final var candidateRosterPath = node.metadata().workingDirOrThrow().resolve(CANDIDATE_ROSTER_JSON);
        try {
            conditionFuture(() -> candidateRosterPath.toFile().exists())
                    .get(CANDIDATE_ROSTER_EXPORT_TIMEOUT.toMillis(), MILLISECONDS);
        } catch (Exception e) {
            log.error("Unable to locate candidate roster at '{}')", candidateRosterPath.toAbsolutePath(), e);
            throw new IllegalStateException(e);
        }
        final var candidateNetwork =
                DiskStartupNetworks.loadNetworkFrom(candidateRosterPath).orElseThrow();
        candidateRoster = RosterUtils.rosterFrom(candidateNetwork);
        rosterValidator.accept(candidateRoster);
    }
}
