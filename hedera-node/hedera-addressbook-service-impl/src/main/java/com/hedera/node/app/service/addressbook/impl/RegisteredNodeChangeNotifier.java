// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publisher for {@link RegisteredNodeChangeListener} events. Used by the registered-node transaction handlers to
 * broadcast that the registered-node state map has been mutated.
 *
 * <p>The publisher itself does not maintain any "dirty" state — it just dispatches synchronously to every listener
 * that has registered, in registration order. Listeners are expected to be cheap (see
 * {@link RegisteredNodeChangeListener} for the threading contract).
 *
 * <p>The dispatcher swallows exceptions thrown by listeners so a single misbehaving subscriber cannot abort the
 * consensus handle for everyone else. Errors are logged at WARN.
 */
@Singleton
public class RegisteredNodeChangeNotifier {

    private static final Logger logger = LogManager.getLogger(RegisteredNodeChangeNotifier.class);

    private final CopyOnWriteArrayList<RegisteredNodeChangeListener> listeners = new CopyOnWriteArrayList<>();

    @Inject
    public RegisteredNodeChangeNotifier() {
        // no-op
    }

    /**
     * Subscribes the given listener to subsequent {@link #notifyChanged()} calls. Registration is idempotent: the
     * same listener instance will not be added twice.
     */
    public void register(@NonNull final RegisteredNodeChangeListener listener) {
        requireNonNull(listener, "listener must not be null");
        listeners.addIfAbsent(listener);
    }

    /**
     * Unsubscribes a previously registered listener. No-op if the listener was not registered.
     */
    public void unregister(@NonNull final RegisteredNodeChangeListener listener) {
        requireNonNull(listener, "listener must not be null");
        listeners.remove(listener);
    }

    /**
     * Dispatches a change event to every registered listener. Called by the registered-node handlers on the
     * consensus handle thread after a successful state mutation.
     */
    public void notifyChanged() {
        for (final RegisteredNodeChangeListener listener : listeners) {
            try {
                listener.onRegisteredNodeChanged();
            } catch (final RuntimeException e) {
                logger.warn("RegisteredNodeChangeListener threw onRegisteredNodeChanged; ignoring", e);
            }
        }
    }
}
