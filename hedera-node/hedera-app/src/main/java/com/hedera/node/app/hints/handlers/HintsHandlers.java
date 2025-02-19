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

package com.hedera.node.app.hints.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Bundles the handlers for the {@link com.hedera.node.app.hints.HintsService}.
 * @param keyPublicationHandler the handler for key publication
 * @param preprocessingVoteHandler the handler for preprocessing votes
 * @param partialSignatureHandler the handler for partial signatures
 * @param crsPublicationHandler the handler for CRS publication
 */
public record HintsHandlers(
        @NonNull HintsKeyPublicationHandler keyPublicationHandler,
        @NonNull HintsPreprocessingVoteHandler preprocessingVoteHandler,
        @NonNull HintsPartialSignatureHandler partialSignatureHandler,
        @NonNull CrsPublicationHandler crsPublicationHandler) {
    public HintsHandlers {
        requireNonNull(keyPublicationHandler);
        requireNonNull(preprocessingVoteHandler);
        requireNonNull(partialSignatureHandler);
        requireNonNull(crsPublicationHandler);
    }
}
