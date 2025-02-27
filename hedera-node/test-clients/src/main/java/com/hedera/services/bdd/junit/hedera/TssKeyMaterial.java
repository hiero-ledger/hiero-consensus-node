// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A summary of all the TSS key material that could be in a {@link Network}.
 *
 * @param ledgerId the ledger ID
 * @param tssMessages the TSS messages
 */
public record TssKeyMaterial(@NonNull Bytes ledgerId, @NonNull List<TssMessageTransactionBody> tssMessages) {}
