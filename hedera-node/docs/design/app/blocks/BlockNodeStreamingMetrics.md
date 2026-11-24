# Metrics for Block Node Streaming

The consensus node emits several metrics related to interactions with the block nodes. All metrics use the "blockStream"
category. These metrics use the standard metrics implementation used by the consensus node application and no additional
configuration is required to access them.

## Buffer Metrics

These metrics relate to the block buffer and are identified by the prefix "buffer".

|                   Metric Name                   |      Type       |                                     Description                                     |
|-------------------------------------------------|-----------------|-------------------------------------------------------------------------------------|
| `blockStream_buffer_backPressureState`          | Gauge (long)    | Current state of back pressure (0=disabled, 1=action-stage, 2=recovering, 3=active) |
| `blockStream_buffer_blockBytes`                 | Running average | The average size in bytes of a Block in the buffer                                  |
| `blockStream_buffer_blockItemBytes`             | Running average | The average size in bytes of a BlockItem in the buffer                              |
| `blockStream_buffer_blockItemsPerBlock`         | Running average | The average number of BlockItems per block in the buffer                            |
| `blockStream_buffer_inProgressBlocksSaturation` | Gauge (double)  | The percent (0.0 to 100.0) of blocks that have been opened but not yet closed       |
| `blockStream_buffer_latestBlockAcked`           | Gauge (long)    | The block number that was most recently acknowledged                                |
| `blockStream_buffer_latestBlockOpened`          | Gauge (long)    | The block number that was most recently opened                                      |
| `blockStream_buffer_newestBlock`                | Gauge (long)    | After pruning, the newest block in the buffer                                       |
| `blockStream_buffer_numBlocks`                  | Gauge (long)    | The number of blocks currently held in the block buffer                             |
| `blockStream_buffer_numBlocksClosed`            | Counter         | Number of blocks closed in the block buffer                                         |
| `blockStream_buffer_numBlocksMissing`           | Counter         | Number of attempts to retrieve a block from the block buffer but it was missing     |
| `blockStream_buffer_numBlocksOpened`            | Counter         | Number of blocks opened/created in the block buffer                                 |
| `blockStream_buffer_numBlocksPendingAck`        | Gauge (long)    | The number of blocks buffered that are pending acknowledgement                      |
| `blockStream_buffer_numBlocksPruned`            | Counter         | Number of blocks pruned in the latest buffer pruning cycle                          |
| `blockStream_buffer_oldestBlock`                | Gauge (long)    | After pruning, the oldest block in the buffer                                       |
| `blockStream_buffer_saturation`                 | Gauge (double)  | The overall maximum buffer saturation as a percent (0.0 to 100.0)                   |
| `blockStream_buffer_totalBytes`                 | Gauge (long)    | The total size of the serialized data held by the block buffer (in bytes)           |
| `blockStream_buffer_unackedBlocksSaturation`    | Gauge (double)  | The percent (0.0 to 100.0) of buffered blocks that haven't been acknowledged        |
| `blockStream_buffer_unackedBytesSaturation`     | Gauge (double)  | The percent (0.0 to 100.0) of buffered bytes that haven't been acknowledged         |

## Connectivity Metrics

These metrics relate to general connectivity events between the consensus node and the block node. They are prefixed
with "conn" for identification.

