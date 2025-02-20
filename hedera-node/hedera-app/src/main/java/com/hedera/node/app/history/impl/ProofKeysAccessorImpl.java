// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ProofKeysAccessorImpl implements ProofKeysAccessor {
    private static final Bytes FAKE_SCHNORR_PRIVATE_KEY = Bytes.wrap("FAKE_SCHNORR_PRIVATE_KEY");
    private static final Bytes FAKE_SCHNORR_PUBLIC_KEY = Bytes.wrap("FAKE_SCHNORR_PUBLIC_KEY");
    private static final TssKeyPair FAKE_SCHNORR_KEY_PAIR =
            new TssKeyPair(FAKE_SCHNORR_PRIVATE_KEY, FAKE_SCHNORR_PUBLIC_KEY);

    private final HistoryLibrary library;

    @Inject
    public ProofKeysAccessorImpl(@NonNull final HistoryLibrary library) {
        this.library = requireNonNull(library);
    }

    @Override
    public Bytes sign(final long constructionId, @NonNull final Bytes message) {
        return library.signSchnorr(message, FAKE_SCHNORR_PRIVATE_KEY);
    }

    @Override
    public TssKeyPair getOrCreateSchnorrKeyPair(final long constructionId) {
        return FAKE_SCHNORR_KEY_PAIR;
    }
}
