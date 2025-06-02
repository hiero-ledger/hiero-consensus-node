package com.swirlds.platform.system.events;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.PlatformEvent;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

public class DefaultGenerationCalculator implements GenerationCalculator {

    private static final Logger logger = LogManager.getLogger(DefaultGenerationCalculator.class);

    private final long firstBirthRoundWithoutGenerations;
    private final boolean setGenerationToZero;

    /**
     * Constructs a new BirthRoundMigrationShim.
     *
     * @param platformContext                   the platform context
     * @param firstBirthRoundWithoutGenerations the first birth round value where generations are not used
     */
    public DefaultGenerationCalculator(
            @NonNull final PlatformContext platformContext,
            final long firstBirthRoundWithoutGenerations) {

        logger.info(
                STARTUP.getMarker(),
                "GenerationMigrationShim initialized with firstRoundWithoutGenerations={}",
                firstBirthRoundWithoutGenerations);

        this.firstBirthRoundWithoutGenerations = firstBirthRoundWithoutGenerations;
        this.setGenerationToZero = platformContext.getConfiguration().getConfigData(EventConfig.class)
                .setGenerationToZero();

//        shimAncientEvents = platformContext.getMetrics().getOrCreate(SHIM_ANCIENT_EVENTS);
//        shimBarelyNonAncientEvents = platformContext.getMetrics().getOrCreate(SHIM_BARELY_NON_ANCIENT_EVENTS);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformEvent maybeCalculateGeneration(@NonNull final PlatformEvent event) {
        if (!setGenerationToZero) {
            // We are not yet zeroing out generation, so we need to populate all events with generation
            event.calculateGeneration();
        } else if (event.getBirthRound() < firstBirthRoundWithoutGenerations) {
            // This event was created with a version of software that calculated generation,
            // so we need to calculate it again now.
            event.calculateGeneration();
        }
        return event;
    }
}
