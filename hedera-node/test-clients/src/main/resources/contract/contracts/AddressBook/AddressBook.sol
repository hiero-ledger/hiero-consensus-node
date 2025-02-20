// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.24;

import "./SafeMath.sol";

library AddressBook {

    using SafeMath for uint256;

    struct Data {
        mapping (address => bool) isAddressInBook;
        mapping (address => uint) addressIndex;
        address[] addresses;
    }

    // add an entry into addressbook
    function addAddress (Data storage self, address _address) internal {
        require (!self.isAddressInBook [_address], "Address already exists");
        self.isAddressInBook[_address] = true;
        self.addressIndex[_address] = self.addresses.length;
        self.addresses.push(_address);
    }

    // remove entry from the addressbook
    function removeAddress (Data storage self, address _address) internal {
        require (self.isAddressInBook [_address], "Address does not exist");
        uint index = self.addressIndex[_address];
        if(index == self.addresses.length.sub(1)){
            self.addresses.length = self.addresses.length.sub(1);
            self.isAddressInBook[_address] = false;
            self.addressIndex[_address] = 0;
        } else {
            self.isAddressInBook[_address] = false;
            delete self.addresses[index];
            self.addresses[index] = self.addresses[self.addresses.length.sub(1)];
            self.addressIndex[self.addresses[self.addresses.length.sub(1)]] = index;
            self.addresses.length = self.addresses.length.sub(1);
            self.addressIndex[_address] = 0;
        }
    }

    // returns all addresses registered
    function getAddressList(Data storage self) public view returns (address[]) {
        return self.addresses;
    }

    //
    function getAddressIndex(Data storage self, address userAddr) public view returns (uint) {
        return self.addressIndex[userAddr];
    }

    // check if an address is already registered
    function checkAddressInBook(Data storage self, address userAddr) public view returns (bool) {
        return self.isAddressInBook[userAddr];
    }

    // returns registered address count
    function getAddressBookSize(Data storage self) public view returns (uint) {
        return self.addresses.length;
    }
}
