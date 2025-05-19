// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Helper class that defines a step in the status progression of a node.
 *
 * <p>This class is used to define the expected status progression of a node during a test. It allows
 * to specify the target status, as well as any required or optional interim statuses that must/can be
 * reached before reaching the target status.
 */
public class StatusProgressionStep {

    private final PlatformStatus target;
    private final EnumSet<PlatformStatus> requiredInterim;
    private final EnumSet<PlatformStatus> optionalInterim;

    private StatusProgressionStep(
            @NonNull final PlatformStatus target,
            @NonNull final EnumSet<PlatformStatus> requiredInterim,
            @NonNull final EnumSet<PlatformStatus> optionalInterim) {
        this.target = requireNonNull(target);
        this.requiredInterim = requireNonNull(requiredInterim);
        this.optionalInterim = requireNonNull(optionalInterim);
    }

    /**
     * Returns the target status of this step.
     *
     * @return the target status
     */
    @NonNull
    public PlatformStatus target() {
        return target;
    }

    /**
     * Returns the required interim statuses of this step. The {@link Set} may be empty.
     *
     * @return the required interim statuses as an immutable {@link Set}
     */
    @NonNull
    public Set<PlatformStatus> requiredInterim() {
        return unmodifiableSet(requiredInterim);
    }

    /**
     * Returns the optional interim statuses of this step. The {@link Set} may be empty.
     *
     * @return the optional interim statuses as an immutable {@link Set}
     */
    @NonNull
    public Set<PlatformStatus> optionalInterim() {
        return unmodifiableSet(optionalInterim);
    }

    /**
     * Creates a new instance of {@link StatusProgressionStep} with the specified target status.
     *
     * @param target the target status
     * @return a new instance of {@link StatusProgressionStep}
     */
    public static StatusProgressionStep target(@NonNull final PlatformStatus target) {
        return new StatusProgressionStep(
                target, EnumSet.noneOf(PlatformStatus.class), EnumSet.noneOf(PlatformStatus.class));
    }

    /**
     * Creates a new instance of {@link StatusProgressionStep} that is a copy of this instance, but
     * with the specified required interim status added.
     *
     * @param first the first required interim status
     * @param rest optionally, the additional required interim statuses
     * @return a new instance of {@link StatusProgressionStep} with the specified required interim status
     */
    public StatusProgressionStep requiringInterim(
            @NonNull final PlatformStatus first, @Nullable final PlatformStatus... rest) {
        requireNonNull(first);

        final EnumSet<PlatformStatus> newRequiredInterim = EnumSet.copyOf(requiredInterim);
        newRequiredInterim.add(first);
        if (rest != null) {
            newRequiredInterim.addAll(Arrays.asList(rest));
        }

        return new StatusProgressionStep(target, newRequiredInterim, optionalInterim);
    }

    /**
     * Creates a new instance of {@link StatusProgressionStep} that is a copy of this instance, but
     * with the specified optional interim status added.
     *
     * @param first the first optional interim status
     * @param rest optionally, the additional optional interim statuses
     * @return a new instance of {@link StatusProgressionStep} with the specified optional interim status
     */
    public StatusProgressionStep optionalInterim(
            @NonNull final PlatformStatus first, @Nullable final PlatformStatus... rest) {
        requireNonNull(first);

        final EnumSet<PlatformStatus> newOptionalInterim = EnumSet.copyOf(optionalInterim);
        newOptionalInterim.add(first);
        if (rest != null) {
            newOptionalInterim.addAll(Arrays.asList(rest));
        }

        return new StatusProgressionStep(target, requiredInterim, newOptionalInterim);
    }
}
