// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

interface IClprSystemContract {
    /// Enqueue a cross-ledger message on the specified channel.
    /// @param channelId 32-byte channel identifier
    /// @param connectorId 32-byte connector identifier (keccak256(channelId || pubKey || salt))
    /// @param targetApplication destination application address bytes on the peer ledger
    /// @param messageData opaque application payload
    /// @return messageId the assigned message sequence number
    function sendMessage(
        bytes32 channelId,
        bytes32 connectorId,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external returns (uint64 messageId);

    /// Returns queue delivery counters for a channel.
    /// @param channelId 32-byte channel identifier
    /// @return receivedMessageId total inbound messages processed (advances on each received bundle)
    /// @return ackedMessageId total outbound messages acknowledged (advances when peer confirms delivery)
    function getChannelQueueState(bytes32 channelId)
        external
        returns (uint64 receivedMessageId, uint64 ackedMessageId);
}
