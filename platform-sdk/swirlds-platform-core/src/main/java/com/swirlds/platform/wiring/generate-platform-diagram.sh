#!/usr/bin/env bash

# The location were this script can be found.
SCRIPT_PATH="$(dirname "$(readlink -f "$0")")"

# You must install mermaid to use this script.
# npm install -g @mermaid-js/mermaid-cli@10.9.1
# With newer versions, the diagram generated may not look as expected.

# Add the flag "--less-mystery" to add back labels for mystery input wires (noisy diagram warning)

../../../../../../../../swirlds-cli/pcli.sh diagram \
    -l 'TransactionPrehandler:futures:TransactionHandler' \
    -l 'ConsensusEventStream:future hash:TransactionHandler' \
    -s 'EventWindowManager:event window:🌀' \
    -s 'Heartbeat:heartbeat:❤️' \
    -s 'TransactionPrehandler:futures:🔮' \
    -s 'pcesReplayer:done streaming pces:✅' \
    -s 'InlinePcesWriter:events to gossip:📬' \
    -s 'extractOldestMinimumBirthRoundOnDisk:minimum identifier to store:📀' \
    -s 'EventCreationManager:non-validated events:🍎' \
    -s 'Mystery Input:mystery data:❔' \
    -s 'StateSigner:submit transaction:🖋️' \
    -s 'StateSigner:signature transactions:🖋️' \
    -s 'IssDetectorSplitter:IssNotification:💥' \
    -s 'IssDetector:ISS notification monitoring:💀' \
    -s 'ConsensusRoundsSplitter:monitor consensus round:🕐' \
    -s 'LatestCompleteStateNotifier:complete state notification:💢' \
    -s 'RunningEventHashOverride:hash override:💨' \
    -s 'StateSnapshotManager:state saving monitoring:💾' \
    -s 'PlatformMonitor:PlatformStatus:🚦' \
    -s 'HealthMonitor:health info:🏥' \
    -g 'Orphan Buffer:OrphanBuffer,OrphanBufferSplitter' \
    -g 'Event Intake:EventHasher,InternalEventValidator,EventDeduplicator,EventSignatureValidator,Orphan Buffer,InlinePcesWriter' \
    -g 'Consensus Engine:ConsensusEngine,EventWindowManager,ConsensusRounds,PreConsensusEvents,PreConsensusEventsSplitter,ConsensusRoundsSplitter,RoundsToCesEvents,staleEvents,staleEventsSplitter' \
    -g 'State Snapshot Manager:saveToDiskFilter,StateSnapshotManager,extractOldestMinimumBirthRoundOnDisk,toNotification' \
    -g 'State File Management:State Snapshot Manager,📀,💾' \
    -g 'State Signature Collector:StateSignatureCollector,reservedStateSplitter,allStatesReserver,completeStateFilter,completeStatesReserver,LatestCompleteStateNotifier' \
    -g 'State Signature Collection:State Signature Collector,LatestCompleteStateNexus,💢' \
    -g 'Event Creation:EventCreationManager,🍎' \
    -g 'ISS Detector:IssDetector,IssDetectorSplitter,IssHandler' \
    -g 'PCES Replay:pcesReplayer,✅' \
    -g 'Transaction Handler:TransactionHandler,notNullStateFilter,postHandler_stateWithHashComplexityReserver,postHandler_stateWithHashComplexityToStateReserver,SavedStateController' \
    -g 'State Hasher:StateHasher,postHasher_stateReserver' \
    -g 'Consensus:Consensus Engine,ConsensusEventStream,🌀,🕐' \
    -g 'State Verification:StateSigner,HashLogger,ISS Detector,ExecutionSignatureSubmission,🖋️,💥,💀' \
    -g 'Transaction Handling:Transaction Handler,LatestImmutableStateNexus,TransactionPrehandler,getSystemTransactions,🔮' \
    -g 'Branch Detection:BranchDetector,BranchReporter' \
    -g 'Miscellaneous:Mystery Input,RunningEventHashOverride,HealthMonitor,SignedStateSentinel,PlatformMonitor,Heartbeat,ExecutionStatusHandler,❔,🏥,❤️,💨,🚦' \
    -c 'Orphan Buffer' \
    -c 'Consensus Engine' \
    -c 'State Signature Collector' \
    -c 'State Snapshot Manager' \
    -c 'Transaction Handler' \
    -c 'State Hasher' \
    -c 'ISS Detector' \
    -c 'Wait For Crash Durability' \
    -c 'Stale Event Detector' \
    -c 'Transaction Resubmitter' \
    -c 'Branch Detection' \
    -o "${SCRIPT_PATH}/../../../../../../../../docs/core/wiring-diagram.svg"
