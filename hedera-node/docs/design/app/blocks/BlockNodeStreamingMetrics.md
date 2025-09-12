# Metrics for Block Node Streaming

The consensus node emits several metrics related to interactions with the block nodes. All metrics use the "blockStream"
category. These metrics use the standard metrics implementation used by the consensus node application and no additional
configuration is required to access them.

## Buffer Metrics

These metrics relate to the block buffer and are identified by the prefix "buffer".

|              Metric Name               |      Type      |                                     Description                                     |
|----------------------------------------|----------------|-------------------------------------------------------------------------------------|
| `blockStream_buffer_saturation`        | Gauge (double) | Latest saturation of the block buffer as a percent (0.0 to 100.0)                   |
| `blockStream_buffer_latestBlockOpened` | Gauge (long)   | The block number that was most recently opened                                      |
| `blockStream_buffer_latestBlockAcked`  | Gauge (long)   | The block number that was most recently acknowledged                                |
| `blockStream_buffer_numBlocksPruned`   | Counter        | Number of blocks opened/created in the block buffer                                 |
| `blockStream_buffer_numBlocksClosed`   | Counter        | Number of blocks closed in the block buffer                                         |
| `blockStream_buffer_numBlocksMissing`  | Counter        | Number of attempts to retrieve a block from the block buffer but it was missing     |
| `blockStream_buffer_backPressureState` | Gauge (long)   | Current state of back pressure (0=disabled, 1=action-stage, 2=recovering, 3=active) |
| `blockStream_buffer_oldestBlock`       | Gauge (long)   | After pruning, the oldest block in the buffer                                       |
| `blockStream_buffer_newestBlock`       | Gauge (long)   | After pruning, the newest block in the buffer                                       |

## Connectivity Metrics

These metrics relate to general connectivity events between the consensus node and the block node. They are prefixed
with "conn" for identification.

|                 Metric Name                 |     Type     |                                             Description                                              |
|---------------------------------------------|--------------|------------------------------------------------------------------------------------------------------|
| `blockStream_conn_onComplete`               | Counter      | Number of onComplete handler invocations on block node connections                                   |
| `blockStream_conn_onError`                  | Counter      | Number of onError handler invocations on block node connections                                      |
| `blockStream_conn_opened`                   | Counter      | Number of block node connections opened                                                              |
| `blockStream_conn_closed`                   | Counter      | Number of block node connections closed                                                              |
| `blockStream_conn_noActive`                 | Counter      | Number of times streaming a block was attempted but there was no active connection                   |
| `blockStream_conn_createFailure`            | Counter      | Number of times establishing a block node connection failed                                          |
| `blockStream_conn_activeConnIp`             | Gauge (long) | The IP address of the active block node connection (Note: The IP address is converted to an integer) |
| `blockStream_conn_endOfStreamLimitExceeded` | Counter      | Number of times an active connection has exceeded the allowed number of EndOfStream responses        |

## Connection Receive Metrics

These metrics relate to responses received from a block node. They are identified using the "connRecv" prefix.

|                    Metric Name                     |  Type   |                       Description                        |
|----------------------------------------------------|---------|----------------------------------------------------------|
| `blockStream_connRecv_unknown`                     | Counter | Number of responses received that are of unknown types   |
| `blockStream_connRecv_acknowledgement`             | Counter | Number of Acknowledgement responses received             |
| `blockStream_connRecv_skipBlock`                   | Counter | Number of SkipBlock responses received                   |
| `blockStream_connRecv_resendBlock`                 | Counter | Number of ResendBlock responses received                 |
| `blockStream_connRecv_endStream_success`           | Counter | Number of EndStream.Success responses received           |
| `blockStream_connRecv_endStream_invalidRequest`    | Counter | Number of EndStream.InvalidRequest responses received    |
| `blockStream_connRecv_endStream_error`             | Counter | Number of EndStream.Error responses received             |
| `blockStream_connRecv_endStream_timeout`           | Counter | Number of EndStream.Timeout responses received           |
| `blockStream_connRecv_endStream_duplicateBlock`    | Counter | Number of EndStream.DuplicateBlock responses received    |
| `blockStream_connRecv_endStream_badBlockProof`     | Counter | Number of EndStream.BadBlockProof responses received     |
| `blockStream_connRecv_endStream_behind`            | Counter | Number of EndStream.Behind responses received            |
| `blockStream_connRecv_endStream_persistenceFailed` | Counter | Number of EndStream.PersistenceFailed responses received |

## Connection Send Metrics

These metrics relate to the requests sent from the consensus node to a block node. They are identified using the
"connSend" prefix.

|                  Metric Name                  |  Type   |                      Description                      |
|-----------------------------------------------|---------|-------------------------------------------------------|
| `blockStream_connSend_blockItemCount`         | Counter | Number of individual block items sent to a block node |
| `blockStream_connSend_blockItems`             | Counter | Number of BlockItems requests sent                    |
| `blockStream_connSend_endStream_reset`        | Counter | Number of EndStream.Reset requests sent               |
| `blockStream_connSend_endStream_timeout`      | Counter | Number of EndStream.Timeout requests sent             |
| `blockStream_connSend_endStream_error`        | Counter | Number of EndStream.Error requests sent               |
| `blockStream_connSend_endStream_tooFarBehind` | Counter | Number of EndStream.TooFarBehind requests sent        |
