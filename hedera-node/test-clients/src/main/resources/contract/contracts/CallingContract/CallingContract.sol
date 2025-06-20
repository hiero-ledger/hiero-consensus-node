// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.0;

contract CallingContract {

    uint var1;

    function setVar1(uint _var1) public {
        var1 = _var1;
    }

    function callContract(address _addr, uint _var1) public {
        _addr.call(bytes4(keccak256("storeValue(uint256)")));
        var1 = _var1;
    }

    function getVar1() public view returns (uint){
        return var1;
    }
}
