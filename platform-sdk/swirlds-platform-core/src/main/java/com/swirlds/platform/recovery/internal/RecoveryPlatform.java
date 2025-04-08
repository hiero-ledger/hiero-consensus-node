// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.crypto.CryptoStatic.initNodeSecurity;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.utility.AutoCloseableNonThrowing;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateReference;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * A simplified version of the platform to be used during the recovery workflow.
 */
public class RecoveryPlatform implements Platform, AutoCloseableNonThrowing {

    private final NodeId selfId;
    private final Roster roster;

    private final AddressBook addressBook;
    private final KeysAndCerts keysAndCerts;

    private final SignedStateReference immutableState = new SignedStateReference();

    private final NotificationEngine notificationEngine;

    private final PlatformContext context;

    /**
     * Create a new recovery platform.
     *
     * @param configuration   the node's configuration
     * @param initialState    the starting signed state
     * @param selfId          the ID of the node
     * @param loadSigningKeys whether to load the signing keys, if false then {@link #sign(byte[])} will throw if
     *                        called
     */
    public RecoveryPlatform(
            @NonNull final Configuration configuration,
            @NonNull final SignedState initialState,
            @NonNull final NodeId selfId,
            final boolean loadSigningKeys) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(initialState, "initialState must not be null");
        this.selfId = Objects.requireNonNull(selfId, "selfId must not be null");

        this.roster = initialState.getRoster();
        this.addressBook = RosterUtils.buildAddressBook(this.roster);

        if (loadSigningKeys) {
            keysAndCerts = initNodeSecurity(addressBook, configuration, Collections.singleton(selfId))
                    .get(selfId);
        } else {
            keysAndCerts = null;
        }

        notificationEngine = NotificationEngine.buildEngine(getStaticThreadManager());

        context = PlatformContext.create(configuration);

        setLatestState(initialState);
    }

    /**
     * Set the most recent immutable state.
     *
     * @param signedState the most recent signed state
     */
    public synchronized void setLatestState(final SignedState signedState) {
        immutableState.set(signedState, "RecoveryPlatform.setLatestState");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Signature sign(final byte[] data) {
        if (keysAndCerts == null) {
            throw new UnsupportedOperationException(
                    "RecoveryPlatform was not loaded with signing keys, this operation is not supported");
        }
        return new PlatformSigner(keysAndCerts).sign(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformContext getContext() {
        return context;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NotificationEngine getNotificationEngine() {
        return notificationEngine;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Roster getRoster() {
        return roster;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId getSelfId() {
        return selfId;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <T extends State> AutoCloseableWrapper<T> getLatestImmutableState(@NonNull String reason) {
        final ReservedSignedState reservedSignedState = immutableState.getAndReserve(reason);
        return new AutoCloseableWrapper<>(
                reservedSignedState.isNull()
                        ? null
                        : (T) reservedSignedState.get().getState(),
                reservedSignedState::close);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean createTransaction(@NonNull final byte[] transaction) {
        // Transaction creation always fails
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        immutableState.clear();
        notificationEngine.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        // no-op
    }
}
