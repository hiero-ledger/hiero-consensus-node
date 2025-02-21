// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link BuilderSink} accumulates stream builders that are either preceding or following an implicit "base builder".
 * It enforces capacity limits as client code attempts to add builders, but also provides {@link #precedingCapacity()}
 * and {@link #followingCapacity()} capacity information.
 * <p>
 * Besides accumulating a controlled number of builders within its capacity, it supports traversing and coalescing
 * its builders, or flushing them to a provided parent sink.
 * <p>
 * Client code is free to designate any builder as the base builder; this type is agnostic about which builder
 * is the base builder, or if a base builder even exists.
 */
public interface BuilderSink {
    /**
     * Adds a builder to the preceding builders of this sink. Throws a {@link HandleException} if the
     * maximum number of preceding builders has been exceeded.
     * @param builder the builder to add
     */
    void addPrecedingOrThrow(@NonNull StreamBuilder builder);

    /**
     * Adds a builder to the following builders of this sink. Throws a {@link HandleException} if the
     * maximum number of following builders has been exceeded.
     * @param builder the builder to add
     */
    void addFollowingOrThrow(@NonNull StreamBuilder builder);

    /**
     * Adds all the given builders to the preceding builders of this sink. Throws a {@link HandleException} if the
     * maximum number of preceding builders has been exceeded.
     * @param builders the builders to add
     */
    default void addAllPreceding(@NonNull List<StreamBuilder> builders) {
        builders.forEach(this::addPrecedingOrThrow);
    }

    /**
     * Adds all the given builders to the following builders of this sink. Throws a {@link HandleException} if the
     * maximum number of following builders has been exceeded.
     * @param builders the builders to add
     */
    default void addAllFollowing(@NonNull List<StreamBuilder> builders) {
        builders.forEach(this::addFollowingOrThrow);
    }

    /**
     * Returns the number of preceding builders that can be added to this sink, as controlled by both the total
     * and preceding builder limits.
     * @return the number of preceding builders that can be added to this sink
     */
    int precedingCapacity();

    /**
     * Returns the number of following builders that can be added to this sink, as controlled by both the total
     * and following builder limits.
     * @return the number of following builders that can be added to this sink
     */
    int followingCapacity();

    /**
     * Returns all the builders accumulated in this sink, with the preceding builders coming before the following
     * builders, in the order they were added.
     * @return all accumulated builders
     */
    List<StreamBuilder> allBuilders();

    /**
     * Returns whether this savepoint has accumulated any builders other than the designated base builder.
     * @param baseBuilder the base builder
     * @return whether this savepoint has any builders other than the base builder
     */
    boolean hasBuilderOtherThan(@NonNull StreamBuilder baseBuilder);

    /**
     * For each builder in this savepoint other than the designated base builder, invokes the given consumer
     * with the builder cast to the given type.
     *
     * @param consumer the consumer to invoke
     * @param builderType the type to cast the builders to
     * @param baseBuilder the base builder
     * @param <T> the type to cast the builders to
     */
    <T> void forEachOtherBuilder(
            @NonNull Consumer<T> consumer, @NonNull Class<T> builderType, @NonNull StreamBuilder baseBuilder);

    /**
     * Flushes any stream item builders accumulated in this sink to the parent sink, preserving their ordering relative
     * to the implicit base builder. That is, flushes all preceding builders to the preceding builders of the parent,
     * then all following builders to the following builders of the parent.
     * @param parentSink the parent sink to flush to
     */
    void flushInOrder(@NonNull BuilderSink parentSink);

    /**
     * Flushes all builders to the preceding builders of the parent sink.
     * @param parentSink the parent sink to flush to
     */
    void flushPreceding(@NonNull BuilderSink parentSink);

    /**
     * Flushes all builders to the following builders of the parent sink.
     * @param parentSink the parent sink to flush to
     */
    void flushFollowing(@NonNull BuilderSink parentSink);
}
