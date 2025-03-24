// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.signing;

import com.swirlds.common.crypto.Signature;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;

/**
 * A default implementation of {@link SelfEventSigner}.
 */
public class DefaultSelfEventSigner implements SelfEventSigner {

    private final KeysAndCerts keysAndCerts;

    /**
     * Constructor.
     *
     * @param keysAndCerts the platform's keys and certificates
     */
    public DefaultSelfEventSigner(@NonNull final KeysAndCerts keysAndCerts) {
        this.keysAndCerts = Objects.requireNonNull(keysAndCerts);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformEvent signEvent(@NonNull final UnsignedEvent event) {
        final Signature signature = new PlatformSigner(keysAndCerts).sign(event.getHash());
        return new PlatformEvent(event, signature.getBytes());
    }
}
