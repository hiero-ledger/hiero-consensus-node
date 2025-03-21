#!/usr/bin/env bash

# The location were this script can be found.
SCRIPT_PATH="$(dirname "$(readlink -f "$0")")"

# You must install mermaid to use this script.
# npm install -g @mermaid-js/mermaid-cli@10.9.1
# With newer versions, the diagram generated may not look as expected.

# Add the flag "--less-mystery" to add back labels for mystery input wires (noisy diagram warning)

pcli diagram \
    -l 'TransactionPrehandler:futures:TransactionHandler' \
    -l 'EventCreationManager:get transactions:TransactionPool' \
    -l 'ConsensusEventStream:future hash:TransactionHandler' \
    -s 'EventWindowManager:event window:🌀' \
    -s 'Heartbeat:heartbeat:❤️' \
    -s 'TransactionPrehandler:futures:🔮' \
    -s 'pcesReplayer:done streaming pces:✅' \
    -s 'InlinePcesWriter:events to gossip:📬' \
    -s 'extractOldestMinimumGenerationOnDisk:minimum identifier to store:📀' \
    -s 'StaleEventDetectorRouter:non-validated events:🍎' \
    -s 'Mystery Input:mystery data:❔' \
    -s 'StateSigner:submit transaction:🖋️' \
    -s 'StateSigner:signature transactions:🖋️' \
    -s 'IssDetectorSplitter:IssNotification:💥' \
    -s 'getStatusAction:PlatformStatusAction:💀' \
    -s 'LatestCompleteStateNotifier:complete state notification:💢' \
    -s 'RunningEventHashOverride:hash override:💨' \
    -s 'StaleEventDetectorRouter:publishStaleEvent:⚰️' \
    -s 'toStateWrittenToDiskAction:PlatformStatusAction:💾' \
    -s 'StatusStateMachine:PlatformStatus:🚦' \
    -s 'HealthMonitor:health info:🏥' \
    -g 'Orphan Buffer:OrphanBuffer,OrphanBufferSplitter' \
    -g 'Event Intake:EventHasher,InternalEventValidator,EventDeduplicator,EventSignatureValidator,Orphan Buffer' \
    -g 'Consensus Engine:ConsensusEngine,ConsensusEngineSplitter,EventWindowManager,getCesEvents' \
    -g 'State Snapshot Manager:saveToDiskFilter,StateSnapshotManager,extractOldestMinimumGenerationOnDisk,toStateWrittenToDiskAction,toNotification' \
    -g 'State File Management:State Snapshot Manager,📀,💾' \
    -g 'State Signature Collector:StateSignatureCollector,reservedStateSplitter,allStatesReserver,completeStateFilter,completeStatesReserver,LatestCompleteStateNotifier' \
    -g 'State Signature Collection:State Signature Collector,LatestCompleteStateNexus,💢' \
    -g 'Transaction Resubmitter:TransactionResubmitter' \
    -g 'Stale Event Detector:StaleEventDetector,StaleEventDetectorSplitter,StaleEventDetectorRouter' \
    -g 'Event Creation:EventCreationManager,TransactionPool,SelfEventSigner,Stale Event Detector,postSigner_encode_systemTransactions,Transaction Resubmitter,⚰️' \
    -g 'ISS Detector:IssDetector,IssDetectorSplitter,IssHandler,getStatusAction' \
    -g 'PCES Replay:pcesReplayer,✅' \
    -g 'Transaction Handler:TransactionHandler,notNullStateFilter,postHandler_stateWithHashComplexityReserver,postHandler_stateWithHashComplexityToStateReserver,SavedStateController' \
    -g 'State Hasher:StateHasher,postHasher_stateReserver' \
    -g 'Consensus:Consensus Engine,🌀' \
    -g 'State Verification:StateSigner,HashLogger,ISS Detector,🖋️,💥,💀' \
    -g 'Transaction Handling:Transaction Handler,LatestImmutableStateNexus' \
    -g 'Branch Detection:BranchDetector,BranchReporter' \
    -g 'Miscellaneous:Mystery Input,RunningEventHashOverride,HealthMonitor,SignedStateSentinel,StatusStateMachine,Heartbeat,❔,🏥,❤️,💨,🚦' \
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
