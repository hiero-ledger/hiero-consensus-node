// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import com.hederahashgraph.api.proto.java.SubType;

public class TokenBurnMeta extends TokenBurnWipeMeta {
    public TokenBurnMeta(final int bpt, final SubType subType, final long transferRecordRb, final int serialNumsCount) {
        super(bpt, subType, transferRecordRb, serialNumsCount);
    }
}
