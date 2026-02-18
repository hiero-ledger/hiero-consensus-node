// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { ClprTypes } from "../types/ClprTypes.sol";
import { IClprDestinationApplication } from "../interfaces/IClprApplication.sol";

/// @title Echo Application for CLPR
/// @author Hashgraph
/// @notice Reference destination-side application that echoes inbound payloads as response payloads.
/// @dev Used to validate middleware and queue semantics during IT1-CONN-AUTH.
contract EchoApplication is IClprDestinationApplication {
    /// @notice Thrown when middleware callback methods are called by non-middleware callers.
    error MiddlewareOnly();

    /// @notice Thrown when contract is constructed with an invalid middleware address.
    error InvalidMiddleware();

    /// @notice Local middleware that is allowed to invoke this contract.
    address public immutable middleware;

    /// @notice Number of inbound requests processed.
    uint64 public requestCount;

    /// @notice Emitted when this app handles an inbound message.
    event MessageHandled(bytes32 indexed connectorId, bytes payload);

    /// @param middlewareAddress Local middleware instance that invokes `handleMessage`.
    constructor(address middlewareAddress) {
        if (middlewareAddress == address(0)) revert InvalidMiddleware();
        middleware = middlewareAddress;
    }

    /// @dev Restricts callbacks to the configured middleware.
    modifier onlyMiddleware() {
        if (msg.sender != middleware) revert MiddlewareOnly();
        _;
    }

    /// @inheritdoc IClprDestinationApplication
    function handleMessage(
        ClprTypes.ClprApplicationMessage calldata message
    ) external onlyMiddleware returns (ClprTypes.ClprApplicationResponse memory response) {
        requestCount++;

        emit MessageHandled(message.connectorId, message.data);
        response = ClprTypes.ClprApplicationResponse({data: message.data});
    }
}
