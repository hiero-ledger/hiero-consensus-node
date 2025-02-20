// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.24;
pragma experimental ABIEncoderV2;

import "./Ownable.sol";
import "./SafeMath.sol";
import "./AddressBook.sol";
import "./Jurisdictions.sol";



/// @title Minters
contract Minters is Ownable {

    struct Minter {
        address minter;
        string name;
        bytes32 jurisdiction;
    }

    /// @notice Make sure incoming calls only come from BCDA contract
    modifier onlyAdmin(){
        require(msg.sender == admin, "Caller not Admin");
        _;
    }

    /// @notice Object of Addressbook library
    AddressBook.Data private minterAddressbook;
    /// @notice Object of jurisdiction contract
    Jurisdictions jurisdictions;
    /// @notice Address of BCDA contract
    address admin;

    /// @notice minter address => minter info
    mapping (address => Minter) public minters; // all minter info
    /// @notice minter address => list of tokens minted by minter
    mapping (address => uint[]) public minterTokens; // pucks minted by each Minter

    event MinterAdded(address minter, string name, bytes32 jurisdiction, uint timestamp);
    event MinterRemoved(address minter, string name, bytes32 jurisdiction, uint timestamp);



    /// @notice Initialize contract with jurisdiction contract
    /// @param _jurisdictions Address of jurisdiction
    constructor(address _jurisdictions, address _admin) public {
        jurisdictions = Jurisdictions(_jurisdictions);
        admin = _admin;
    }

    /// @notice Onboard a new minter
    /// @param minter Address of minter
    /// @param name Name of minter
    /// @param jurisdiction Jurisdiction code
    function add(address minter, string name, bytes32 jurisdiction) public onlyOwner() {
        require(jurisdictions.isValid(jurisdiction), "Invalid jurisdiction code");
        AddressBook.addAddress(minterAddressbook, minter);
        Minter memory newMinter = Minter({
        minter : minter,
        name : name,
        jurisdiction : jurisdiction
        });
        minters[minter] = newMinter;
        emit MinterAdded(minter, name, jurisdiction, now);
    }

    /// @notice Delete a minter
    /// @param minter Address of minter
    function remove(address minter) public onlyOwner(){
        string memory name = minters[minter].name;
        bytes32 jurisdiction = minters[minter].jurisdiction;
        AddressBook.removeAddress(minterAddressbook, minter);
        delete minters[minter];
        emit MinterRemoved(minter, name, jurisdiction, now);
    }

    /// @notice List of all registered minters
    function getAddressList() public view returns (address[]){
        return AddressBook.getAddressList(minterAddressbook);
    }

    /// @notice Get jurisdiction of a registered minter
    /// @param minter Address minter
    function getJurisdiction(address minter) public view returns (bytes32){
        return minters[minter].jurisdiction;
    }

    /// @notice List of tokens minted by a minter
    /// @param minter Address of minter
    function getTokens(address minter) public view returns(uint[]){
        return minterTokens[minter];
    }

    /// @notice Check if a particular minter is registered
    /// @param minter Address of minter
    function isValid(address minter) public view returns (bool){
        return AddressBook.checkAddressInBook(minterAddressbook, minter);
    }

    function seven() public pure returns (uint) {
        return 7;
    }

    //=============== CONFIGURATION Functions ===============

    /// @notice Configure address of jurisdiction contract
    /// @param _jurisdictions Address of jurisdiction contract
    function configureJurisdictionContract(address _jurisdictions) public {
        jurisdictions = Jurisdictions(_jurisdictions);
    }

}