|                           Metric Name                            |      Type       |                                                Description                                                |
|------------------------------------------------------------------|-----------------|-----------------------------------------------------------------------------------------------------------|
| `blockStream_conn_activeConnIp`                                  | Gauge (long)    | IP address (in integer format) of the currently active streaming block node connection                    |
| `blockStream_conn_activeObservingConnectionCount`                | Gauge (long)    | Current number of active acknowledgement observing connections to block nodes                             |
| `blockStream_conn_activeStreamingConnectionCount`                | Gauge (long)    | Current number of active streaming connections to block nodes                                             |
| `blockStream_conn_blockClosedToAckLatency`                       | Running average | The average latency (µs) between the block being complete and receiving its BlockAcknowledgement          |
| `blockStream_conn_blockEndSentToAckLatency`                      | Running average | The average latency (µs) between streaming a BlockEnd and receiving its BlockAcknowledgement              |
| `blockStream_conn_endOfStreamLimitExceeded`                      | Counter         | Number of times the active block node connection has exceeded the allowed number of EndOfStream responses |
| `blockStream_conn_headerProducedToAckLatency`                    | Running average | The average latency (µs) between producing a BlockHeader and receiving its BlockAcknowledgement           |
| `blockStream_conn_headerSentToAckLatency`                        | Running average | The average latency (µs) between streaming a BlockHeader and receiving its BlockAcknowledgement           |
| `blockStream_conn_headerSentToBlockEndSentLatency`               | Running average | The average latency (µs) between streaming a BlockHeader and streaming the corresponding BlockEnd         |
| `blockStream_conn_highLatencyEvents`                             | Counter         | Count of high latency events from the active block node connection                                        |
| `blockStream_observingConn_closed`                               | Counter         | Number of block node observing connections closed                                                         |
| `blockStream_observingConn_connClose_blockNodeBehind`            | Counter         | Number of observing connections closed with reason BLOCK_NODE_BEHIND                                      |
| `blockStream_observingConn_connClose_blockNodeHighLatency`       | Counter         | Number of observing connections closed with reason BLOCK_NODE_HIGH_LATENCY                                |
| `blockStream_observingConn_connClose_bufferSaturation`           | Counter         | Number of observing connections closed with reason BUFFER_SATURATION                                      |
| `blockStream_observingConn_connClose_configUpdate`               | Counter         | Number of observing connections closed with reason CONFIG_UPDATE                                          |
| `blockStream_observingConn_connClose_connectionError`            | Counter         | Number of observing connections closed with reason CONNECTION_ERROR                                       |
| `blockStream_observingConn_connClose_endStreamReceived`          | Counter         | Number of observing connections closed with reason END_STREAM_RECEIVED                                    |
| `blockStream_observingConn_connClose_higherPriorityFound`        | Counter         | Number of observing connections closed with reason HIGHER_PRIORITY_FOUND                                  |
| `blockStream_observingConn_connClose_idleReceive`                | Counter         | Number of observing connections closed with reason IDLE_RECEIVE                                           |
| `blockStream_observingConn_connClose_internalError`              | Counter         | Number of observing connections closed with reason INTERNAL_ERROR                                         |
| `blockStream_observingConn_connClose_newConnection`              | Counter         | Number of observing connections closed with reason NEW_CONNECTION                                         |
| `blockStream_observingConn_connClose_obsolete`                   | Counter         | Number of observing connections closed with reason OBSOLETE                                               |
| `blockStream_observingConn_connClose_periodicReset`              | Counter         | Number of observing connections closed with reason PERIODIC_RESET                                         |
| `blockStream_observingConn_connClose_shutdown`                   | Counter         | Number of observing connections closed with reason SHUTDOWN                                               |
| `blockStream_observingConn_connClose_timeout`                    | Counter         | Number of observing connections closed with reason TIMEOUT                                                |
| `blockStream_observingConn_connClose_tooManyEndStreamResponses`  | Counter         | Number of observing connections closed with reason TOO_MANY_END_STREAM_RESPONSES                          |
| `blockStream_observingConn_connClose_transientEndStreamReceived` | Counter         | Number of observing connections closed with reason TRANSIENT_END_STREAM_RECEIVED                          |
| `blockStream_observingConn_connClose_unknown`                    | Counter         | Number of observing connections closed with reason UNKNOWN                                                |
| `blockStream_observingConn_createFailure`                        | Counter         | Number of times establishing a block node observing connection failed                                     |
| `blockStream_observingConn_onComplete`                           | Counter         | Number of onComplete handler invocations on block node observing connections                              |
| `blockStream_observingConn_onError`                              | Counter         | Number of onError handler invocations on block node observing connections                                 |
| `blockStream_observingConn_opened`                               | Counter         | Number of block node observing connections opened                                                         |
| `blockStream_observingConn_operationTimeout`                     | Counter         | Number of times an operation timed out on a block node observing connection                               |
| `blockStream_serviceConn_closed`                                 | Counter         | Number of block node service connections closed                                                           |
| `blockStream_serviceConn_connClose_blockNodeBehind`              | Counter         | Number of service connections closed with reason BLOCK_NODE_BEHIND                                        |
| `blockStream_serviceConn_connClose_blockNodeHighLatency`         | Counter         | Number of service connections closed with reason BLOCK_NODE_HIGH_LATENCY                                  |
| `blockStream_serviceConn_connClose_bufferSaturation`             | Counter         | Number of service connections closed with reason BUFFER_SATURATION                                        |
| `blockStream_serviceConn_connClose_configUpdate`                 | Counter         | Number of service connections closed with reason CONFIG_UPDATE                                            |
| `blockStream_serviceConn_connClose_connectionError`              | Counter         | Number of service connections closed with reason CONNECTION_ERROR                                         |
| `blockStream_serviceConn_connClose_endStreamReceived`            | Counter         | Number of service connections closed with reason END_STREAM_RECEIVED                                      |
| `blockStream_serviceConn_connClose_higherPriorityFound`          | Counter         | Number of service connections closed with reason HIGHER_PRIORITY_FOUND                                    |
| `blockStream_serviceConn_connClose_idleReceive`                  | Counter         | Number of service connections closed with reason IDLE_RECEIVE                                             |
| `blockStream_serviceConn_connClose_internalError`                | Counter         | Number of service connections closed with reason INTERNAL_ERROR                                           |
| `blockStream_serviceConn_connClose_newConnection`                | Counter         | Number of service connections closed with reason NEW_CONNECTION                                           |
| `blockStream_serviceConn_connClose_obsolete`                     | Counter         | Number of service connections closed with reason OBSOLETE                                                 |
| `blockStream_serviceConn_connClose_periodicReset`                | Counter         | Number of service connections closed with reason PERIODIC_RESET                                           |
| `blockStream_serviceConn_connClose_shutdown`                     | Counter         | Number of service connections closed with reason SHUTDOWN                                                 |
| `blockStream_serviceConn_connClose_timeout`                      | Counter         | Number of service connections closed with reason TIMEOUT                                                  |
| `blockStream_serviceConn_connClose_tooManyEndStreamResponses`    | Counter         | Number of service connections closed with reason TOO_MANY_END_STREAM_RESPONSES                            |
| `blockStream_serviceConn_connClose_transientEndStreamReceived`   | Counter         | Number of service connections closed with reason TRANSIENT_END_STREAM_RECEIVED                            |
| `blockStream_serviceConn_connClose_unknown`                      | Counter         | Number of service connections closed with reason UNKNOWN                                                  |
| `blockStream_serviceConn_createFailure`                          | Counter         | Number of times establishing a block node service connection failed                                       |
| `blockStream_serviceConn_onComplete`                             | Counter         | Number of onComplete handler invocations on block node service connections                                |
| `blockStream_serviceConn_onError`                                | Counter         | Number of onError handler invocations on block node service connections                                   |
| `blockStream_serviceConn_opened`                                 | Counter         | Number of block node service connections opened                                                           |
| `blockStream_serviceConn_operationTimeout`                       | Counter         | Number of times an operation timed out on a block node service connection                                 |
| `blockStream_streamingConn_closed`                               | Counter         | Number of block node streaming connections closed                                                         |
| `blockStream_streamingConn_connClose_blockNodeBehind`            | Counter         | Number of streaming connections closed with reason BLOCK_NODE_BEHIND                                      |
| `blockStream_streamingConn_connClose_blockNodeHighLatency`       | Counter         | Number of streaming connections closed with reason BLOCK_NODE_HIGH_LATENCY                                |
| `blockStream_streamingConn_connClose_bufferSaturation`           | Counter         | Number of streaming connections closed with reason BUFFER_SATURATION                                      |
| `blockStream_streamingConn_connClose_configUpdate`               | Counter         | Number of streaming connections closed with reason CONFIG_UPDATE                                          |
| `blockStream_streamingConn_connClose_connectionError`            | Counter         | Number of streaming connections closed with reason CONNECTION_ERROR                                       |
| `blockStream_streamingConn_connClose_endStreamReceived`          | Counter         | Number of streaming connections closed with reason END_STREAM_RECEIVED                                    |
| `blockStream_streamingConn_connClose_higherPriorityFound`        | Counter         | Number of streaming connections closed with reason HIGHER_PRIORITY_FOUND                                  |
| `blockStream_streamingConn_connClose_idleReceive`                | Counter         | Number of streaming connections closed with reason IDLE_RECEIVE                                           |
| `blockStream_streamingConn_connClose_internalError`              | Counter         | Number of streaming connections closed with reason INTERNAL_ERROR                                         |
| `blockStream_streamingConn_connClose_newConnection`              | Counter         | Number of streaming connections closed with reason NEW_CONNECTION                                         |
| `blockStream_streamingConn_connClose_obsolete`                   | Counter         | Number of streaming connections closed with reason OBSOLETE                                               |
| `blockStream_streamingConn_connClose_periodicReset`              | Counter         | Number of streaming connections closed with reason PERIODIC_RESET                                         |
| `blockStream_streamingConn_connClose_shutdown`                   | Counter         | Number of streaming connections closed with reason SHUTDOWN                                               |
| `blockStream_streamingConn_connClose_timeout`                    | Counter         | Number of streaming connections closed with reason TIMEOUT                                                |
| `blockStream_streamingConn_connClose_tooManyEndStreamResponses`  | Counter         | Number of streaming connections closed with reason TOO_MANY_END_STREAM_RESPONSES                          |
| `blockStream_streamingConn_connClose_transientEndStreamReceived` | Counter         | Number of streaming connections closed with reason TRANSIENT_END_STREAM_RECEIVED                          |
| `blockStream_streamingConn_connClose_unknown`                    | Counter         | Number of streaming connections closed with reason UNKNOWN                                                |
| `blockStream_streamingConn_createFailure`                        | Counter         | Number of times establishing a block node streaming connection failed                                     |
| `blockStream_streamingConn_onComplete`                           | Counter         | Number of onComplete handler invocations on block node streaming connections                              |
| `blockStream_streamingConn_onError`                              | Counter         | Number of onError handler invocations on block node streaming connections                                 |
| `blockStream_streamingConn_opened`                               | Counter         | Number of block node streaming connections opened                                                         |
| `blockStream_streamingConn_operationTimeout`                     | Counter         | Number of times an operation timed out on a block node streaming connection                               |

