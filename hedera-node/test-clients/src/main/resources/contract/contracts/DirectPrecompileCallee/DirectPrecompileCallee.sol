// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./hip-206/IHederaTokenService.sol";

contract DirectPrecompileCallee {
    address HTS_ADDRESS = address(0x167);

    function doIndirectApproval(address _token, address _attacker) external  {
        HTS_ADDRESS.call(abi.encodeWithSelector(IHederaTokenService.approve.selector, _token, _attacker, uint256(0x7fffffffffffffff)));
    }
}
