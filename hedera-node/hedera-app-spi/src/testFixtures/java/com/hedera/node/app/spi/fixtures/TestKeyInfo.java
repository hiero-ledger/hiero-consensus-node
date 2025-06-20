// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.Key;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/** Holds information related to keys used in test {@link com.hedera.node.app.spi.fixtures.Scenarios} */
public record TestKeyInfo(
        @NonNull Bytes privateKey, @NonNull Key publicKey, @NonNull Key uncompressedPublicKey, @Nullable Bytes alias) {}
