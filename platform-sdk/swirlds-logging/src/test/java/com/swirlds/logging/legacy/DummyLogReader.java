// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import com.swirlds.logging.legacy.json.JsonLogEntry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * A log reader that emits "canned" data.
 */
public class DummyLogReader extends SwirldsLogReader<JsonLogEntry> {

    private final List<JsonLogEntry> entryList;
    private final Queue<JsonLogEntry> entryQueue;

    public DummyLogReader(final List<JsonLogEntry> entryList) throws FileNotFoundException {
        this.entryList = entryList;
        this.entryQueue = new LinkedList<>(entryList);
    }

    private DummyLogReader(final DummyLogReader that) {
        entryList = that.entryList;
        entryQueue = new LinkedList<>(entryList);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JsonLogEntry readNextEntry() throws IOException {
        if (entryQueue.size() > 0) {
            return entryQueue.remove();
        } else {
            return null;
        }
    }

    public DummyLogReader copy() {
        return new DummyLogReader(this);
    }
}
