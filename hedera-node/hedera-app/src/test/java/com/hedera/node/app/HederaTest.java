// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import com.swirlds.platform.test.fixtures.state.MerkleTestBase;

final class HederaTest extends MerkleTestBase {
    // Constructor: null registry throws
    // Constructor: bootstrap props throws
    // Constructor: Null version throws (pass the version in)
    // Constructor: #getSoftwareVersion returns the supplied version
    // Constructor: Verify constructable registry is used for registering MerkleStateRoot (and for services)
    // Constructor: Verify constructable registry that throws is handled (for services and for MerkleStateRoot)

    // TRY: Sending customized bootstrap props
    // TRY: Check logs?

    // newState: Called when there is no saved state, and when there is a saved state

    // onStateInitialized: Called when there is no saved state, and when there is a saved state
    // onStateInitialized: deserializedVersion is < current version
    // onStateInitialized: deserializedVersion is = current version
    // onStateInitialized: deserializedVersion is > current version
    // onStateInitialized: called for genesis
    // onStateInitialized: called for restart
    // onStateInitialized: called for reconnect (no error)
    // onStateInitialized: called for event stream recovery (no error)

    // onMigrate ONLY CALLED if deserializedVersion < current version or if genesis

    // init: Try to init with the system with something other than UTF-8 as the native charset (throws)
    // init: Try to init with sha384 not available (throws)
    // init: ensure JVM is set to UTF-8 after init
    // init: validateLedgerState ....
    // init: configurePlatform ....
    // init: exportAccountsIfDesired ....

    // run: start grpc server for old port
    // run: start grpc server for new port

    // genesis: onMigrate is called
    // genesis: seqStart comes from bootstrap props
    // genesis: results of createSpecialGenesisChildren
    // genesis: ... dagger ...
    // genesis: initializeFeeManager
    // genesis: initializeExchangeRateManager
    // genesis: initializeThrottleManager
    // genesis: version info is saved in state (and committed)
    // genesis: other stuff?....
    // genesis: updateStakeDetails....?
    // genesis: markPostUpradeScanStatus?....

    // createSpecialGenesisChildren: What if seqStart is < 0?
    // createSpecialGenesisChildren: other children...?

    // restart: update if needed
    // restart: dagger ....?
    // restart: housekeeping? freeze? etc.

    // rebuild aliases?
}
