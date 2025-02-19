/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.gui.hashgraph.internal;

import com.swirlds.platform.gui.hashgraph.HashgraphGuiConstants;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.events.EventConstants;
import java.util.List;

/**
 * A {@link HashgraphGuiSource} that wraps another source but caches the results until {@link #refresh()} is called
 */
public class CachingGuiSource implements HashgraphGuiSource {
    private final HashgraphGuiSource source;
    private List<EventImpl> events = null;
    private AddressBook addressBook = null;
    private long maxGeneration = EventConstants.GENERATION_UNDEFINED;
    private long startGeneration = EventConstants.FIRST_GENERATION;
    private int numGenerations = HashgraphGuiConstants.DEFAULT_GENERATIONS_TO_DISPLAY;

    public CachingGuiSource(final HashgraphGuiSource source) {
        this.source = source;
    }

    @Override
    public long getMaxGeneration() {
        return maxGeneration;
    }

    @Override
    public List<EventImpl> getEvents(final long startGeneration, final int numGenerations) {
        this.startGeneration = startGeneration;
        this.numGenerations = numGenerations;
        return events;
    }

    @Override
    public AddressBook getAddressBook() {
        return addressBook;
    }

    @Override
    public boolean isReady() {
        return events != null && addressBook != null && maxGeneration != EventConstants.GENERATION_UNDEFINED;
    }

    /**
     * Reload the data from the source and cache it
     */
    public void refresh() {
        if (source.isReady()) {
            events = source.getEvents(startGeneration, numGenerations);
            addressBook = source.getAddressBook();
            maxGeneration = source.getMaxGeneration();
        }
    }
}
