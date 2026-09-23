// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

interface IClprApplication {
    function onClprMessage(
        bytes32 channelId,
        bytes calldata sender,
        bytes calldata messageData
    ) external returns (bytes memory responseData);

    function onClprResponse(
        bytes32 channelId,
        uint64 messageId,
        uint8 status,
        bytes calldata responseData
    ) external;
}

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

contract PingPong is IClprApplication, IClprConnector {
    address constant CLPR_PRECOMPILE = address(0x16e);

    /// Per-message bookkeeping populated on serve() so onClprResponse can bounce.
    /// connectorByMessage[m] := connectorId used when m was last served
    /// targetByMessage[m]    := target application address used when m was last served
    mapping(bytes => bytes32) public connectorByMessage;
    mapping(bytes => bytes) public targetByMessage;

    event MessageReceived(bytes32 indexed channelId, bytes sender, bytes messageData);
    event MessageDropped(bytes32 indexed channelId, bytes messageData);
    event ResponseReceived(bytes32 indexed channelId, uint64 messageId, uint8 status, bytes responseData);
    event Bounced(bytes32 indexed channelId, bytes messageData);

    /// Payable so `contracts create --initial-balance` can credit the contract
    /// at construction time without reverting.
    constructor() payable {}

    /// Payable catch-all so the contract can accept HBAR top-ups after creation.
    fallback() external payable {}

    receive() external payable {}

    /// Pass-through authorization: approve every outbound message. Called
    /// by the CLPR precompile during sendMessage when PingPong is registered
    /// as the channel's connector_contract.
    function authorizeOutboundMessage(
        bytes32,
        bytes calldata,
        bytes calldata,
        bytes calldata
    ) external pure override returns (bool) {
        return true;
    }

    /// Transfer the exact requested execution fee back to the CLPR manager.
    function payForExecution(uint256 amount) external override {
        (bool success, ) = msg.sender.call{value: amount}("");
        require(success, "CLPR execution payment failed");
    }

    /// Connector-level inbound notification hook. PingPong handles inbound
    /// application behavior via onClprMessage, so this hook is intentionally empty.
    function onInboundMessage(
        bytes32,
        uint64,
        bytes calldata,
        bytes calldata,
        bytes calldata
    ) external override {}

    /// Inbound application callback. With ~25% probability (PREVRANDAO-driven)
    /// the message is "dropped": we return empty bytes so the originator's
    /// onClprResponse sees an empty responseData and will NOT bounce. The
    /// remaining ~75% returns the original messageData (classic echo).
    function onClprMessage(
        bytes32 channelId,
        bytes calldata sender,
        bytes calldata messageData
    ) external returns (bytes memory responseData) {
        emit MessageReceived(channelId, sender, messageData);
        if (uint256(block.prevrandao) % 4 == 0) {
            emit MessageDropped(channelId, messageData);
            return new bytes(0);
        }
        return messageData;
    }

    /// Inbound response callback. For a non-empty responseData (i.e. peer didn't drop),
    /// with ~75% probability (PREVRANDAO-driven) re-serve a copy of the same payload
    /// using the connector + target stored on the original serve(). Empty responseData
    /// terminates the volley unconditionally.
    function onClprResponse(
        bytes32 channelId,
        uint64 messageId,
        uint8 status,
        bytes calldata responseData
    ) external {
        emit ResponseReceived(channelId, messageId, status, responseData);
        if (responseData.length == 0) {
            return;
        }
        if (uint256(block.prevrandao) % 4 == 0) {
            emit MessageDropped(channelId, responseData);
            return;
        }
        bytes32 connectorId = connectorByMessage[responseData];
        bytes memory targetApplication = targetByMessage[responseData];
        if (connectorId == bytes32(0) || targetApplication.length == 0) {
            return;
        }
        _serve(channelId, connectorId, targetApplication, responseData);
        emit Bounced(channelId, responseData);
    }

    /// Kick off a volley: forward a single `sendMessage` call to the CLPR system
    /// contract precompile at 0x16e. Records (connectorId, targetApplication)
    /// keyed by messageData so a subsequent onClprResponse can bounce.
    /// @param channelId       32-byte channel identifier
    /// @param connectorId        32-byte connector identifier
    /// @param targetApplication  application address bytes on the peer ledger
    /// @param messageData        opaque application payload
    /// @return messageId         assigned message sequence number
    function serve(
        bytes32 channelId,
        bytes32 connectorId,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external returns (uint64 messageId) {
        connectorByMessage[messageData] = connectorId;
        targetByMessage[messageData] = targetApplication;
        return _serve(channelId, connectorId, targetApplication, messageData);
    }

    /// Internal helper used by both the operator-facing serve() and the
    /// onClprResponse bounce path. Calldata is unavailable on the bounce path
    /// (we read from storage), so this accepts `memory` args.
    function _serve(
        bytes32 channelId,
        bytes32 connectorId,
        bytes memory targetApplication,
        bytes memory messageData
    ) internal returns (uint64 messageId) {
        (bool success, bytes memory result) = CLPR_PRECOMPILE.call(
            abi.encodeWithSignature(
                "sendMessage(bytes32,bytes32,bytes,bytes)",
                channelId,
                connectorId,
                targetApplication,
                messageData
            )
        );
        require(success, "CLPR sendMessage failed");
        messageId = abi.decode(result, (uint64));
    }
}
