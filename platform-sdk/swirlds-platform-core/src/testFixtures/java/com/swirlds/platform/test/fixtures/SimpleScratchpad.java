// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.common.formatting.TextTable;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.scratchpad.ScratchpadType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * A pared down version of the StandardScratchpad class that does not actually write to disk. Useful for testing
 * where it is inconvenient to create files. Is not thread safe.
 *
 * @param <K> the type of scratchpad
 */
public class SimpleScratchpad<K extends Enum<K> & ScratchpadType> implements Scratchpad<K> {

    private static final Logger logger = LogManager.getLogger();

    private final Map<K, SelfSerializable> data = new HashMap<>();

    public SimpleScratchpad() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void logContents() {
        final TextTable table = new TextTable().setBordersEnabled(false);

        for (final K field : data.keySet()) {
            final SelfSerializable value = data.get(field);
            if (value == null) {
                table.addToRow(field.name(), "null");
            } else {
                table.addRow(field.name(), value.toString());
            }
        }

        logger.info(
                STARTUP.getMarker(),
                """
                        Scratchpad contents:
                        {}""",
                table.render());
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <V extends SelfSerializable> V get(@NonNull K key) {
        return (V) data.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <V extends SelfSerializable> V set(@NonNull K key, @Nullable V value) {
        return (V) data.put(key, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void atomicOperation(@NonNull Consumer<Map<K, SelfSerializable>> operation) {
        operation.accept(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void atomicOperation(@NonNull Function<Map<K, SelfSerializable>, Boolean> operation) {
        operation.apply(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        data.clear();
    }
}
