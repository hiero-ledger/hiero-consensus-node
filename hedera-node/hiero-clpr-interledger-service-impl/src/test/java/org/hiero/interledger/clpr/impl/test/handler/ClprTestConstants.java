// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

public class ClprTestConstants {
    public static final ClprLedgerId MOCK_LEDGER_ID =
            ClprLedgerId.newBuilder().ledgerId(Bytes.wrap("test-ledger-id")).build();
    public static final ClprLedgerId LOCAL_LEDGER_ID =
            ClprLedgerId.newBuilder().ledgerId(Bytes.wrap("local-ledger-id")).build();
    public static final ClprLedgerId REMOTE_LEDGER_ID =
            ClprLedgerId.newBuilder().ledgerId(Bytes.wrap("remote-ledger-id")).build();
    public static final Bytes MOCK_RUNNING_HASH = Bytes.wrap(new byte[32]);
}
