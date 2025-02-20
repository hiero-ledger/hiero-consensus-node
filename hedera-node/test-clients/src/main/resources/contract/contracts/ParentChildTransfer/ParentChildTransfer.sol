// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract Child {

    function getAmount() public view returns (uint){
        return address(this).balance;
    }

    function() external payable {}
}

contract Parent {

    Child myChild;

    constructor() public payable {
        myChild =  new Child();
    }

    function transferToChild(uint256 _amount) public {
        require(address(this).balance >= _amount);
        address(myChild).transfer(_amount);
    }

    function() external payable {}
}
