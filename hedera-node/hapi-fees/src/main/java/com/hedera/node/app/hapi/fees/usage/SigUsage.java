// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

public record SigUsage(int numSigs, int sigsSize, int numPayerKeys) {}