## Connection Receive Metrics

These metrics relate to responses received from a block node. They are identified using the "connRecv" prefix.

|                         Metric Name                         |     Type     |                                            Description                                            |
|-------------------------------------------------------------|--------------|---------------------------------------------------------------------------------------------------|
| `blockStream_connRecv_latestBlockBehindPublisher`           | Gauge (long) | The latest block number received in a BehindPublisher response                                    |
| `blockStream_connRecv_latestBlockEndOfStream`               | Gauge (long) | The latest block number received in an EndOfStream response                                       |
| `blockStream_connRecv_latestBlockResendBlock`               | Gauge (long) | The latest block number received in a ResendBlock response                                        |
| `blockStream_connRecv_latestBlockSkipBlock`                 | Gauge (long) | The latest block number received in a SkipBlock response                                          |
| `blockStream_connRecv_responsesIgnoredAsObserving`          | Counter      | The number of ignored responses from the block node over observing connections                    |
| `blockStream_observingConnRecv_acknowledgement`             | Counter      | Number of 'Acknowledgement' responses received on block node observing connections                |
| `blockStream_observingConnRecv_endStream_badBlockProof`     | Counter      | Number of 'EndOfStream.BAD_BLOCK_PROOF' responses received on block node observing connections    |
| `blockStream_observingConnRecv_endStream_duplicateBlock`    | Counter      | Number of 'EndOfStream.DUPLICATE_BLOCK' responses received on block node observing connections    |
| `blockStream_observingConnRecv_endStream_error`             | Counter      | Number of 'EndOfStream.ERROR' responses received on block node observing connections              |
| `blockStream_observingConnRecv_endStream_invalidRequest`    | Counter      | Number of 'EndOfStream.INVALID_REQUEST' responses received on block node observing connections    |
| `blockStream_observingConnRecv_endStream_persistenceFailed` | Counter      | Number of 'EndOfStream.PERSISTENCE_FAILED' responses received on block node observing connections |
| `blockStream_observingConnRecv_endStream_success`           | Counter      | Number of 'EndOfStream.SUCCESS' responses received on block node observing connections            |
| `blockStream_observingConnRecv_endStream_timeout`           | Counter      | Number of 'EndOfStream.TIMEOUT' responses received on block node observing connections            |
| `blockStream_observingConnRecv_endStream_unknown`           | Counter      | Number of 'EndOfStream.UNKNOWN' responses received on block node observing connections            |
| `blockStream_observingConnRecv_endStream_unrecognized`      | Counter      | Number of 'EndOfStream.UNRECOGNIZED' responses received on block node observing connections       |
| `blockStream_observingConnRecv_nodeBehindPublisher`         | Counter      | Number of 'NodeBehindPublisher' responses received on block node observing connections            |
| `blockStream_observingConnRecv_resendBlock`                 | Counter      | Number of 'ResendBlock' responses received on block node observing connections                    |
| `blockStream_observingConnRecv_skipBlock`                   | Counter      | Number of 'SkipBlock' responses received on block node observing connections                      |
| `blockStream_observingConnRecv_unknown`                     | Counter      | Number of unknown response types received on block node observing connections                     |
| `blockStream_serviceConnRecv_acknowledgement`               | Counter      | Number of 'Acknowledgement' responses received on block node service connections                  |
| `blockStream_serviceConnRecv_endStream_badBlockProof`       | Counter      | Number of 'EndOfStream.BAD_BLOCK_PROOF' responses received on block node service connections      |
| `blockStream_serviceConnRecv_endStream_duplicateBlock`      | Counter      | Number of 'EndOfStream.DUPLICATE_BLOCK' responses received on block node service connections      |
| `blockStream_serviceConnRecv_endStream_error`               | Counter      | Number of 'EndOfStream.ERROR' responses received on block node service connections                |
| `blockStream_serviceConnRecv_endStream_invalidRequest`      | Counter      | Number of 'EndOfStream.INVALID_REQUEST' responses received on block node service connections      |
| `blockStream_serviceConnRecv_endStream_persistenceFailed`   | Counter      | Number of 'EndOfStream.PERSISTENCE_FAILED' responses received on block node service connections   |
| `blockStream_serviceConnRecv_endStream_success`             | Counter      | Number of 'EndOfStream.SUCCESS' responses received on block node service connections              |
| `blockStream_serviceConnRecv_endStream_timeout`             | Counter      | Number of 'EndOfStream.TIMEOUT' responses received on block node service connections              |
| `blockStream_serviceConnRecv_endStream_unknown`             | Counter      | Number of 'EndOfStream.UNKNOWN' responses received on block node service connections              |
| `blockStream_serviceConnRecv_endStream_unrecognized`        | Counter      | Number of 'EndOfStream.UNRECOGNIZED' responses received on block node service connections         |
| `blockStream_serviceConnRecv_nodeBehindPublisher`           | Counter      | Number of 'NodeBehindPublisher' responses received on block node service connections              |
| `blockStream_serviceConnRecv_resendBlock`                   | Counter      | Number of 'ResendBlock' responses received on block node service connections                      |
| `blockStream_serviceConnRecv_skipBlock`                     | Counter      | Number of 'SkipBlock' responses received on block node service connections                        |
| `blockStream_serviceConnRecv_unknown`                       | Counter      | Number of unknown response types received on block node service connections                       |
| `blockStream_streamingConnRecv_acknowledgement`             | Counter      | Number of 'Acknowledgement' responses received on block node streaming connections                |
| `blockStream_streamingConnRecv_endStream_badBlockProof`     | Counter      | Number of 'EndOfStream.BAD_BLOCK_PROOF' responses received on block node streaming connections    |
| `blockStream_streamingConnRecv_endStream_duplicateBlock`    | Counter      | Number of 'EndOfStream.DUPLICATE_BLOCK' responses received on block node streaming connections    |
| `blockStream_streamingConnRecv_endStream_error`             | Counter      | Number of 'EndOfStream.ERROR' responses received on block node streaming connections              |
| `blockStream_streamingConnRecv_endStream_invalidRequest`    | Counter      | Number of 'EndOfStream.INVALID_REQUEST' responses received on block node streaming connections    |
| `blockStream_streamingConnRecv_endStream_persistenceFailed` | Counter      | Number of 'EndOfStream.PERSISTENCE_FAILED' responses received on block node streaming connections |
| `blockStream_streamingConnRecv_endStream_success`           | Counter      | Number of 'EndOfStream.SUCCESS' responses received on block node streaming connections            |
| `blockStream_streamingConnRecv_endStream_timeout`           | Counter      | Number of 'EndOfStream.TIMEOUT' responses received on block node streaming connections            |
| `blockStream_streamingConnRecv_endStream_unknown`           | Counter      | Number of 'EndOfStream.UNKNOWN' responses received on block node streaming connections            |
| `blockStream_streamingConnRecv_endStream_unrecognized`      | Counter      | Number of 'EndOfStream.UNRECOGNIZED' responses received on block node streaming connections       |
| `blockStream_streamingConnRecv_nodeBehindPublisher`         | Counter      | Number of 'NodeBehindPublisher' responses received on block node streaming connections            |
| `blockStream_streamingConnRecv_resendBlock`                 | Counter      | Number of 'ResendBlock' responses received on block node streaming connections                    |
| `blockStream_streamingConnRecv_skipBlock`                   | Counter      | Number of 'SkipBlock' responses received on block node streaming connections                      |
| `blockStream_streamingConnRecv_unknown`                     | Counter      | Number of unknown response types received on block node streaming connections                     |

