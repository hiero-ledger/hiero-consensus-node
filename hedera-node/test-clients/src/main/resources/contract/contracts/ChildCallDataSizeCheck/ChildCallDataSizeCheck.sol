// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

contract ChildCallDataSizeValidationContract {

    address constant HTS = address(0x167);
    address constant HAS = address(0x16a);
    address constant HSS = address(0x16b);

    function callAssociateTokens(
        address account,
        uint256 tokenCount
    ) external returns (bool success, bytes memory result) {
        bytes memory payload = generateAssociateTokensPayload(account, tokenCount);
        (success, result) = HTS.call(payload);
    }

    function generateAssociateTokensPayload(
        address account,
        uint256 tokenCount
    ) public pure returns (bytes memory) {
        bytes4 selector = bytes4(keccak256("associateTokens(address,address[])"));
        // ABI encoding layout:
        // 4  bytes - selector
        // 32 bytes - account
        // 32 bytes - offset to array (always 64 = 0x40)
        // 32 bytes - array length
        // 32 bytes - per array element (tokenCount elements)
        bytes memory payload = new bytes(4 + 32 + 32 + 32 + tokenCount * 32);
        // selector
        payload[0] = selector[0];
        payload[1] = selector[1];
        payload[2] = selector[2];
        payload[3] = selector[3];
        // account
        bytes32 accountEncoded = bytes32(uint256(uint160(account)));
        for (uint i = 0; i < 32; i++) {
            payload[4 + i] = accountEncoded[i];
        }
        // offset to array data (64 = 0x40)
        bytes32 offset = bytes32(uint256(64));
        for (uint i = 0; i < 32; i++) {
            payload[36 + i] = offset[i];
        }
        // array length
        bytes32 length = bytes32(tokenCount);
        for (uint i = 0; i < 32; i++) {
            payload[68 + i] = length[i];
        }
        // array elements are zero-filled by default
        return payload;
    }

    function callIsAuthorized(
        address account,
        uint256 simMapSize
    ) external returns (bool success, bytes memory result) {
        bytes memory payload = generateIsAuthorizedPayload(account, 32, simMapSize);
        (success, result) = HAS.call(payload);
    }

    function generateIsAuthorizedPayload(
        address account,
        uint256 firstBytesSize,
        uint256 simMapSize
    ) public pure returns (bytes memory) {
        bytes4 selector = bytes4(keccak256("isAuthorized(address,bytes,bytes)"));
        // ABI encoding layout:
        // 4   bytes - selector
        // 32  bytes - account
        // 32  bytes - offset to first bytes (always 96 = 0x60, three 32-byte slots after selector)
        // 32  bytes - offset to second bytes (96 + 32 + paddedFirstBytesSize)
        // 32  bytes - first bytes length
        // paddedFirstBytesSize bytes - first bytes data
        // 32  bytes - second bytes length
        // paddedSecondBytesSize bytes - second bytes data
        uint256 paddedFirstSize = (firstBytesSize + 31) / 32 * 32;
        uint256 paddedSecondSize = (simMapSize + 31) / 32 * 32;
        bytes memory payload = new bytes(4 + 32 + 32 + 32 + 32 + paddedFirstSize + 32 + paddedSecondSize);
        // selector
        payload[0] = selector[0];
        payload[1] = selector[1];
        payload[2] = selector[2];
        payload[3] = selector[3];
        // account
        bytes32 accountEncoded = bytes32(uint256(uint160(account)));
        for (uint i = 0; i < 32; i++) {
            payload[4 + i] = accountEncoded[i];
        }
        // offset to first bytes data (96 = 0x60: account + offset1 + offset2)
        bytes32 offset1 = bytes32(uint256(96));
        for (uint i = 0; i < 32; i++) {
            payload[36 + i] = offset1[i];
        }
        // offset to second bytes data (96 + 32 + paddedFirstSize)
        bytes32 offset2 = bytes32(uint256(96 + 32 + paddedFirstSize));
        for (uint i = 0; i < 32; i++) {
            payload[68 + i] = offset2[i];
        }
        // first bytes length
        bytes32 firstLength = bytes32(firstBytesSize);
        for (uint i = 0; i < 32; i++) {
            payload[100 + i] = firstLength[i];
        }
        // first bytes data is zero-filled by default
        // second bytes length
        bytes32 secondLength = bytes32(simMapSize);
        for (uint i = 0; i < 32; i++) {
            payload[132 + paddedFirstSize + i] = secondLength[i];
        }
        // second bytes data is zero-filled by default
        return payload;
    }

    function callSignSchedule(
        address account,
        uint256 simMapSize
    ) external returns (bool success, bytes memory result) {
        bytes memory payload = generateSignSchedulePayload(account, simMapSize);
        (success, result) = HSS.call(payload);
    }

    function generateSignSchedulePayload(
        address account,
        uint256 simMapSize
    ) public pure returns (bytes memory) {
        bytes4 selector = bytes4(keccak256("signSchedule(address,bytes)"));
        // ABI encoding layout:
        // 4  bytes - selector
        // 32 bytes - account
        // 32 bytes - offset to bytes data (always 64 = 0x40)
        // 32 bytes - bytes length
        // bodySize bytes - bytes data (zero-filled, padded to 32-byte boundary)
        uint256 paddedBodySize = (simMapSize + 31) / 32 * 32; // round up to 32-byte boundary
        bytes memory payload = new bytes(4 + 32 + 32 + 32 + paddedBodySize);
        // selector
        payload[0] = selector[0];
        payload[1] = selector[1];
        payload[2] = selector[2];
        payload[3] = selector[3];
        // account
        bytes32 accountEncoded = bytes32(uint256(uint160(account)));
        for (uint i = 0; i < 32; i++) {
            payload[4 + i] = accountEncoded[i];
        }
        // offset to bytes data (64 = 0x40)
        bytes32 offset = bytes32(uint256(64));
        for (uint i = 0; i < 32; i++) {
            payload[36 + i] = offset[i];
        }
        // bytes length (actual size, not padded)
        bytes32 length = bytes32(simMapSize);
        for (uint i = 0; i < 32; i++) {
            payload[68 + i] = length[i];
        }
        // bytes data is zero-filled by default
        return payload;
    }
}