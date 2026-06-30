#!/usr/bin/env bash

# The location were this script can be found.
SCRIPT_PATH="$(dirname "$(readlink -f "$0")")"

# You must install mermaid to use this script.
# npm install -g @mermaid-js/mermaid-cli@10.9.1
# With newer versions, the diagram generated may not look as expected.

# Add the flag "--less-mystery" to add back labels for mystery input wires (noisy diagram warning)

# Emoji legend — each icon below replaces a (spammy) edge via a "-s" substitution.
# The icon appears as a badge on every component that receives that edge.
#   🌀  event window broadcast (EventWindowManager)
#   ❤️  heartbeat tick
#   🔮  transaction-prehandle futures (TransactionPrehandler)
#   ✅  PCES replay complete ("done streaming pces")
#   📀  minimum birth-round identifier to store on disk
#   ❔  mystery data (unbound input wire; see --less-mystery)
#   💥  ISS notification → app notifier
#   💀  ISS notification → status monitoring
#   🕐  consensus round monitoring
#   💨  running event hash override
#   💾  state-saving monitoring (StateSnapshotManager)
#   🚦  platform status
#   🏥  health info (HealthMonitor)
#   #️⃣  hashed states out of the State Hasher

../../../../../../../../swirlds-cli/pcli.sh diagram \
    -l 'TransactionPrehandler:futures:TransactionHandler' \
    -l 'ConsensusEventStream:future hash:TransactionHandler' \
    -s 'EventWindowManager:event window:🌀' \
    -s 'Heartbeat:heartbeat:❤️' \
    -s 'TransactionPrehandler:futures:🔮' \
    -s 'pcesReplayer:done streaming pces:✅' \
    -s 'extractOldestMinimumBirthRoundOnDisk:minimum identifier to store:📀' \
    -s 'Mystery Input:mystery data:❔' \
    -s 'IssDetectorSplitter:IssNotification:💥' \
    -s 'IssDetectorSplitter:ISS notification monitoring:💀' \
    -s 'ConsensusRoundsSplitter:monitor consensus round:🕐' \
    -s 'RunningEventHashOverride:hash override:💨' \
    -s 'StateSnapshotManager:state saving monitoring:💾' \
    -s 'PlatformMonitor:PlatformStatus:🚦' \
    -s 'HealthMonitor:health info:🏥' \
    -g 'Orphan Buffer:OrphanBuffer,OrphanBufferSplitter' \
    -g 'Branch Detection:BranchDetector,BranchReporter' \
    -g 'Event Intake Module:EventWindowDispatcher,ClearCommandDispatcher,EventHasher,InternalEventValidator,EventDeduplicator,EventSignatureValidator,Orphan Buffer,Branch Detection' \
    -g 'Consensus Engine:ConsensusEngine,RoundsToCesEvents' \
    -g 'State Snapshot Manager:saveToDiskFilter,StateSnapshotManager,extractOldestMinimumBirthRoundOnDisk,toNotification' \
    -g 'State Signature Collector:StateSignatureCollector,reservedStateSplitter,allStatesReserver,completeStateFilter' \
    -g 'State Hasher:StateHasher,postHasher_stateReserver' \
    -g 'Signed State Management:State Hasher,State Snapshot Manager,State Signature Collector,LatestCompleteStateNexus,StateGarbageCollector,StateSigner,HashLogger,ExecutionSignatureSubmission,postHasher_notifier,📀,💾' \
    -g 'Event Creator Module:EventCreationManager' \
    -g 'ISS Detector:IssDetector,IssDetectorSplitter,IssHandler' \
    -g 'PCES Module:pcesReplayer,InlinePcesWriter,✅' \
    -g 'Transaction Handler:TransactionHandler,notNullStateFilter,postHandler_stateWithHashComplexityReserver,postHandler_stateWithHashComplexityToStateReserver,SavedStateController' \
    -g 'Hashgraph Module:Consensus Engine,consensusRounds,ConsensusRoundsSplitter,staleEventsSplitter,staleEvents,staleEventCallback,PreConsensusEvents,PreConsensusEventsSplitter,EventWindowManager,🌀,🕐' \
    -g 'ISS Detection:ISS Detector,💥,💀' \
    -g 'Transaction Handling:Transaction Handler,LatestImmutableStateNexus,TransactionPrehandler,getSystemTransactions,🔮' \
    -g 'Miscellaneous:Mystery Input,RunningEventHashOverride,HealthMonitor,SignedStateSentinel,PlatformMonitor,Heartbeat,ExecutionStatusHandler,AppNotifier,executionHealthInput,❔,🏥,❤️,💨,🚦' \
    -g 'Gossip Module:gossip' \
    -g 'Event Stream:ConsensusEventStream' \
    -c 'Orphan Buffer' \
    -c 'Consensus Engine' \
    -c 'State Signature Collector' \
    -c 'State Snapshot Manager' \
    -c 'Transaction Handler' \
    -c 'State Hasher' \
    -c 'ISS Detector' \
    -c 'Branch Detection' \
    -o "${SCRIPT_PATH}/../../../../../../../../docs/core/wiring-diagram.svg"
