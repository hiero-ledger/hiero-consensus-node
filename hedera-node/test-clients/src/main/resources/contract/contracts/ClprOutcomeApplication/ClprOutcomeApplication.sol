// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IClprOutcomeRouter {
    function sendMessage(bytes32, bytes32, bytes calldata, bytes calldata) external returns (uint64);
}

contract ClprOutcomeChild {}

/** Stateful test fixture: execution effects and compensation must agree with the reported outcome. */
contract ClprOutcomeApplication {
    bytes32 private immutable connectorId;
    uint256 public executions;
    uint256 public ordinaryResponses;
    uint256 public unavailableResponses;
    uint8 public lastStatus;
    uint256 public lastResponseLength;
    uint256 public compensations;

    constructor(bytes32 id) { connectorId = id; }

    function onClprMessage(bytes32 channelId, bytes calldata, bytes calldata messageData)
        external returns (bytes memory)
    {
        (uint256 length, uint8 mode, bool nestedSend) = abi.decode(messageData, (uint256, uint8, bool));
        executions++;
        if (nestedSend) {
            new ClprOutcomeChild();
            IClprOutcomeRouter(address(0x16e)).sendMessage(
                channelId, connectorId, abi.encodePacked(address(this)), new bytes(0));
        }
        require(mode != 1, "requested revert after effects");
        if (mode == 2) {
            // Invalid dynamic-bytes ABI: an offset without the length/data it points to.
            assembly { mstore(0, 32) return(0, 32) }
        }
        if (mode == 3) return abi.encode(gasleft());
        return new bytes(length);
    }

    function onClprResponse(bytes32, uint64, uint8 status, bytes calldata responseData) external {
        ordinaryResponses++;
        lastStatus = status;
        lastResponseLength = responseData.length;
        if (status == 1) compensations++;
    }

    function onClprResponseUnavailable(bytes32, uint64, uint8 status) external {
        unavailableResponses++;
        lastStatus = status;
        lastResponseLength = 0;
        if (status == 1) compensations++;
    }

    function snapshot() external view returns (uint256, uint256, uint256, uint8, uint256, uint256) {
        return (executions, ordinaryResponses, unavailableResponses, lastStatus, lastResponseLength, compensations);
    }
}
