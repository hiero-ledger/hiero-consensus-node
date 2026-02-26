// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { ClprTypes } from "../types/ClprTypes.sol";
import { IClprMiddleware } from "../interfaces/IClprMiddleware.sol";
import { IClprSourceApplication } from "../interfaces/IClprApplication.sol";

/// @title Source Application for CLPR
/// @author Hashgraph
/// @notice Reference application that sends requests via middleware and records responses.
/// @dev This contract models an "application" that chooses a connector and a destination application id.
///      For the MVP connector work, it also demonstrates connector failover by trying a list of
///      connectors in priority order when a send attempt is rejected.
contract SourceApplication is IClprSourceApplication {
    /// @notice Thrown when middleware callback methods are called by non-middleware callers.
    error MiddlewareOnly();

    /// @notice Thrown when contract is constructed with an invalid middleware address.
    error InvalidMiddleware();

    /// @notice Thrown when contract is constructed with an invalid destination application address.
    error InvalidDestinationApplication();

    /// @notice Thrown when contract is constructed with an invalid source connector address.
    error InvalidConnectorId();

    /// @notice Thrown when constructed with an invalid max charge unit.
    error InvalidMaxChargeUnit();

    /// @notice Thrown when constructed with an empty connector preference list.
    error NoConnectors();

    /// @notice Local middleware used for send/response operations.
    address public immutable middleware;

    /// @notice Destination application id to include in outbound `ClprApplicationMessage`.
    address public immutable destinationApplicationId;

    /// @notice Default maximum charge the application is willing to pay for remote execution (destination unit).
    uint256 public immutable defaultMaxChargeValue;

    /// @notice Default maximum charge unit identifier (destination unit).
    string public defaultMaxChargeUnit;

    /// @notice Connector preference list (source-side connector identifiers).
    bytes32[] private _connectorIds;

    /// @notice Index of the currently-preferred connector within `_connectorIds`.
    uint256 public currentConnectorIndex;

    /// @notice Latest application message id for which a response has been delivered.
    uint64 public lastReceivedAppMsgId;

    /// @notice Stored responses keyed by application message id.
    mapping(uint64 => bytes) private _responseByAppMsgId;

    /// @notice Emitted when a message is sent through middleware.
    event SendAttempted(
        uint64 indexed appMsgId,
        bytes32 indexed connectorId,
        ClprTypes.ClprSendStatus status,
        ClprTypes.ClprSendFailureReason failureReason,
        ClprTypes.ClprSendFailureSide failureSide,
        bytes payload
    );

    /// @notice Emitted when a response is delivered through middleware.
    event ResponseReceived(uint64 indexed appMsgId, bytes payload);

    /// @param middlewareAddress Local middleware instance used for send and response callbacks.
    /// @param destinationApplication Destination application id (contract address) on the remote ledger.
    /// @param connectorIds Connector preference list (source connector identifiers).
    /// @param maxChargeValue Default max charge value (destination unit) included in all outbound messages.
    /// @param maxChargeUnit Default max charge unit identifier.
    constructor(
        address middlewareAddress,
        address destinationApplication,
        bytes32[] memory connectorIds,
        uint256 maxChargeValue,
        string memory maxChargeUnit
    ) {
        if (middlewareAddress == address(0)) revert InvalidMiddleware();
        if (destinationApplication == address(0)) revert InvalidDestinationApplication();
        if (connectorIds.length == 0) revert NoConnectors();
        for (uint256 i = 0; i < connectorIds.length; i++) {
            if (connectorIds[i] == bytes32(0)) revert InvalidConnectorId();
        }
        if (bytes(maxChargeUnit).length == 0) revert InvalidMaxChargeUnit();
        middleware = middlewareAddress;
        destinationApplicationId = destinationApplication;
        _connectorIds = connectorIds;
        defaultMaxChargeValue = maxChargeValue;
        defaultMaxChargeUnit = maxChargeUnit;
    }

    /// @dev Restricts callbacks to the configured middleware.
    modifier onlyMiddleware() {
        if (msg.sender != middleware) revert MiddlewareOnly();
        _;
    }

    /// @notice Sends a payload through middleware using the currently-preferred connector.
    /// @dev If the send attempt is rejected, advances the connector index by one (best-effort) so
    ///      the next call will try the next connector.
    /// @param payload Opaque application payload.
    /// @return status Immediate middleware send status.
    function send(bytes calldata payload) external returns (ClprTypes.ClprSendMessageStatus memory status) {
        bytes32 connectorId = _connectorIds[currentConnectorIndex];
        status = _sendOnce(connectorId, payload);
        if (status.status == ClprTypes.ClprSendStatus.Rejected && currentConnectorIndex + 1 < _connectorIds.length) {
            currentConnectorIndex++;
        }
    }

    /// @notice Sends a payload through middleware, failing over across the configured connector list.
    /// @param payload Opaque application payload.
    /// @return status The first accepted send status, or the final rejection status if all connectors reject.
    function sendWithFailover(
        bytes calldata payload
    ) external returns (ClprTypes.ClprSendMessageStatus memory status) {
        uint256 idx = currentConnectorIndex;
        while (idx < _connectorIds.length) {
            bytes32 connectorId = _connectorIds[idx];
            status = _sendOnce(connectorId, payload);
            if (status.status == ClprTypes.ClprSendStatus.Accepted) {
                currentConnectorIndex = idx;
                return status;
            }
            idx++;
        }

        // All connectors rejected; clamp index to the last connector to avoid out-of-bounds reads.
        currentConnectorIndex = _connectorIds.length - 1;
    }

    /// @notice Sends a payload through middleware, always trying connectors from index 0 on each call.
    /// @dev This matches the "always prefer connector 1, then 2, then 3" behavior used in SOLO scenarios.
    /// @param payload Opaque application payload.
    /// @return status The first accepted send status, or the final rejection status if all connectors reject.
    function sendWithFailoverFromFirst(
        bytes calldata payload
    ) external returns (ClprTypes.ClprSendMessageStatus memory status) {
        for (uint256 idx = 0; idx < _connectorIds.length; idx++) {
            bytes32 connectorId = _connectorIds[idx];
            status = _sendOnce(connectorId, payload);
            if (status.status == ClprTypes.ClprSendStatus.Accepted) {
                return status;
            }
        }
    }

    /// @notice Returns the connector preference list.
    function getConnectorIds() external view returns (bytes32[] memory connectorIds) {
        connectorIds = _connectorIds;
    }

    /// @notice Returns a previously-delivered response payload for `appMsgId`.
    function getResponse(uint64 appMsgId) external view returns (bytes memory payload) {
        payload = _responseByAppMsgId[appMsgId];
    }

    /// @inheritdoc IClprSourceApplication
    function handleResponse(
        ClprTypes.ClprApplicationResponse calldata response,
        uint64 appMsgId
    ) external onlyMiddleware {
        lastReceivedAppMsgId = appMsgId;
        _responseByAppMsgId[appMsgId] = response.data;
        emit ResponseReceived(appMsgId, response.data);
    }

    function _sendOnce(
        bytes32 connectorId,
        bytes calldata payload
    ) private returns (ClprTypes.ClprSendMessageStatus memory status) {
        status = IClprMiddleware(middleware).send(
            ClprTypes.ClprApplicationMessage({
                recipientId: destinationApplicationId,
                connectorId: connectorId,
                maxCharge: ClprTypes.ClprAmount({value: defaultMaxChargeValue, unit: defaultMaxChargeUnit}),
                data: payload
            })
        );
        emit SendAttempted(status.appMsgId, connectorId, status.status, status.failureReason, status.failureSide, payload);
    }
}
