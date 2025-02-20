// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
pragma experimental ABIEncoderV2;

import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract ServiceContract is HederaTokenService {


    function nftTransfer(address token, address sender, address receiver, int64 serialNum) external {
        int response = HederaTokenService.transferNFT(token, sender, receiver, serialNum);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token transfer failed");
        }
    }


    function tokenBurn(address token, uint64 amount, int64[] memory serialNumbers) external {
        (int response, uint64 newTotalSupply) = HederaTokenService.burnToken(token, amount, serialNumbers);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }

    }

    function tokenMint(address token, uint64 amount) external {
        (int responseCode, uint64 newTotalSupply, int[] memory serialNumbers) = HederaTokenService.mintToken(token, amount, new bytes[](0));

        if (responseCode != HederaResponseCodes.SUCCESS) {
            revert ("Mint of fungible token failed!");
        }
    }
}
