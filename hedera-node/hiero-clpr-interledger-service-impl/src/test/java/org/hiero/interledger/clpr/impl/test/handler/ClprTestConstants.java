// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

public class ClprTestConstants {
    public static final ClprLedgerId MOCK_LEDGER_ID = ClprLedgerId.newBuilder()
            .ledgerId(Bytes.wrap(Arrays.copyOf("test-ledger-id".getBytes(), 32)))
            .build();
    public static final ClprLedgerId LOCAL_LEDGER_ID = ClprLedgerId.newBuilder()
            .ledgerId(Bytes.wrap(Arrays.copyOf("local-ledger-id".getBytes(), 32)))
            .build();
    public static final ClprLedgerId REMOTE_LEDGER_ID = ClprLedgerId.newBuilder()
            .ledgerId(Bytes.wrap(Arrays.copyOf("remote-ledger-id".getBytes(), 32)))
            .build();
    public static final Bytes MOCK_RUNNING_HASH = Bytes.wrap(new byte[32]);
}
