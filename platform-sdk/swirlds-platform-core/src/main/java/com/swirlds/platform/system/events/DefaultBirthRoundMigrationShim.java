// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.state.lifecycle.HapiUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Default implementation of the {@link BirthRoundMigrationShim}.
 */
public class DefaultBirthRoundMigrationShim implements BirthRoundMigrationShim {

    private static final SpeedometerMetric.Config SHIM_ANCIENT_EVENTS = new SpeedometerMetric.Config(
                    "platform", "shimAncientEvents")
            .withDescription("Events that the BirthRoundMigrationShim gave an ancient birth round override")
            .withUnit("hz");

    private final SpeedometerMetric shimAncientEvents;

    private static final SpeedometerMetric.Config SHIM_BARELY_NON_ANCIENT_EVENTS = new SpeedometerMetric.Config(
                    "platform", "shimBarelyNonAncientEvents")
            .withDescription("Events that the BirthRoundMigrationShim gave a barely non-ancient birth round override")
            .withUnit("hz");

    private final SpeedometerMetric shimBarelyNonAncientEvents;

    private static final Logger logger = LogManager.getLogger(DefaultBirthRoundMigrationShim.class);

    /**
     * The first software version where the birth round mode is enabled. Events from this software version and later are
     * not modified by this object. Events from earlier software versions have their birth rounds modified by this
     * object.
     */
    private final SemanticVersion firstVersionInBirthRoundMode;

    /**
     * The last round before the birth round mode was enabled.
     */
    private final long lastRoundBeforeBirthRoundMode;

    /**
     * The lowest judge generation before the birth round mode was enabled.
     */
    private final long lowestJudgeGenerationBeforeBirthRoundMode;

    /**
     * Constructs a new BirthRoundMigrationShim.
     *
     * @param platformContext                           the platform context
     * @param firstVersionInBirthRoundMode              the first software version where the birth round mode is
     *                                                  enabled
     * @param lastRoundBeforeBirthRoundMode             the last round before the birth round mode was enabled
     * @param lowestJudgeGenerationBeforeBirthRoundMode the lowest judge generation before the birth round mode was
     *                                                  enabled
     */
    public DefaultBirthRoundMigrationShim(
            @NonNull final PlatformContext platformContext,
            @NonNull final SemanticVersion firstVersionInBirthRoundMode,
            final long lastRoundBeforeBirthRoundMode,
            final long lowestJudgeGenerationBeforeBirthRoundMode) {

        logger.info(
                STARTUP.getMarker(),
                "BirthRoundMigrationShim initialized with firstVersionInBirthRoundMode={}, "
                        + "lastRoundBeforeBirthRoundMode={}, lowestJudgeGenerationBeforeBirthRoundMode={}",
                firstVersionInBirthRoundMode,
                lastRoundBeforeBirthRoundMode,
                lowestJudgeGenerationBeforeBirthRoundMode);

        this.firstVersionInBirthRoundMode = firstVersionInBirthRoundMode;
        this.lastRoundBeforeBirthRoundMode = lastRoundBeforeBirthRoundMode;
        this.lowestJudgeGenerationBeforeBirthRoundMode = lowestJudgeGenerationBeforeBirthRoundMode;

        shimAncientEvents = platformContext.getMetrics().getOrCreate(SHIM_ANCIENT_EVENTS);
        shimBarelyNonAncientEvents = platformContext.getMetrics().getOrCreate(SHIM_BARELY_NON_ANCIENT_EVENTS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformEvent migrateEvent(@NonNull final PlatformEvent event) {
        if (HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(event.getSoftwareVersion(), firstVersionInBirthRoundMode)
                < 0) {
            // The event was created before the birth round mode was enabled.
            // We need to migrate the event's birth round.

            if (event.getGeneration() >= lowestJudgeGenerationBeforeBirthRoundMode) {
                // Any event with a generation greater than or equal to the lowest pre-migration judge generation
                // is given a birth round that will be non-ancient at migration time.
                logger.debug(
                        STARTUP.getMarker(),
                        "Event migrated to use birth rounds prev={} new={} (non-ancient)",
                        event.getBirthRound(),
                        lastRoundBeforeBirthRoundMode);
                event.overrideBirthRound(lastRoundBeforeBirthRoundMode, lowestJudgeGenerationBeforeBirthRoundMode);
                shimBarelyNonAncientEvents.cycle();
            } else {
                // All other pre-migration events are given a birth round that will
                // cause them to be immediately ancient.
                logger.debug(
                        STARTUP.getMarker(),
                        "Event migrated to use birth rounds prev={} new={} (ancient)",
                        event.getBirthRound(),
                        ROUND_FIRST);
                event.overrideBirthRound(ROUND_FIRST, lowestJudgeGenerationBeforeBirthRoundMode);
                shimAncientEvents.cycle();
            }
        }

        return event;
    }
}
