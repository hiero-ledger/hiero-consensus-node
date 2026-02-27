// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright (C) 2024-2026 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.virtualmap;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * An iterator that iterates over the leaves of a {@link VirtualMap}.
 *
 */
public class VirtualMapIterator implements Iterator<VirtualLeafBytes> {

    /**
     * The map being iterated.
     */
    private final VirtualMap map;

    /**
     * The path of the first node to be returned.
     */
    private final long firstPath;

    /**
     * The path of the last node to be returned.
     */
    private final long lastPath;

    /**
     * The path of the next node to be returned.
     */
    private long nextPath;

    /**
     * The path of the most recently returned node.
     */
    private long previousPath = Path.INVALID_PATH;

    /**
     * The next node to be returned.
     */
    private VirtualLeafBytes<?> nextNode;

    private BiPredicate<VirtualLeafBytes, Long> filter;

    /**
     * Create a new {@link VirtualMapIterator}.
     *
     * @param map the map to iterate over
     */
    public VirtualMapIterator(@NonNull final VirtualMap map) {
        this.map = Objects.requireNonNull(map);
        final VirtualMapMetadata metadata = map.getMetadata();
        this.firstPath = metadata.getFirstLeafPath();
        this.lastPath = metadata.getLastLeafPath();
        this.nextPath = this.firstPath;
    }

    /**
     * Set a filter for nodes. Only nodes that pass the filter will be returned.
     *
     * A realistic example of such a filter could be
     * <pre>
     *     leafBytes -&gt; StateKeyUtils.extractStateIdFromStateKeyOneOf(leafBytes.keyBytes()) == expectedStateId
     * </pre>
     * This filter would make this iterator only return leaves that belong to a specific state.
     *
     * @param filter the filter
     * @return this iterator
     * @throws IllegalStateException if called after iteration has started
     */
    @NonNull
    public VirtualMapIterator setFilter(@Nullable final Predicate<VirtualLeafBytes> filter) {
        if (nextNode != null || previousPath != Path.INVALID_PATH || nextPath != firstPath) {
            throw new IllegalStateException("Cannot set filter after iteration has started");
        }
        if (filter == null) {
            this.filter = null;
        } else {
            this.filter = (node, path) -> filter.test(node);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        if (nextNode != null) {
            return true;
        }

        if (nextPath == Path.INVALID_PATH) {
            return false;
        }

        while (nextPath <= lastPath) {
            final long path = nextPath++;
            final VirtualLeafBytes<?> leaf = map.getRecords().findLeafRecord(path);
            assert leaf != null;
            if (filter == null || filter.test(leaf, path)) {
                nextNode = leaf;
                return true;
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualLeafBytes next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final VirtualLeafBytes result = nextNode;
        nextNode = null;
        previousPath = result.path();
        return result;
    }

    /**
     * Get the path of the most recently returned node.
     *
     * @return path of the most recently returned node, or null if no node has been returned
     */
    @Nullable
    public Long getPath() {
        if (previousPath == Path.INVALID_PATH) {
            return null;
        }
        return previousPath;
    }
}
