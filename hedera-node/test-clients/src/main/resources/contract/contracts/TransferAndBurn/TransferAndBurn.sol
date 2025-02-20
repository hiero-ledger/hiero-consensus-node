// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract TransferAndBurn is HederaTokenService {

    address tokenAddress;

    constructor(address _tokenAddress) public {
        tokenAddress = _tokenAddress;
    }

    function transferBurn(address _address, address _address2, uint64 amount, int64 serialNum, int64[] memory serialNumbers) public {
        (int burnResponse, uint64 newTotalSupply) = HederaTokenService.burnToken(tokenAddress, amount, serialNumbers);

        if (burnResponse != HederaResponseCodes.SUCCESS) {
            revert ("Token burn failed");
        }

        int response = HederaTokenService.transferNFT(tokenAddress, _address, _address2, serialNum);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token transfer failed");
        }

    }
}
