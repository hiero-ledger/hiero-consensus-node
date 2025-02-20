// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.6.0 <0.9.0;

import "./HederaTokenService.sol";
import "./HederaResponseCodes.sol";

contract NestedHTSTransferrer is HederaTokenService {

    function transfer(
        address _tokenAddress,
        address _sender,
        address _receiver,
        int64 _amount) external {
        int response = HederaTokenService.transferToken(_tokenAddress, _sender, _receiver, _amount);
        if (response != HederaResponseCodes.SUCCESS) {
            revert ("Transfer of tokens failed");
        }
    }
}
