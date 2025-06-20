// SPDX-License-Identifier: MIT
pragma solidity ^0.8.9;

contract RevertingContract{
    constructor(uint256 _range){
        if(_range < 5)
            revert();
    }

    function boo(uint256 _value) public {}

    function createContract(uint256 _range) public {
        new TestContract(_range);
    }

    function callingWrongAddress()public {
        address(0x0).call(abi.encodeWithSignature("boo(uint256)",234));
    }
}
contract TestContract
{
    constructor(uint256 _range){
        if(_range < 5)
            revert();
    }
}
