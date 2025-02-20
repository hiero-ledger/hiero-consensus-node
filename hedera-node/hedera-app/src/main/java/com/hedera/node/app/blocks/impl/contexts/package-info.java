// SPDX-License-Identifier: Apache-2.0
/**
 * This package includes {@link com.hedera.node.app.blocks.impl.contexts.BaseOpContext} and a few other specialized
 * contexts to support translating the block items for a {@link com.hedera.hapi.node.base.TransactionID} into a "legacy"
 * {@link com.hedera.hapi.node.transaction.TransactionRecord}. This is necessary because the record has information
 * that can only be deduced from the block items if the translator repeats deserialization work and <i>also</i>
 * maintains some extra context about the network state which may not be available at the time we want to translate the
 * items.
 */
package com.hedera.node.app.blocks.impl.contexts;
