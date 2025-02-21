// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class HintsKeyAccessorImpl implements HintsKeyAccessor {
    private static final Bytes FAKE_BLS_PRIVATE_KEY = Bytes.wrap("FAKE_BLS_PRIVATE_KEY");
    private static final Bytes FAKE_BLS_PUBLIC_KEY = Bytes.wrap("FAKE_BLS_PUBLIC_KEY");
    private static final TssKeyPair FAKE_BLS_KEY_PAIR = new TssKeyPair(FAKE_BLS_PRIVATE_KEY, FAKE_BLS_PUBLIC_KEY);

    private final HintsLibrary library;

    @Inject
    public HintsKeyAccessorImpl(@NonNull final HintsLibrary library) {
        this.library = requireNonNull(library);
    }

    @Override
    public Bytes signWithBlsPrivateKey(final long constructionId, @NonNull final Bytes message) {
        return library.signBls(message, FAKE_BLS_PRIVATE_KEY);
    }

    @Override
    public TssKeyPair getOrCreateBlsKeyPair(final long constructionId) {
        return FAKE_BLS_KEY_PAIR;
    }
}
