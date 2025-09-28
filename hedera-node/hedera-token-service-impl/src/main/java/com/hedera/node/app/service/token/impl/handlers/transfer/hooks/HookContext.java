// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Tuple;

/**
 * Context provided to a transfer with hook call. Includes the common information for all the hooks in the transfer including
 * proposed transfers, memo, and transaction fee.
 *
 * @param proposedTransfers the proposed transfers as a Tuple
 * @param memo the memo associated with the transaction
 * @param txnFee the transaction fee
 */
public record HookContext(Tuple proposedTransfers, String memo, long txnFee) {}
