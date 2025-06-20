// SPDX-License-Identifier: GPL-3.0
pragma solidity >=0.8.9 <0.9.0;
contract TouchAccountContract {
    // Function to transfer Ether to a specified address
    function touchAccount(address payable Address) public payable {
        require(msg.value > 0, "Amount must be greater than 0");
        Address.transfer(msg.value);
    }

    // Fallback function to allow the contract to receive Ether
    receive() external payable {}
}
