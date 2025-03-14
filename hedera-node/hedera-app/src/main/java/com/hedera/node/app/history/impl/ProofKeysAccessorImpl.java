// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ProofKeysAccessorImpl implements ProofKeysAccessor {
    private final HistoryLibrary library;
    private SigningAndVerifyingSchnorrKeys schnorrKeyPair;

    @Inject
    public ProofKeysAccessorImpl(@NonNull final HistoryLibrary library) {
        this.library = requireNonNull(library);
    }

    @Override
    public Bytes sign(final long constructionId, @NonNull final Bytes message) {
        return library.signSchnorr(message, Bytes.wrap(schnorrKeyPair.signingKey()));
    }

    @Override
    public TssKeyPair getOrCreateSchnorrKeyPair(final long constructionId) {
        schnorrKeyPair = library.newSchnorrKeyPair();
        return new TssKeyPair(Bytes.wrap(schnorrKeyPair.signingKey()), Bytes.wrap(schnorrKeyPair.verifyingKey()));
    }
}
