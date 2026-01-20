# Metrics for Block Node Streaming

The consensus node emits several metrics related to interactions with the block nodes. All metrics use the "blockStream"
category. These metrics use the standard metrics implementation used by the consensus node application and no additional
configuration is required to access them.

## Buffer Metrics

These metrics relate to the block buffer and are identified by the prefix "buffer".

|               Metric Name               |      Type       |                                     Description                                     |
|-----------------------------------------|-----------------|-------------------------------------------------------------------------------------|
| `blockStream_buffer_saturation`         | Gauge (double)  | Latest saturation of the block buffer as a percent (0.0 to 100.0)                   |
| `blockStream_buffer_latestBlockOpened`  | Gauge (long)    | The block number that was most recently opened                                      |
| `blockStream_buffer_latestBlockAcked`   | Gauge (long)    | The block number that was most recently acknowledged                                |
| `blockStream_buffer_numBlocksPruned`    | Counter         | Number of blocks pruned in the latest buffer pruning cycle                          |
| `blockStream_buffer_numBlocksOpened`    | Counter         | Number of blocks opened/created in the block buffer                                 |
| `blockStream_buffer_numBlocksClosed`    | Counter         | Number of blocks closed in the block buffer                                         |
| `blockStream_buffer_numBlocksMissing`   | Counter         | Number of attempts to retrieve a block from the block buffer but it was missing     |
| `blockStream_buffer_backPressureState`  | Gauge (long)    | Current state of back pressure (0=disabled, 1=action-stage, 2=recovering, 3=active) |
| `blockStream_buffer_oldestBlock`        | Gauge (long)    | After pruning, the oldest block in the buffer                                       |
| `blockStream_buffer_newestBlock`        | Gauge (long)    | After pruning, the newest block in the buffer                                       |
| `blockStream_buffer_blockItemsPerBlock` | Running average | Average number of BlockItems per block                                              |
| `blockStream_buffer_blockItemBytes`     | Running average | Average size in bytes of a BlockItem                                                |
| `blockStream_buffer_blockBytes`         | Running average | Average size in bytes of a Block                                                    |

## Connectivity Metrics

These metrics relate to general connectivity events between the consensus node and the block node. They are prefixed
with "conn" for identification.

|                    Metric Name                     |      Type       |                                              Description                                               |
|----------------------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------|
| `blockStream_conn_onComplete`                      | Counter         | Number of onComplete handler invocations on block node connections                                     |
| `blockStream_conn_onError`                         | Counter         | Number of onError handler invocations on block node connections                                        |
| `blockStream_conn_opened`                          | Counter         | Number of block node connections opened                                                                |
| `blockStream_conn_closed`                          | Counter         | Number of block node connections closed                                                                |
| `blockStream_conn_createFailure`                   | Counter         | Number of times establishing a block node connection failed                                            |
| `blockStream_conn_activeConnIp`                    | Gauge (long)    | The IP address of the active block node connection (Note: The IP address is converted to an integer)   |
| `blockStream_conn_endOfStreamLimitExceeded`        | Counter         | Number of times an active connection has exceeded the allowed number of EndOfStream responses          |
| `blockStream_conn_highLatencyEvents`               | Counter         | Count of high latency events from the active block node connection                                     |
| `blockStream_conn_headerSentToAckLatency`          | Running average | Average latency (ms) between streaming a BlockHeader and receiving its BlockAcknowledgement            |
| `blockStream_conn_headerProducedToAckLatency`      | Running average | Average latency (ms) between producing a BlockHeader and receiving its BlockAcknowledgement            |
| `blockStream_conn_blockEndSentToAckLatency`        | Running average | Average latency (ms) between streaming a BlockEnd and receiving its BlockAcknowledgement               |
| `blockStream_conn_blockClosedToAckLatency`         | Running average | Average latency (ms) between the block closing (Proof produced) and receiving its BlockAcknowledgement |
| `blockStream_conn_pipelineOperationTimeoutCounter` | Counter         | Number of times a pipeline operation has occured                                                       |

## Connection Receive Metrics

These metrics relate to responses received from a block node. They are identified using the "connRecv" prefix.

