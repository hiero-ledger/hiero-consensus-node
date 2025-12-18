// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.impl.ReadableClprMetadataStoreImpl;
import org.hiero.interledger.clpr.impl.WritableClprMetadataStoreImpl;
import org.junit.jupiter.api.Test;

class ClprMetadataStoreTest {

    @Test
    void persistsAndReadsMetadata() {
        final var backing = new AtomicReference<ClprLocalLedgerMetadata>();
        final var singletonState = new FunctionWritableSingletonState<>(
                CLPR_LEDGER_METADATA_STATE_ID, CLPR_LEDGER_METADATA_STATE_KEY, backing::get, backing::set);
        final var writableStates =
                MapWritableStates.builder().state(singletonState).build();
        final var readableStates =
                MapReadableStates.builder().state(singletonState).build();

        final var writableStore = new WritableClprMetadataStoreImpl(writableStates);
        final var readableStore = new ReadableClprMetadataStoreImpl(readableStates);

        assertNull(readableStore.get());

        final var metadata = ClprLocalLedgerMetadata.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap("local-ledger".getBytes()))
                        .build())
                .rosterHash(Bytes.wrap("roster-hash".getBytes()))
                .build();

        writableStore.put(metadata);

        assertEquals(metadata, readableStore.get());
    }
}
