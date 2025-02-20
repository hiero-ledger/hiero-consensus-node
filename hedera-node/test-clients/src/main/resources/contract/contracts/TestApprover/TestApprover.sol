// SPDX-License-Identifier: Apache-2.0
pragma solidity 0.8.16;
import '../hip-206/HederaResponseCodes.sol';
import '../hip-206/HederaTokenService.sol';
contract TestApprover is HederaTokenService {
    address public immutable TOKEN;
    address public immutable SPENDER;
    constructor(address token, address spender) {
        int responseCode1 = HederaTokenService.associateToken(address(this), token);
        require(responseCode1 == HederaResponseCodes.SUCCESS, 'Association failed');
        
        uint256 approvalAmount = 10; // Something small
        int responseCode2 = HederaTokenService.approve(token, spender, approvalAmount);
        require(responseCode2 == HederaResponseCodes.SUCCESS, 'Approval failed');
        TOKEN = token;
        SPENDER = spender;
    }
    function approve() public {
        uint256 approvalAmount = 10; // Something small
        int responseCode = HederaTokenService.approve(TOKEN, SPENDER, approvalAmount);
        require(responseCode == HederaResponseCodes.SUCCESS, 'Approval failed');
    }
}
