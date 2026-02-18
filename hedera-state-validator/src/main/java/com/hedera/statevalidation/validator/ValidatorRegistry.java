// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.validator.Validator.ALL_GROUP;

import com.hedera.statevalidation.ValidateCommand;
import com.hedera.statevalidation.validator.listener.ValidationListener;
import com.hedera.statevalidation.validator.model.DiskDataItem.Type;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central registry for all state validators.
 *
 * <p>This class serves as the single source of truth for validator registration and instantiation.
 *
 * <h2>Adding a New Validator</h2>
 * <p>To register a new validator:
 * <ol>
 *     <li>Create your validator class implementing one of the validator interfaces
 *         ({@link HashRecordValidator}, {@link HdhmBucketValidator}, {@link LeafBytesValidator},
 *         or base {@link Validator} for individual validators)</li>
 *     <li>Add a new instance to {@link #ALL_VALIDATORS} list below</li>
 *     <li>Optionally, add a new validator group to {@link ValidateCommand}</li>
 * </ol>
 *
 * <p>That's it. The registry automatically categorizes validators based on their interface:
 * <ul>
 *     <li>{@link HashRecordValidator} → {@link Type#P2H} (Path to Hash)</li>
 *     <li>{@link HdhmBucketValidator} → {@link Type#K2P} (Key to Path)</li>
 *     <li>{@link LeafBytesValidator} → {@link Type#P2KV} (Path to Key/Value)</li>
 *     <li>Base {@link Validator} only → Individual validator (runs outside the pipeline)</li>
 * </ul>
 *
 * @see Validator
 * @see ValidationListener
 */
public final class ValidatorRegistry {

    /**
     * Master set of all available validators.
     */
    private static final Set<Validator> ALL_VALIDATORS = Set.of(
            new HashRecordIntegrityValidator(),
            new HdhmBucketIntegrityValidator(),
            new LeafBytesIntegrityValidator(),
            new AccountAndSupplyValidator(),
            new TokenRelationsIntegrityValidator(),
            new EntityIdCountValidator(),
            new EntityIdUniquenessValidator(),
            new RehashValidator(),
            new RootHashValidator());

    /**
     * Returns pipeline validators grouped by the data type they process.
     *
     * <p>Pipeline validators are those that implement one of the specialized interfaces:
     * {@link HashRecordValidator}, {@link HdhmBucketValidator}, or {@link LeafBytesValidator}.
     * They receive data items streamed through the validation pipeline.
     *
     * @return a map from {@link Type} to the set of validators processing that type;
     */
    public static Map<Type, Set<Validator>> getPipelineValidators() {
        final Map<Type, Set<Validator>> result = new EnumMap<>(Type.class);

        for (final Validator validator : ALL_VALIDATORS) {
            if (validator instanceof HashRecordValidator) {
                result.computeIfAbsent(Type.P2H, k -> new HashSet<>()).add(validator);
            } else if (validator instanceof HdhmBucketValidator) {
                result.computeIfAbsent(Type.K2P, k -> new HashSet<>()).add(validator);
            } else if (validator instanceof LeafBytesValidator) {
                result.computeIfAbsent(Type.P2KV, k -> new HashSet<>()).add(validator);
            }
        }

        return result;
    }

    /**
     * Returns validators that run independently outside the data pipeline.
     *
     * <p>Individual validators implement only the base {@link Validator} interface
     * (not any of the specialized data-processing interfaces). They typically perform
     * validation that requires custom execution patterns, such as tree traversal
     * or external file comparison.
     *
     * @return an unmodifiable set of individual validators
     */
    public static Set<Validator> getIndividualValidators() {
        return ALL_VALIDATORS.stream()
                .filter(v -> !(v instanceof HashRecordValidator)
                        && !(v instanceof HdhmBucketValidator)
                        && !(v instanceof LeafBytesValidator))
                .collect(Collectors.toSet());
    }

    /**
     * Creates, filters, and initializes pipeline validators based on the provided validation groups.
     *
     * <p>This method performs the following steps for each registered pipeline validator:
     * <ol>
     *     <li>Checks if the validator's group matches the requested groups (or if "all" is specified)</li>
     *     <li>Notifies listeners that validation is starting</li>
     *     <li>Calls {@link Validator#initialize(VirtualMapState)}</li>
     *     <li>On success, adds the validator to the result set</li>
     *     <li>On failure, notifies listeners and excludes the validator</li>
     * </ol>
     *
     * @param state               the state to initialize validators with; must not be null
     * @param validationGroups    array of validator groups to run; use {@link Validator#ALL_GROUP} to run all validators
     * @param validationListeners listeners to notify of initialization events; must not be null
     * @return a map from {@link Type} to thread-safe sets of initialized validators;
     */
    public static Map<Type, Set<Validator>> createAndInitValidators(
            @NonNull final VirtualMapState state,
            @NonNull final String[] validationGroups,
            @NonNull final Set<ValidationListener> validationListeners) {

        final Set<String> groupSet = Set.of(validationGroups);
        final boolean runAll = groupSet.contains(ALL_GROUP);

        final Map<Type, Set<Validator>> result = new EnumMap<>(Type.class);

        ValidatorRegistry.getPipelineValidators().forEach((type, validators) -> {
            final Set<Validator> validatorSet = new HashSet<>();
            for (Validator v : validators) {
                if (runAll || groupSet.contains(v.getGroup())) {
                    if (tryInitialize(v, state, validationListeners)) {
                        validatorSet.add(v);
                    }
                }
            }
            if (!validatorSet.isEmpty()) {
                result.put(type, validatorSet);
            }
        });

        return result;
    }

    /**
     * Creates, filters, and initializes individual validators based on the provided validation groups.
     *
     * <p>Similar to {@link #createAndInitValidators}, but for validators that run
     * independently outside the data pipeline.
     *
     * @param state               the state to initialize validators with; must not be null
     * @param validationGroups    array of validator groups to run; use {@link Validator#ALL_GROUP} to run all validators
     * @param validationListeners listeners to notify of initialization events; must not be null
     * @return an unmodifiable list of initialized individual validators
     */
    public static List<Validator> createAndInitIndividualValidators(
            @NonNull final VirtualMapState state,
            @NonNull final String[] validationGroups,
            @NonNull final Set<ValidationListener> validationListeners) {

        final Set<String> groupSet = Set.of(validationGroups);
        final boolean runAll = groupSet.contains(ALL_GROUP);

        return ValidatorRegistry.getIndividualValidators().stream()
                .filter(v -> runAll || groupSet.contains(v.getGroup()))
                .filter(v -> tryInitialize(v, state, validationListeners))
                .toList();
    }

    /**
     * Attempts to initialize a validator, notifying listeners of the outcome.
     *
     * <p>This method:
     * <ol>
     *     <li>Notifies all listeners via {@link ValidationListener#onValidationStarted(String)}</li>
     *     <li>Calls {@link Validator#initialize(VirtualMapState)}</li>
     *     <li>On failure, notifies listeners via {@link ValidationListener#onValidationFailed(ValidationException)}</li>
     * </ol>
     *
     * @param validator the validator to initialize
     * @param state the state to pass to the validator
     * @param listeners listeners to notify of the initialization outcome
     * @return {@code true} if initialization succeeded, {@code false} otherwise
     */
    public static boolean tryInitialize(
            @NonNull final Validator validator,
            @NonNull final VirtualMapState state,
            @NonNull final Set<ValidationListener> listeners) {
        listeners.forEach(l -> l.onValidationStarted(validator.getName()));
        try {
            validator.initialize(state);
            return true;
        } catch (ValidationException e) {
            listeners.forEach(l -> l.onValidationFailed(e));
        } catch (Exception e) {
            listeners.forEach(l -> l.onValidationFailed(
                    new ValidationException(validator.getName(), "Unexpected exception: " + e.getMessage(), e)));
        }
        return false;
    }
}
