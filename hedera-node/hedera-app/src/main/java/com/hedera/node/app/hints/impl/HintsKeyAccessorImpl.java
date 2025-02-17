/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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
