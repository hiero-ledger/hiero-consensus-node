// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.consensus;

public record SubmitMessageMeta(int numMsgBytes, long numCustomFees) {}
