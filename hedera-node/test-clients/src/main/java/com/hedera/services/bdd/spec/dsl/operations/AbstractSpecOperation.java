// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Provides implementation support for a {@link SpecOperation} that depends on
 * one or more {@link SpecEntity}s to be present in the {@link HapiSpec} before
 * it can be executed.
 */
public abstract class AbstractSpecOperation implements SpecOperation {
    protected final List<SpecEntity> requiredEntities;

    protected AbstractSpecOperation(@NonNull final List<SpecEntity> requiredEntities) {
        this.requiredEntities = closureOf(requireNonNull(requiredEntities));
    }

    /**
     * Computes the delegate operation for the given {@link HapiSpec}.
     *
     * @param spec the {@link HapiSpec} to compute the delegate operation for
     * @return the delegate operation
     */
    protected abstract @NonNull SpecOperation computeDelegate(@NonNull final HapiSpec spec);

    /**
     * Hook to be called when the operation is successful.
     *
     * @param spec the {@link HapiSpec} the operation was successful for
     */
    protected void onSuccess(@NonNull final HapiSpec spec) {
        // No-op
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes the operation for the given {@link HapiSpec} by first ensuring all entities
     * are registered with the spec, then computing its delegate operation and submitting it.
     *
     * @param spec the {@link HapiSpec} to execute the operation for
     * @return an optional containing any failure that was thrown
     */
    @Override
    public Optional<Throwable> execFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        requiredEntities.forEach(entity -> entity.registerOrCreateWith(spec));
        final var delegate = computeDelegate(spec);
        final var maybeFailure = delegate.execFor(spec);
        if (maybeFailure.isEmpty()) {
            onSuccess(spec);
        }
        return maybeFailure;
    }

    /**
     * Returns the closure of the given direct requirements, including all transitive prerequisites.
     * The returned list may contain duplicates, but this doesn't matter for our purposes.
     *
     * <p>We also do not attempt to achieve a topological sort of the requirements; once again
     * there is no likely scenario where this would be necessary, as specs will only use a handful of
     * entities.
     *
     * @param directRequirements the direct requirements
     * @return the closure of the direct requirements
     */
    private List<SpecEntity> closureOf(@NonNull final List<SpecEntity> directRequirements) {
        final List<SpecEntity> allRequirements = new ArrayList<>();
        directRequirements.forEach(entity -> addRequisites(allRequirements, entity));
        return allRequirements;
    }

    private void addRequisites(@NonNull final List<SpecEntity> allRequirements, @NonNull final SpecEntity entity) {
        requireNonNull(entity);
        entity.prerequisiteEntities().forEach(prerequisite -> addRequisites(allRequirements, prerequisite));
        allRequirements.add(entity);
    }
}
