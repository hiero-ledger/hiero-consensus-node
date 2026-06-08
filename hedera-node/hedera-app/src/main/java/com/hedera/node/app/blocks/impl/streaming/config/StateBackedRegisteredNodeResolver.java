// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableRegisteredNodeStoreImpl;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * State-backed implementation of {@link RegisteredNodeEndpointResolver}.
 *
 * <p>The latest visible {@link State} is published into this resolver from the block-stream
 * lifecycle (see {@code BlockStreamModule#provideBlockStreamManagerLifecycle}), so subsequent
 * resolution calls always observe the most recent State that the block stream manager has seen.
 */
@Singleton
public class StateBackedRegisteredNodeResolver implements RegisteredNodeEndpointResolver {

    private static final Logger logger = LogManager.getLogger(StateBackedRegisteredNodeResolver.class);

    private final AtomicReference<State> stateRef = new AtomicReference<>();

    @Inject
    public StateBackedRegisteredNodeResolver() {
        // no-op
    }

    /**
     * Publishes the latest visible State. Subsequent {@link #resolve(long)} calls will use this State.
     */
    public void updateState(@NonNull final State state) {
        stateRef.set(state);
    }

    @Override
    public boolean isStateAvailable() {
        return stateRef.get() != null;
    }

    @Override
    public @Nullable RegisteredNode resolve(final long registeredNodeId) {
        final State state = stateRef.get();
        if (state == null) {
            return null;
        }
        try {
            final var addressBookStates = state.getReadableStates(AddressBookService.NAME);
            final var entityIdStore = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
            final ReadableRegisteredNodeStore store =
                    new ReadableRegisteredNodeStoreImpl(addressBookStates, entityIdStore);
            return store.get(registeredNodeId);
        } catch (final RuntimeException e) {
            logger.warn("Failed to resolve registered node id {} from state", registeredNodeId, e);
            return null;
        }
    }
}