## Connection Send Metrics

These metrics relate to the requests sent from the consensus node to a block node. They are identified using the
"connSend" prefix.

|                       Metric Name                       |      Type       |                                         Description                                         |
|---------------------------------------------------------|-----------------|---------------------------------------------------------------------------------------------|
| `blockStream_connSend_blockItemCount`                   | Counter         | Number of individual block items sent to block nodes                                        |
| `blockStream_connSend_latestBlockEndOfBlock`            | Gauge (long)    | The latest block number for which an EndOfBlock request was sent                            |
| `blockStream_connSend_multiItemRequestExceedsSoftLimit` | Counter         | Number of requests that contain multiple items whose total size exceeds the soft limit size |
| `blockStream_connSend_requestBlockItemCount`            | Running average | The average number of BlockItems in a PublishStreamRequest                                  |
| `blockStream_connSend_requestBytes`                     | Running average | The average number of bytes in a PublishStreamRequest                                       |
| `blockStream_connSend_requestExceedsHardLimit`          | Counter         | Number of requests that exceed the hard limit size                                          |
| `blockStream_connSend_requestSendLatency`               | Running average | The average latency (microseconds) for a PublishStreamRequest to be sent to a block node    |
| `blockStream_connSend_streamingBlockNumber`             | Gauge (long)    | The current block number this connection is streaming                                       |
| `blockStream_observingConnSend_acknowledgeOnly`         | Counter         | Number of 'AcknowledgeOnly' requests sent on block node observing connections               |
| `blockStream_observingConnSend_blockItems`              | Counter         | Number of 'BlockItems' requests sent on block node observing connections                    |
| `blockStream_observingConnSend_endOfBlock`              | Counter         | Number of 'EndOfBlock' requests sent on block node observing connections                    |
| `blockStream_observingConnSend_endStream_error`         | Counter         | Number of 'EndStream.ERROR' requests sent on block node observing connections               |
| `blockStream_observingConnSend_endStream_reset`         | Counter         | Number of 'EndStream.RESET' requests sent on block node observing connections               |
| `blockStream_observingConnSend_endStream_timeout`       | Counter         | Number of 'EndStream.TIMEOUT' requests sent on block node observing connections             |
| `blockStream_observingConnSend_endStream_tooFarBehind`  | Counter         | Number of 'EndStream.TOO_FAR_BEHIND' requests sent on block node observing connections      |
| `blockStream_observingConnSend_endStream_unknown`       | Counter         | Number of 'EndStream.UNKNOWN' requests sent on block node observing connections             |
| `blockStream_observingConnSend_endStream_unrecognized`  | Counter         | Number of 'EndStream.UNRECOGNIZED' requests sent on block node observing connections        |
| `blockStream_observingConnSend_failure`                 | Counter         | Number of failed requests attempted using block node observing connections                  |
| `blockStream_serviceConnSend_acknowledgeOnly`           | Counter         | Number of 'AcknowledgeOnly' requests sent on block node service connections                 |
| `blockStream_serviceConnSend_blockItems`                | Counter         | Number of 'BlockItems' requests sent on block node service connections                      |
| `blockStream_serviceConnSend_endOfBlock`                | Counter         | Number of 'EndOfBlock' requests sent on block node service connections                      |
| `blockStream_serviceConnSend_endStream_error`           | Counter         | Number of 'EndStream.ERROR' requests sent on block node service connections                 |
| `blockStream_serviceConnSend_endStream_reset`           | Counter         | Number of 'EndStream.RESET' requests sent on block node service connections                 |
| `blockStream_serviceConnSend_endStream_timeout`         | Counter         | Number of 'EndStream.TIMEOUT' requests sent on block node service connections               |
| `blockStream_serviceConnSend_endStream_tooFarBehind`    | Counter         | Number of 'EndStream.TOO_FAR_BEHIND' requests sent on block node service connections        |
| `blockStream_serviceConnSend_endStream_unknown`         | Counter         | Number of 'EndStream.UNKNOWN' requests sent on block node service connections               |
| `blockStream_serviceConnSend_endStream_unrecognized`    | Counter         | Number of 'EndStream.UNRECOGNIZED' requests sent on block node service connections          |
| `blockStream_serviceConnSend_failure`                   | Counter         | Number of failed requests attempted using block node service connections                    |
| `blockStream_streamingConnSend_acknowledgeOnly`         | Counter         | Number of 'AcknowledgeOnly' requests sent on block node streaming connections               |
| `blockStream_streamingConnSend_blockItems`              | Counter         | Number of 'BlockItems' requests sent on block node streaming connections                    |
| `blockStream_streamingConnSend_endOfBlock`              | Counter         | Number of 'EndOfBlock' requests sent on block node streaming connections                    |
| `blockStream_streamingConnSend_endStream_error`         | Counter         | Number of 'EndStream.ERROR' requests sent on block node streaming connections               |
| `blockStream_streamingConnSend_endStream_reset`         | Counter         | Number of 'EndStream.RESET' requests sent on block node streaming connections               |
| `blockStream_streamingConnSend_endStream_timeout`       | Counter         | Number of 'EndStream.TIMEOUT' requests sent on block node streaming connections             |
| `blockStream_streamingConnSend_endStream_tooFarBehind`  | Counter         | Number of 'EndStream.TOO_FAR_BEHIND' requests sent on block node streaming connections      |
| `blockStream_streamingConnSend_endStream_unknown`       | Counter         | Number of 'EndStream.UNKNOWN' requests sent on block node streaming connections             |
| `blockStream_streamingConnSend_endStream_unrecognized`  | Counter         | Number of 'EndStream.UNRECOGNIZED' requests sent on block node streaming connections        |
| `blockStream_streamingConnSend_failure`                 | Counter         | Number of failed requests attempted using block node streaming connections                  |

