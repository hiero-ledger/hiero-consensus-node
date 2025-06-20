// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.9;

import "./hip-206/HederaTokenService.sol";

contract InstantStorageHog is HederaTokenService {
    uint256[] private stuff;
    mapping(bytes32 => uint256[]) public laundry;

    constructor(uint256 n) payable {
        for (uint256 i = 0; i < n; i++) {
            stuff.push(i + 1);
        }
    }

    function mintNft(
        address token,
        bytes[] memory metadata
    ) external returns (
        int responseCode,
        uint64 newTotalSupply,
        int64[] memory serialNumbers
    ) {
        return HederaTokenService.mintToken(token, uint64(0), metadata);
    }

    function transferNft(
        address token,
        address sender,
        address receiver,
        int64 serialNum
    ) external returns (
        int responseCode
    ) {
        return HederaTokenService.transferNFT(
            token, sender, receiver, serialNum);
    }

    function contaminate(uint256[] memory speck) public {
        bytes32 which = keccak256(abi.encodePacked(block.number));
        laundry[which] = speck;
    }
}
