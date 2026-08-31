// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * Parse and payer-verification results from ingest, so this node can skip that work in pre-handle
 * for a transaction it already accepted.
 *
 * @param txInfo the parsed transaction
 * @param verifiedPayerKey the payer key ingest already verified, or {@code null} if ingest did not
 * @param payerResults completed verification futures for keys ingest already checked
 * @param configVersion the node configuration version used during ingest
 */
public record IngestHandoff(
        @NonNull TransactionInfo txInfo,
        @Nullable Key verifiedPayerKey,
        @Nullable Map<Key, SignatureVerificationFuture> payerResults,
        long configVersion) {}