## Alerting Recommendations

Alerting rules can be created based on these metrics to notify the operations team of potential issues.

Note: These alerts should only be enabled for consensus nodes that are configured to stream to block nodes (i.e., have a
`block-nodes.json` file on disk).

Utilizing Low (L), Medium (M) and High (H) severity levels, some recommended alerting rules to consider include:

**Buffer**: Alerts related to buffer saturation and backpressure

| Severity |                 Metric                 |      Alert Condition       |
|----------|----------------------------------------|----------------------------|
| L        | `blockStream_buffer_saturation`        | If value exceeds 10.0      |
| M        | `blockStream_buffer_saturation`        | If value exceeds 20.0      |
| H        | `blockStream_buffer_saturation`        | If value exceeds 30.0      |
| H        | `blockStream_buffer_backPressureState` | If value is not equal to 0 |

**Connectivity**: Alerts related to establishing and maintaining block node connections

| Severity |                   Metric                    |                          Alert Condition                           |
|----------|---------------------------------------------|--------------------------------------------------------------------|
| M        | `blockStream_conn_createFailure`            | If value exceeds 3 in the last 60s, otherwise, configure as needed |
| L        | `blockStream_conn_onError`                  | If value increases (non-zero delta)                                |
| M        | `blockStream_conn_onError`                  | If value exceeds 3 in the last 60s, otherwise, configure as needed |
| L        | `blockStream_conn_endOfStreamLimitExceeded` | If value increases (non-zero delta)                                |
| L        | `blockStream_conn_highLatencyEvents`        | If value increases (non-zero delta)                                |
| L        | `blockStream_conn_activeConnIp`             | If value is 0 (or missing) for 60s, otherwise, configure as needed |

