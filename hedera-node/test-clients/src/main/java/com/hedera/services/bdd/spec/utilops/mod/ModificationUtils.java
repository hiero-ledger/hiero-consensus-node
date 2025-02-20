// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;

import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Helpers for creating and using {@link TxnModificationStrategy} and
 * {@link QueryModificationStrategy} instances in {@link HapiSpec}s.
 *
 */
public class ModificationUtils {
    private ModificationUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a factory that computes a list of {@link TxnModification}s from a
     * {@link Transaction}, where these modifications focus on mutating entity ids.
     * The default entity id modification strategies are:
     * <ul>
     *     <li>{@link BodyIdClearingStrategy} - which one at a time clears any
     *     entity id set in the {@link TransactionBody}.</li>
     * </ul>
     *
     * @return the default entity id modifications factory for transactions
     */
    public static Function<Transaction, List<TxnModification>> withSuccessivelyVariedBodyIds() {
        return withTxnModificationStrategies(List.of(new BodyIdClearingStrategy()));
    }

    /**
     * Returns a factory that computes a list of {@link QueryModification}s from a
     * {@link Query}, where these modifications focus on mutating entity ids.
     * The default entity id modification strategies are:
     * <ul>
     *     <li>{@link QueryIdClearingStrategy} - which one at a time clears any
     *     entity id set in the {@link Query}.</li>
     * </ul>
     *
     * @return the default entity id modifications factory for queries
     */
    public static Function<Query, List<QueryModification>> withSuccessivelyVariedQueryIds() {
        return withQueryModificationStrategies(List.of(new QueryIdClearingStrategy()));
    }

    /**
     * Returns a factory that computes a list of {@link QueryModification}s from a
     * {@link Query}, where these modifications are derived from a given list of
     * {@link QueryModificationStrategy} implementations.
     *
     * @return the modifications factory for queries based on the given strategies
     */
    public static Function<Query, List<QueryModification>> withQueryModificationStrategies(
            @NonNull final List<QueryModificationStrategy> strategies) {
        return query -> modificationsFor(query, strategies);
    }

    /**
     * Returns a factory that computes a list of {@link TxnModification}s from a
     * {@link TransactionBody}, where these modifications are derived from a given list of
     * {@link TxnModificationStrategy} implementations.
     *
     * @return the modifications factory for transactions based on the given strategies
     */
    public static Function<Transaction, List<TxnModification>> withTxnModificationStrategies(
            @NonNull final List<TxnModificationStrategy> strategies) {
        return transaction -> {
            final TransactionBody body;
            try {
                body = extractTransactionBody(transaction);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e);
            }
            return modificationsFor(body, strategies);
        };
    }

    /**
     * Returns a copy of the given {@link GeneratedMessage} with the given
     * occurrence number of the field identified by the given
     * {@link Descriptors.FieldDescriptor} cleared.
     *
     * @param message the message whose field should be cleared
     * @param descriptor the field descriptor
     * @param targetIndex the occurrence number of the field to clear
     * @return the message with the field cleared
     * @param <T> the type of the message
     */
    public static <T extends GeneratedMessage> T withClearedField(
            @NonNull final T message, @NonNull final Descriptors.FieldDescriptor descriptor, final int targetIndex) {
        final var currentIndex = new AtomicInteger(0);
        return withClearedField(message, descriptor, targetIndex, currentIndex);
    }

    /**
     * Given a list of {@link ModificationStrategy} instances, returns a list of
     * modifications for the given {@link GeneratedMessage} message.
     *
     * @param message the message to modify
     * @param strategies the modification strategies to apply
     * @return the list of modifications
     * @param <T> the type of the message
     * @param <M> the type of the modification strategy
     */
    private static <T, M extends ModificationStrategy<T>> List<T> modificationsFor(
            @NonNull final GeneratedMessage message, @NonNull final List<M> strategies) {
        final List<T> modifications = new ArrayList<>();
        for (final var strategy : strategies) {
            final var targetFields = getTargetFields(message.toBuilder(), strategy::hasTarget);
            // Since the same field descriptor can appear multiple times in the message,
            // we need to track its occurrence count to target each appearance separately
            final Map<String, AtomicInteger> occurrenceCounts = new HashMap<>();
            modifications.addAll(targetFields.stream()
                    .map(field -> {
                        final var encounterIndex = occurrenceCounts
                                .computeIfAbsent(field.descriptor().getFullName(), k -> new AtomicInteger(0))
                                .getAndIncrement();
                        return strategy.modificationForTarget(field, encounterIndex);
                    })
                    .toList());
        }
        return modifications;
    }

    private static <T extends GeneratedMessage> T withClearedField(
            @NonNull final T message,
            @NonNull final Descriptors.FieldDescriptor descriptor,
            final int targetIndex,
            @NonNull final AtomicInteger currentIndex) {
        final var builder = message.toBuilder();
        if (descriptor.getContainingType().equals(builder.getDescriptorForType())) {
            final var value = builder.getField(descriptor);
            if (value instanceof List<?> list) {
                final var clearedList = list.stream()
                        .filter(subValue -> currentIndex.getAndIncrement() != targetIndex)
                        .toList();
                builder.setField(descriptor, clearedList);
            } else {
                builder.clearField(descriptor);
            }
        } else {
            builder.getAllFields().forEach((field, value) -> {
                if (value instanceof GeneratedMessage subMessage) {
                    builder.setField(field, withClearedField(subMessage, descriptor, targetIndex, currentIndex));
                } else if (value instanceof List<?> list) {
                    final var clearedList = list.stream()
                            .map(item -> (item instanceof GeneratedMessage subMessageItem)
                                    ? withClearedField(subMessageItem, descriptor, targetIndex, currentIndex)
                                    : item)
                            .toList();
                    builder.setField(field, clearedList);
                }
            });
        }
        return (T) builder.build();
    }

    private static List<TargetField> getTargetFields(
            @NonNull final Message.Builder builder,
            @NonNull final BiPredicate<Descriptors.FieldDescriptor, Object> filter) {
        final List<TargetField> targetFields = new ArrayList<>();
        accumulateFields(false, builder, targetFields, filter);
        System.out.println("Target fields: " + targetFields);
        return targetFields;
    }

    private static void accumulateFields(
            final boolean isInScheduledTransaction,
            @NonNull final Message.Builder builder,
            @NonNull final List<TargetField> descriptors,
            @NonNull final BiPredicate<Descriptors.FieldDescriptor, Object> filter) {
        builder.getAllFields().forEach((field, value) -> {
            if (filter.test(field, value)) {
                descriptors.add(new TargetField(field, isInScheduledTransaction));
            } else if (value instanceof Message message) {
                accumulateFields(
                        message instanceof SchedulableTransactionBody || isInScheduledTransaction,
                        message.toBuilder(),
                        descriptors,
                        filter);
            } else if (value instanceof List<?> list) {
                list.forEach(subValue -> {
                    if (filter.test(field, subValue)) {
                        descriptors.add(new TargetField(field, isInScheduledTransaction));
                    } else if (subValue instanceof Message subMessage) {
                        accumulateFields(isInScheduledTransaction, subMessage.toBuilder(), descriptors, filter);
                    }
                });
            }
        });
    }
}
