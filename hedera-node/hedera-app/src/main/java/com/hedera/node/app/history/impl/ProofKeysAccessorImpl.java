/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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