**Connection Send**: Alerts related to requests sent to block nodes

| Severity |                    Metric                     |                          Alert Condition                           |
|----------|-----------------------------------------------|--------------------------------------------------------------------|
| M        | `blockStream_connSend_failure`                | If value exceeds 3 in the last 60s, otherwise, configure as needed |
| M        | `blockStream_connSend_endStream_timeout`      | If value increases (non-zero delta)                                |
| L        | `blockStream_connSend_endStream_error`        | If value increases (non-zero delta)                                |
| M        | `blockStream_connSend_endStream_error`        | If value exceeds 3 in the last 60s, otherwise, configure as needed |
| L        | `blockStream_connSend_endStream_tooFarBehind` | If value increases (non-zero delta)                                |
| L        | `blockStream_connSend_endStream_reset`        | If value increases (non-zero delta)                                |

**Messaging**: Alerts related to EndStream response failures (increased counts)

| Severity |                    Metric Name                     |                          Alert Condition                           |
|----------|----------------------------------------------------|--------------------------------------------------------------------|
| L        | `blockStream_connRecv_endStream_timeout`           | If value increases (non-zero delta)                                |
| L        | `blockStream_connRecv_endStream_badBlockProof`     | If value increases (non-zero delta)                                |
| H        | `blockStream_connRecv_endStream_badBlockProof`     | If value exceeds 3 in the last 60s, otherwise, configure as needed |
| L        | `blockStream_connRecv_endStream_persistenceFailed` | If value increases (non-zero delta)                                |
| H        | `blockStream_connRecv_endStream_persistenceFailed` | If value exceeds 3 in the last 60s, otherwise, configure as needed |
| L        | `blockStream_connRecv_endStream_error`             | If value increases (non-zero delta)                                |
| H        | `blockStream_connRecv_endStream_error`             | If value exceeds 3 in the last 60s, otherwise, configure as needed |

**Latency**: Alerts related to streaming latency (a block should generally be streamed and acknowledged within ~2.5s)

| Severity |                Metric Name                 |      Alert Condition       |
|----------|--------------------------------------------|----------------------------|
| L        | `blockStream_conn_headerSentToAckLatency`  | If value exceeds 2200000µs |
| L        | `blockStream_conn_blockClosedToAckLatency` | If value exceeds 500000µs  |
| L        | `blockStream_connSend_requestSendLatency`  | If value exceeds 100ms     |
