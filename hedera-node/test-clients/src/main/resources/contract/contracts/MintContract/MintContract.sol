// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";

contract MintContract is HederaTokenService {

    event MintedTokenInfo(uint64 indexed totalSupply, int256 indexed firstSerialNumber) anonymous;
    address tokenAddress;

    constructor(address _tokenAddress) public {
        tokenAddress = _tokenAddress;
    }

    function mintFungibleToken(uint64 amount) external {
        HederaTokenService.mintToken(tokenAddress, amount, new bytes[](0));
    }

    function mintNonFungibleToken(bytes[] memory metadata) external {
        HederaTokenService.mintToken(tokenAddress, 0, metadata);
    }

    function mintFungibleTokenWithEvent(uint64 amount) public {
        (int responseCode, uint64 newTotalSupply, int64[] memory serialNumbers) = HederaTokenService.mintToken(tokenAddress, amount, new bytes[](0));
        emit MintedTokenInfo(newTotalSupply, 0);

        if (responseCode != HederaResponseCodes.SUCCESS || serialNumbers.length > 0) {
            revert ("Fungible mint failed!");
        }
    }

    function mintNonFungibleTokenWithEvent(bytes[] memory metadata) external {
         (int responseCode, uint64 newTotalSupply, int64[] memory serialNumbers) = HederaTokenService.mintToken(tokenAddress, 0, metadata);
         emit MintedTokenInfo(newTotalSupply,serialNumbers[0]);

         if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Non fungible mint failed!");
        }
    }

    function mintNonFungibleTokenWithAddress(address _tokenAddress, bytes[] memory metadata) external {
        (int responseCode, uint64 newTotalSupply, int64[] memory serialNumbers) = HederaTokenService.mintToken(_tokenAddress, 0, metadata);
        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Non fungible mint failed!");
        }
    }

    function revertMintAfterFailedMint(address sender, address recipient, int64 amount) external {
        HederaTokenService.transferToken(tokenAddress, sender, recipient, amount);
        (int responseCode, uint64 newTotalSupply, int64[] memory serialNumbers) = HederaTokenService.mintToken(tokenAddress, 0, new bytes[](0));
        if (responseCode != HederaResponseCodes.SUCCESS) {
                revert ("Mint of fungible token failed!");
            }
        }
}
