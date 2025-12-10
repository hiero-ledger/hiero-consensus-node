// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import static com.hedera.statevalidation.poc.validator.api.Validator.ALL_TAG;

import com.hedera.statevalidation.Validate2Command;
import com.hedera.statevalidation.poc.listener.ValidationListener;
import com.hedera.statevalidation.poc.model.DiskDataItem.Type;
import com.hedera.statevalidation.poc.util.ValidationException;
import com.hedera.statevalidation.poc.validator.api.HashRecordValidator;
import com.hedera.statevalidation.poc.validator.api.HdhmBucketValidator;
import com.hedera.statevalidation.poc.validator.api.LeafBytesValidator;
import com.hedera.statevalidation.poc.validator.api.Validator;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

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
 *     <li>Add a new tag to {@link Validate2Command}</li>
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
     * Master list of all available validators.
     */
    private static final List<Validator> ALL_VALIDATORS = List.of(
            new HashRecordIntegrityValidator(),
            new HdhmBucketIntegrityValidator(),
            new LeafBytesIntegrityValidator(),
            new AccountAndSupplyValidator(),
            new TokenRelationsIntegrityValidator(),
            new EntityIdCountValidator(),
            new EntityIdUniquenessValidator(),
            new RehashValidator(),
            new MerkleTreeValidator());

    /**
     * Returns pipeline validators grouped by the data type they process.
     *
     * <p>Pipeline validators are those that implement one of the specialized interfaces:
     * {@link HashRecordValidator}, {@link HdhmBucketValidator}, or {@link LeafBytesValidator}.
     * They receive data items streamed through the validation pipeline.
     *
     * @return a map from {@link Type} to the list of validators processing that type;
     *         the returned map uses {@link EnumMap} for optimal performance
     */
    public static Map<Type, List<Validator>> getPipelineValidators() {
        Map<Type, List<Validator>> result = new EnumMap<>(Type.class);

        for (Validator v : ALL_VALIDATORS) {
            if (v instanceof HashRecordValidator) {
                result.computeIfAbsent(Type.P2H, k -> new ArrayList<>()).add(v);
            } else if (v instanceof HdhmBucketValidator) {
                result.computeIfAbsent(Type.K2P, k -> new ArrayList<>()).add(v);
            } else if (v instanceof LeafBytesValidator) {
                result.computeIfAbsent(Type.P2KV, k -> new ArrayList<>()).add(v);
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
     * @return an unmodifiable list of individual validators
     */
    public static List<Validator> getIndividualValidators() {
        return ALL_VALIDATORS.stream()
                .filter(v -> !(v instanceof HashRecordValidator)
                        && !(v instanceof HdhmBucketValidator)
                        && !(v instanceof LeafBytesValidator))
                .toList();
    }

    /**
     * Creates, filters, and initializes pipeline validators based on the provided tags.
     *
     * <p>This method performs the following steps for each registered pipeline validator:
     * <ol>
     *     <li>Checks if the validator's tag matches the requested tags (or if "all" is specified)</li>
     *     <li>Notifies listeners that validation is starting</li>
     *     <li>Calls {@link Validator#initialize(DeserializedSignedState)}</li>
     *     <li>On success, adds the validator to the result set</li>
     *     <li>On failure, notifies listeners and excludes the validator</li>
     * </ol>
     *
     * @param deserializedSignedState the state to initialize validators with; must not be null
     * @param tags array of validator tags to run; use {@link Validator#ALL_TAG} to run all validators
     * @param validationListeners listeners to notify of initialization events; must not be null
     * @return a map from {@link Type} to thread-safe sets of initialized validators;
     *         uses {@link CopyOnWriteArraySet} for safe concurrent access during pipeline execution
     */
    public static Map<Type, CopyOnWriteArraySet<Validator>> createAndInitValidators(
            @NonNull final DeserializedSignedState deserializedSignedState,
            @NonNull final String[] tags,
            @NonNull final List<ValidationListener> validationListeners) {

        final Set<String> tagSet = Set.of(tags);
        final boolean runAll = tagSet.contains(ALL_TAG);

        final Map<Type, CopyOnWriteArraySet<Validator>> result = new EnumMap<>(Type.class);

        ValidatorRegistry.getPipelineValidators().forEach((type, validators) -> {
            final CopyOnWriteArraySet<Validator> validatorSet = new CopyOnWriteArraySet<>();
            for (Validator v : validators) {
                if (runAll || tagSet.contains(v.getTag())) {
                    if (tryInitialize(v, deserializedSignedState, validationListeners)) {
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
     * Creates, filters, and initializes individual validators based on the provided tags.
     *
     * <p>Similar to {@link #createAndInitValidators}, but for validators that run
     * independently outside the data pipeline.
     *
     * @param deserializedSignedState the state to initialize validators with; must not be null
     * @param tags array of validator tags to run; use {@link Validator#ALL_TAG} to run all validators
     * @param validationListeners listeners to notify of initialization events; must not be null
     * @return an unmodifiable list of initialized individual validators
     */
    public static List<Validator> createAndInitIndividualValidators(
            @NonNull final DeserializedSignedState deserializedSignedState,
            @NonNull final String[] tags,
            @NonNull final List<ValidationListener> validationListeners) {

        final Set<String> tagSet = Set.of(tags);
        final boolean runAll = tagSet.contains(ALL_TAG);

        return ValidatorRegistry.getIndividualValidators().stream()
                .filter(v -> runAll || tagSet.contains(v.getTag()))
                .filter(v -> tryInitialize(v, deserializedSignedState, validationListeners))
                .toList();
    }

    /**
     * Attempts to initialize a validator, notifying listeners of the outcome.
     *
     * <p>This method:
     * <ol>
     *     <li>Notifies all listeners via {@link ValidationListener#onValidationStarted(String)}</li>
     *     <li>Calls {@link Validator#initialize(DeserializedSignedState)}</li>
     *     <li>On failure, notifies listeners via {@link ValidationListener#onValidationFailed(ValidationException)}</li>
     * </ol>
     *
     * @param validator the validator to initialize
     * @param state the deserialized state to pass to the validator
     * @param listeners listeners to notify of the initialization outcome
     * @return {@code true} if initialization succeeded, {@code false} otherwise
     */
    public static boolean tryInitialize(
            Validator validator, DeserializedSignedState state, List<ValidationListener> listeners) {
        listeners.forEach(l -> l.onValidationStarted(validator.getTag()));
        try {
            validator.initialize(state);
            return true;
        } catch (ValidationException e) {
            listeners.forEach(l -> l.onValidationFailed(e));
        } catch (Exception e) {
            listeners.forEach(l -> l.onValidationFailed(
                    new ValidationException(validator.getTag(), "Unexpected: " + e.getMessage(), e)));
        }
        return false;
    }
}