|                    Metric Name                     |     Type     |                          Description                           |
|----------------------------------------------------|--------------|----------------------------------------------------------------|
| `blockStream_connRecv_unknown`                     | Counter      | Number of responses received that are of unknown types         |
| `blockStream_connRecv_acknowledgement`             | Counter      | Number of Acknowledgement responses received                   |
| `blockStream_connRecv_skipBlock`                   | Counter      | Number of SkipBlock responses received                         |
| `blockStream_connRecv_resendBlock`                 | Counter      | Number of ResendBlock responses received                       |
| `blockStream_connRecv_nodeBehindPublisher`         | Counter      | Number of BehindPublisher responses received                   |
| `blockStream_connRecv_latestBlockEndOfStream`      | Gauge (long) | The latest block number received in an EndOfStream response    |
| `blockStream_connRecv_latestBlockSkipBlock`        | Gauge (long) | The latest block number received in a SkipBlock response       |
| `blockStream_connRecv_latestBlockResendBlock`      | Gauge (long) | The latest block number received in a ResendBlock response     |
| `blockStream_connRecv_latestBlockBehindPublisher`  | Gauge (long) | The latest block number received in a BehindPublisher response |
| `blockStream_connRecv_endStream_success`           | Counter      | Number of EndStream.Success responses received                 |
| `blockStream_connRecv_endStream_invalidRequest`    | Counter      | Number of EndStream.InvalidRequest responses received          |
| `blockStream_connRecv_endStream_error`             | Counter      | Number of EndStream.Error responses received                   |
| `blockStream_connRecv_endStream_timeout`           | Counter      | Number of EndStream.Timeout responses received                 |
| `blockStream_connRecv_endStream_duplicateBlock`    | Counter      | Number of EndStream.DuplicateBlock responses received          |
| `blockStream_connRecv_endStream_badBlockProof`     | Counter      | Number of EndStream.BadBlockProof responses received           |
| `blockStream_connRecv_endStream_persistenceFailed` | Counter      | Number of EndStream.PersistenceFailed responses received       |

## Connection Send Metrics

These metrics relate to the requests sent from the consensus node to a block node. They are identified using the
"connSend" prefix.

|                  Metric Name                  |      Type       |                           Description                            |
|-----------------------------------------------|-----------------|------------------------------------------------------------------|
| `blockStream_connSend_blockItemCount`         | Counter         | Number of individual block items sent to a block node            |
| `blockStream_connSend_blockItems`             | Counter         | Number of BlockItems requests sent                               |
| `blockStream_connSend_endStream_reset`        | Counter         | Number of EndStream.Reset requests sent                          |
| `blockStream_connSend_endStream_timeout`      | Counter         | Number of EndStream.Timeout requests sent                        |
| `blockStream_connSend_endStream_error`        | Counter         | Number of EndStream.Error requests sent                          |
| `blockStream_connSend_endStream_tooFarBehind` | Counter         | Number of EndStream.TooFarBehind requests sent                   |
| `blockStream_connSend_failure`                | Counter         | Number of requests sent to block nodes that failed               |
| `blockStream_connSend_requestSendLatency`     | Running average | Average latency (ms) for a PublishStreamRequest to be sent       |
| `blockStream_connSend_latestBlockEndOfBlock`  | Gauge (long)    | The latest block number for which an EndOfBlock request was sent |
| `blockStream_connSend_streamingBlockNumber`   | Gauge (long)    | The current block number this connection is streaming            |
| `blockStream_connSend_requestBytes`           | Running average | Average size in bytes of a PublishStreamRequest                  |
| `blockStream_connSend_requestBlockItemCount`  | Running average | Average number of BlockItems in a PublishStreamRequest           |

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

| Severity |                Metric Name                 |     Alert Condition     |
|----------|--------------------------------------------|-------------------------|
| L        | `blockStream_conn_headerSentToAckLatency`  | If value exceeds 2200ms |
| L        | `blockStream_conn_blockClosedToAckLatency` | If value exceeds 500ms  |
| L        | `blockStream_connSend_requestSendLatency`  | If value exceeds 100ms  |
