// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

/// @title IClprConnector
/// @notice Interface for connector contracts. The CLPR Service calls into the
///         connector on the source ledger to authorize outbound messages, and
///         on the destination ledger to notify it of inbound messages.
interface IClprConnector {
    /// @notice Authorize an outbound message. Called by ClprService during sendMessage.
    /// @dev The implementing contract IS the source connector -- the connector knows
    ///      its own identity, so no connector address parameter is passed.
    /// @param channelId The channel the message will be sent on
    /// @param targetApplication The destination application address bytes
    /// @param sender The sender address bytes
    /// @param messageData The message payload bytes
    /// @return authorized True if the connector approves the message
    function authorizeOutboundMessage(
        bytes32 channelId,
        bytes calldata targetApplication,
        bytes calldata sender,
        bytes calldata messageData
    ) external returns (bool authorized);

    /// @notice Pay the protocol for inbound message execution.
    /// @dev Called by ClprService during bundle processing, before the
    ///      inbound notification, after the connector's balance has been verified
    ///      sufficient. The connector MUST transfer exactly `amount` wei to
    ///      `msg.sender` (the manager forwards to the bundle submitter). Reverting
    ///      or transferring less than `amount` causes the message to be marked
    ///      `CONNECTOR_UNDERFUNDED` and the connector to be slashed.
    ///
    ///      Implementations SHOULD restrict callers to the manager (the address
    ///      they were registered with), otherwise any caller can drain the
    ///      contract's operating balance.
    /// @param amount Wei to transfer back to `msg.sender`.
    function payForExecution(uint256 amount) external;

    /// @notice Notify the connector that an inbound message has been processed and
    ///         the connector charged. Best-effort: reverts are caught by the protocol.
    /// @dev Called with a bounded gas stipend (economicConfig.connectorInboundGasStipend).
    ///      The implementing contract IS the destination connector.
    /// @param channelId The channel the message arrived on
    /// @param messageId The destination-side message ID
    /// @param sender The sender address bytes
    /// @param targetApplication The target application address bytes
    /// @param messageData The message payload bytes
    function onInboundMessage(
        bytes32 channelId,
        uint64 messageId,
        bytes calldata sender,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external;
}
