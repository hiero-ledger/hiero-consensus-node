// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack.savepoints;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.stack.BuilderSink;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@link BuilderSinkImpl} supports accumulating stream item builders that are either preceding or
 * following an implicit "base builder". It supports traversing and coalescing its builders.
 * Client code is free to designate any builder as the base builder; this class is agnostic about
 * the base builder's identity.
 */
public class BuilderSinkImpl implements BuilderSink {
    protected final List<StreamBuilder> precedingBuilders = new ArrayList<>();
    protected final List<StreamBuilder> followingBuilders = new ArrayList<>();

    private final int maxPreceding;
    private final int maxFollowing;
    private final int maxTotal;

    /**
     * Constructs a {@link BuilderSinkImpl} with the given maximum number of preceding and following builders.
     * Only the {@link com.hedera.node.app.workflows.handle.stack.savepoints.FirstRootSavepoint} should use this
     * constructor, since other savepoints are limited by just a total capacity that doesn't discriminate
     * between preceding and following builders.
     * @param maxPreceding the maximum number of preceding builders
     * @param maxFollowing the maximum number of following builders
     */
    public BuilderSinkImpl(final int maxPreceding, final int maxFollowing) {
        this.maxPreceding = maxPreceding;
        this.maxFollowing = maxFollowing;
        this.maxTotal = maxPreceding == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxPreceding + maxFollowing;
    }

    /**
     * Constructs a {@link BuilderSinkImpl} with the given maximum number of total builders that can be constructed
     * in the savepoint. Used for any savepoint other than the first root savepoint.
     * @param maxTotal the maximum number of total builders
     */
    public BuilderSinkImpl(final int maxTotal) {
        this.maxPreceding = maxTotal;
        this.maxFollowing = maxTotal;
        this.maxTotal = maxTotal;
    }

    @Override
    public List<StreamBuilder> allBuilders() {
        if (precedingBuilders.isEmpty()) {
            return followingBuilders;
        } else {
            final List<StreamBuilder> allBuilders = new ArrayList<>(precedingBuilders);
            allBuilders.addAll(followingBuilders);
            return allBuilders;
        }
    }

    @Override
    public boolean hasBuilderOtherThan(@NonNull final StreamBuilder baseBuilder) {
        requireNonNull(baseBuilder);
        for (final var builder : precedingBuilders) {
            if (builder != baseBuilder) {
                return true;
            }
        }
        for (final var builder : followingBuilders) {
            if (builder != baseBuilder) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <T> void forEachOtherBuilder(
            @NonNull final Consumer<T> consumer,
            @NonNull final Class<T> builderType,
            @NonNull final StreamBuilder baseBuilder) {
        requireNonNull(builderType);
        requireNonNull(consumer);
        requireNonNull(baseBuilder);
        for (final var builder : followingBuilders) {
            if (builder != baseBuilder) {
                consumer.accept(builderType.cast(builder));
            }
        }
        for (final var builder : precedingBuilders) {
            if (builder != baseBuilder) {
                consumer.accept(builderType.cast(builder));
            }
        }
    }

    @Override
    public void flushInOrder(@NonNull final BuilderSink parentSink) {
        requireNonNull(parentSink);
        parentSink.addAllPreceding(precedingBuilders);
        parentSink.addAllFollowing(followingBuilders);
    }

    @Override
    public void flushPreceding(@NonNull final BuilderSink parentSink) {
        requireNonNull(parentSink);
        parentSink.addAllPreceding(allBuilders());
    }

    @Override
    public void flushFollowing(@NonNull final BuilderSink parentSink) {
        requireNonNull(parentSink);
        parentSink.addAllFollowing(allBuilders());
    }

    @Override
    public void addPrecedingOrThrow(@NonNull final StreamBuilder builder) {
        requireNonNull(builder);
        if (precedingCapacity() == 0) {
            throw new HandleException(MAX_CHILD_RECORDS_EXCEEDED);
        }
        precedingBuilders.add(builder);
    }

    @Override
    public void addFollowingOrThrow(@NonNull final StreamBuilder builder) {
        requireNonNull(builder);
        if (followingCapacity() == 0) {
            throw new HandleException(MAX_CHILD_RECORDS_EXCEEDED);
        }
        followingBuilders.add(builder);
    }

    @Override
    public int precedingCapacity() {
        return Math.min(maxPreceding - precedingBuilders.size(), maxTotal - numBuilders());
    }

    @Override
    public int followingCapacity() {
        return Math.min(maxFollowing - followingBuilders.size(), maxTotal - numBuilders());
    }

    private int numBuilders() {
        return precedingBuilders.size() + followingBuilders.size();
    }
}
