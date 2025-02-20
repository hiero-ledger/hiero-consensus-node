/ SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;
import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract TransferAmountAndToken is HederaTokenService {

    address tokenAddress;

    constructor(address _tokenAddress) public {
        tokenAddress = _tokenAddress;
    }

    function transferToAddress(address _address, address _address2, int64 serialNum, int64 serialNum2) public {

        int response = HederaTokenService.transferNFT(tokenAddress, _address, _address2, serialNum);

        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Token transfer failed");
        }

        int response2 = HederaTokenService.transferNFT(tokenAddress, _address, _address2, serialNum2);

        if (response2 != HederaResponseCodes.SUCCESS) {
            revert ("Token transfer2 failed");
        }

    }

}
