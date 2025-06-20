// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

public record BaseTransactionMeta(int memoUtf8Bytes, int numExplicitTransfers) {}
