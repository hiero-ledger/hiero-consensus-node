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

package com.hedera.node.app.history.impl;

import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides access to the Schnorr key pairs generated for use in metadata proof constructions.
 */
public interface ProofKeysAccessor {
    /**
     * Signs the given message with the Schnorr key this node should use for the given construction id.
     * @param constructionId the active construction ID
     * @param message the message to sign
     * @return the signature, using the Schnorr private key for the given construction ID
     */
    Bytes sign(long constructionId, @NonNull Bytes message);

    /**
     * Returns the Schnorr key pair this node should use starting with the given construction id,
     * creating the key pair if necessary.
     * @param constructionId the active construction ID
     * @return the Schnorr key pair
     */
    TssKeyPair getOrCreateSchnorrKeyPair(long constructionId);
}
