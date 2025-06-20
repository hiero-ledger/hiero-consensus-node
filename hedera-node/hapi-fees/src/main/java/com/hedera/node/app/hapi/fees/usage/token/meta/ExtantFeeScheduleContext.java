// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

public record ExtantFeeScheduleContext(long expiry, int numBytesInFeeScheduleRepr) {}
