# yahcli

Yahcli (_Yet Another Hedera Command Line Interface_) supports DevOps
actions against the Hedera networks listed in a _config.yml_ file.

Actions include updating system files, running validation tests,
re-keying accounts, and freezing networks for maintenance.

:warning:&nbsp;Besides the _config.yml_, yahcli requires keys and
other assets to be present in a specific directory layout. The details
appear below.

**Table of contents**
1. [Setting up the working directory](#setting-up-the-working-directory)
2. [Understanding general usage](#general-usage)
3. [Checking account balances](#getting-account-balances)
4. [Getting account information](#getting-account-information)
5. [Sending funds to an account](#sending-account-funds)
6. [Creating a new account](#creating-a-new-account)
7. [Updating system files](#updating-system-files)
8. [Preparing for an NMT upgrade](#preparing-an-nmt-software-upgrade)
9. [Launching an NMT telemetry upgrade](#launching-an-nmt-telemetry-upgrade)
10. [Scheduling a network freeze](#scheduling-a-network-freeze)
11. [Re-keying an account](#updating-account-keys)
12. [Getting deployed version info of a network](#get-version-info)
13. [Creating a new key](#generate-a-new-ed25519-key)
14. [Printing key details](#printing-key-details)
15. [Changing a staking election](#changing-a-staking-election)
16. [Scheduling a transaction](#scheduling-a-transaction)
17. [Scheduling public key list update](#scheduling-public-key-list-update)
18. [Activating staking after a network reset](#activating-staking)
19. [(DAB) Creating a node](#dab-creating-a-node)
20. [(DAB) Deleting a node](#dab-deleting-a-node)
21. [(DAB) Updating a node](#dab-updating-a-node)
21. [Running `ivy` acceptance tests](#running-ivy-acceptance-tests)

# Setting up the working directory

Yahcli needs the key for a "default" payer to use for each network.
To specify a key for account `0.0.2` on previewnet, you would create
a directory structure as below:

```
├── config.yml
├── previewnet
│   └── keys
│       ├── account2.pass
│       └── account2.pem
```

Where the _config.yml_ would have contents like:

```
defaultNetwork: previewnet

networks:
  previewnet:
    allowedReceiverAccountIds:
    nodes:
      - { id: 0, account: 3, ipv4Addr: 35.231.208.148 }
```

We can add details for multiple networks to this config file; for example,
we can add information about the stable testnet. And we can override the
default payer account or default node account for any network using the
`defaultPayer` and `defaultNodeAccount` fields, respectively.

We can add also allowedReceiverAccountIds of beneficiaries that the funds can be sent to and the format is list
example:`[123,456,789]`
or it can be `null` by default.

For Example:

```agsl
networks:
  previewnet:
    allowedReceiverAccountIds:[123,456,789]
    nodes:
      - { id: 0, account: 3, ipv4Addr: 35.231.208.148 }
```

We can also use the command line option `-p` to override `defaultPayer`, and
the command line option `-a/--node-account` to override `defaultNodeAccount`.

It is also possible to use the `-i/--node-ip` option to choose a target
node by its IP adress. However, if the IP address given to the `-i` option does
not appear in the _config.yml_, then we **must** explicitly give its node
account via the `-a` option. So with the above _config.yml_, it is enough to do,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 2 -n previewnet \
> -i 35.231.208.148
```

...since the ip `35.231.208.148` is in the _config.yml_. But to use an IP not
in the _config.yml_, we must also specify the node account,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 2 -n previewnet \
> -i 35.199.15.177 -a 4
```

```
defaultNetwork: previewnet

networks:
  previewnet:
    allowedReceiverAccountIds:
    nodes:
      - { id: 0, account: 3, ipv4Addr: 35.231.208.148 }
  stabletestnet:
    defaultPayer: 50
    defaultNodeAccount: 4
    allowedReceiverAccountIds:
    nodes:
      - { id: 0, account: 3, ipv4Addr: 34.94.106.61 }
      - { id: 1, account: 4, ipv4Addr: 35.237.119.55 }
```

For each network we add, we need a _{network}/keys/_ folder
that contains a `account{num}.pem` for each account we will
use with that network. :guard: &nbsp; If there is no corresponding
`account{num}.pass` for a PEM file, please be ready to enter
the passphrase interactively in the console. For example,

```
$ docker run -it -v $(pwd):/launch yahcli:${TAG) -p 2 sysfiles download all
Targeting localhost, paying with 0.0.2
Please enter the passphrase for key file localhost/keys/account2.pem:
```

:turtle: &nbsp; The docker image needs to launch a JAR, which is fairly slow.
Please allow a few seconds for the above command to run.

:warning:&nbsp;Without the `-it` flags above, Docker will not attach
STDIN as a TTY, and you will either not be prompted for the passphrase,
or your passphrase will appear in clear text.

Note that yahcli does not support multi-sig accounts.

# General usage

To list all available commands,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) help
```

:information_desk_person: &nbsp; Since the only key we have for previewnet
is for account `0.0.2`, we will need to use `-p 2` for the payer argument
when running against this network.

To download the fee schedules from previewnet given the config above, we run,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 2 -n previewnet sysfiles download fees
Targeting previewnet, paying with 0.0.2
Downloading the fees...OK
$ ls previewnet/sysfiles/
feeSchedules.json
```

The fee schedules were downloaded in JSON form to _previewnet/sysfiles/feeSchedules.json_.
To see more options for the `download` subcommand (including a custom download directory),
we run,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) sysfiles download help
```

The remaining sections of this document focus on specific use cases.

# Getting account balances

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n previewnet -p 2 accounts balance 56 50
```

# Getting account information

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n previewnet -p 2 accounts info 56 50
```

# Sending account funds

You can send assets from the default payer's account to a beneficiary account.

## Sending hbar

You can send hbar in denominations of `tinybar`, `hbar`, or `kilobar`.

The default denomination is `hbar`. To change the memo for the `CryptoTransfer`, use the `--memo` option.

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n previewnet -p 2 accounts send --denomination hbar --to 58 --memo "Yes or no" 1_000_000
```

## Sending fungible HTS units

To send fungible HTS units instead of hbar, change the denomination to the `0.0.X` id of
the token you want to send. The following example sends USDC on testnet.

**Important:** Unlike with hbar, the integral part of the amount sent is whole tokens,
relative to the token's `decimals` setting. The default of `--decimals 6` (which is correct
for USDC) means that the `1.23` amount below corresponds to `1_230_000` units of the
USDC fungible token type.

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n stabletestnet -p 2 accounts send -d 0.0.2276691 --to 58 --memo "Hello" 1.23
```

# Creating a new account

You can also create an entirely new account with an optional initial balance (default `0`) and memo (default blank).

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n previewnet -p 2 accounts create -d hbar -a 1 --memo "Who danced between"

```

By default, the new account will have a single Ed25519 key, exported to the target network's _keys/_ directory in both
PEM/passphrase and mnemonic forms. (E.g., this command might create both _previewnet/keys/account1234.{pem,pass}_ and
_previewnet/keys/account1234.words_.) If the `-k SECP256K1` option is used, the new account will be created with a
Secp256k1 key. A PEM file and .pass file are still created, but this algorithm doesn't support mnemonic keys, so the
`.words` file is _not_ created.

Yahcli now supports creating a new account with an existing key (either Ed25519 or Secp256k1) in PEM format. Use the
`--keyFile` and `--passFile` options–both required–to specify the path to the PEM file and its corresponding passphrase file.
These options supersede the key type option (`--keyType`) when specified, meaning there is no need to specify the key type
when using an existing key.

For example:

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n previewnet -p 2 accounts create -d hbar -a 1 /
--memo "Created with existing key" /
--keyFile previewnet/keys/existing-account.pem /
--passFile previewnet/keys/existing-account.pass

```

Note that the existing key **is not moved or copied** to the target network's `keys/` directory. Since yahcli typically
depends on the keys in the `keys/` directory for subsequent operations, you will need to ensure that the imported key files
used to create the account (i.e. the PEM file and pass file) are placed in the appropriate `keys/` directory with the
standard naming, `accountXXX.pem` and `accountXXX.pass`.

# Updating system files

For this example, we will run against a `localhost` network since we will modify a system file.

Our goal in the example is to add a completely new address book entry for a node with `nodeId=3`.
The DER-encoded RSA public key of the node is in a file _node3.der_, and its TLS X509 cert is
in a file _node3.crt_. We place these files in the directory structure below.

```

localhost
├── keys
│   ├── account2.pass
│   ├── account2.pem
│   ├── account55.pass
│   └── account55.pem
└── sysfiles
├── certs
│   └── node3.crt
└── pubkeys
└── node3.der

```

We first download the existing address book,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 sysfiles download address-book
Targeting localhost, paying with 0.0.2
Downloading the address-book...OK

```

Next we edit the newly-downloaded _localhost/sysfiles/addressBook.json_ and
add a new entry with `nodeId=3`, as below.

:information_desk_person: &nbsp; By using the `'!'` character in the `certHash` and `rsaPubKey` fields,
we tell yahcli to compute their values from the _certs/node3.crt_ and _pubkeys/node3.der_
files, respectively.

```

...
}, {
"nodeId" : 3,
"certHash" : "!",
"rsaPubKey" : "!",
"nodeAccount" : "0.0.6",
"endpoints" : [ {
"ipAddressV4" : "127.0.0.1",
"port" : 50207
}, {
"ipAddressV4" : "127.0.0.1",
"port" : 50208
}]
...

```

And now we upload the new address book, this time using the address book admin `0.0.55` as the payer:

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 55 sysfiles upload address-book

```

Finally we re-download the book to see that the hex-encoded cert hash and RSA public key were uploaded as expected:

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 sysfiles download address-book
Targeting localhost, paying with 0.0.2
Downloading the address-book...OK
$ tail -17 localhost/sysfiles/addressBook.json
}, {
"nodeId" : 3,
"certHash" : "0ae05bde15d216781a40e7bce5303bf68926f9440eec3cb20fabe9df06b0091a205fdea86911facb4e51e46c3890c803",
"rsaPubKey" : "3636303839396438343537353933653537396565363861333263303630336535393936666162613530386439343438306333656236346463613538373835326461333830353730323933636535336564393932666465343835333234636162356433353565373438313334393534623139633231633366303863363161316535643965333530316334333234353937656334646538646435383866666266646434613566333634366262376335393830636264323164643634301216763393131336631636533313864636134616635373732323462646538396332633137336633666538643039326534623866383030373130376138643965323633333166353335356135383464383037373661306162636139326530303438646433373163666530353936656464366261303737303338313432383866313039613832383035383630363562376262663238353432303434376134343336383830633361393336613666666663646162313012153335633864666561306461306537353035383530346661396163333036396438653166643762623333343530663761346261303439310a",
"nodeAccount" : "0.0.6",
"endpoints" : [ {
"ipAddressV4" : "127.0.0.1",
"port" : 50207
}, {
"ipAddressV4" : "127.0.0.1",
"port" : 50208
}]
} ]
}

```

## Uploading special files

System files in the range `0.0.150-159` are _special files_ that do not have the normal 1MB size limit.
These are used to stage ZIP artifacts for an NMT software or telemetry upgrade. By default, file `0.0.150`
is used for a software update ZIP, and file `0.0.159` for a telemetry upgrade ZIP.

:warning:&nbsp;Only three accounts have permission to update the special files: `0.0.2`, `0.0.50`, and `0.0.58`.

To upload such artifacts, use the special files names as below,

```

$ tree localhost/sysfiles/
localhost/sysfiles/
├── softwareUpgrade.zip
└── telemetryUpgrade.zip

```

Then proceed as with any other `sysfiles upload` command,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 sysfiles upload software-zip
...
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 sysfiles upload telemetry-zip
...

```

:repeat:&nbsp;Since `yahcli:${TAG) you can add the `--restart-from-failure` option like,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 sysfiles upload software-zip --restart-from-failure

```

If the hash of the file on the network matches the hash of a prefix of the bytes you're uploading, then yahcli will
automatically restart the upload after that prefix. For example,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 sysfiles upload software-zip
Log level is WARN
Targeting localhost, paying with 0.0.2
.i. Continuing upload for 0.0.150 with 34 appends already finished (out of 97 appends required)
...

```

### Checking a special file hash

You can also directly check the SHA-384 hash of a special file with the `sysfiles hash-check` subcommand,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 sysfiles hash-check software-zip

```

# Preparing an NMT software upgrade

To prepare for an automatic software upgrade, there must exist a system file in the range `0.0.150-159`
(by default, `0.0.150`) that is a ZIP archive with artifacts listed in the [NMT requirements document](https://github.com/swirlds/swirlds-docker/blob/main/docs/docker-infrastructure-design.md#toc-phase-1-feat-hedera-node-protobuf-defs-current). The expected
SHA-384 hash of this ZIP must be given so the nodes can validate the integrity of the upgrade file before
staging its artifacts for NMT to use. This looks like,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 prepare-upgrade \

> --upgrade-zip-hash 5d3b0e619d8513dfbf606ef00a2e83ba97d736f5f5ba61561d895ea83a6d4c34fce05d6cd74c83ec171f710e37e12aab

```

# Launching an NMT telemetry upgrade

To perform an automatic telemetry upgrade, there must exist a system file in the range `0.0.150-159`
(by default, `0.0.159`) that is a ZIP archive with artifacts listed in the [NMT requirements document](https://github.com/swirlds/swirlds-docker/blob/main/docs/docker-infrastructure-design.md#toc-phase-1-feat-hedera-node-protobuf-defs-current). The expected
SHA-384 hash of this ZIP must be known so the nodes can validate the integrity of the upgrade file before
staging its artifacts for NMT to use.  This looks like,

```

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 upgrade-telemetry \

> --upgrade-zip-hash 8ec75ab44b6c8ccac4a6e7f7d77b5a66280cad8d8a86ed961975a3bea597613f83af9075f65786bf9101d50047ca768f \
> --start-time 2022-01-01.00:00:00

```

# Scheduling a network freeze

Freeze start times are (consensus) UTC times formatted as `yyyy-MM-dd.HH:mm:ss`. Freezes may be
both scheduled and aborted; and a scheduled freeze may also be flagged as the trigger for an NMT
software upgrade.

A vanilla freeze with no NMT upgrade only includes the start time,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 freeze \
> --start-time 2022-01-01.00:00:00
```

While a freeze that should trigger a staged NMT upgrade uses the `freeze-upgrade` variant,
which **must** repeat the hash of the intended update,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 freeze-upgrade \
> --upgrade-zip-hash 5d3b0e619d8513dfbf606ef00a2e83ba97d736f5f5ba61561d895ea83a6d4c34fce05d6cd74c83ec171f710e37e12aab
> --start-time 2021-09-09.20:11:13
```

To abort a scheduled freeze, simply use the `freeze-abort` command,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 58 freeze-abort
```

# Updating account keys

You can use yahcli to replace an account's key with either a newly generated key, or an existing key. Existing keys
can be either PEM files (for Ed25519 or Secp256k1 keys) or BIP-39 mnemonics (Ed25519 keys only). Note that, in the
case of key generation, you may opt to specify a Secp256k1 type with `-K`, which will now generate a new ECDSA key.
**The `-K SECP256K1` option is only required for generating a _new_ key; this option is _not_ required for _replacing_
an existing Secp256k1 key.** Support for Secp256k1 keys has not previously been available for the `rekey` command, but
should now 'just work' as the Ed25519 key replacements do.

Our first example uses a randomly generated new key,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 2 -n localhost \
> accounts rekey --gen-new-key 57
Targeting localhost, paying with 0.0.2
.i. Exported a newly generated key in PEM format to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

This leaves the existing key info under _localhost/keys_ with _.bkup_ extensions, and overwrites
_localhost/keys/account57.pem_ and _localhost/keys/account57.pass_ in-place.

```
$ tree localhost/keys
localhost/keys
├── account2.pass
├── account2.pem
├── account57.pass
├── account57.pass.bkup
├── account57.pem
└── account57.pem.bkup
```

For the next example, we specify an existing PEM file, and enter its passphrase when prompted:

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 57 -n localhost \
> accounts rekey -k new-account57.pem 57
Targeting localhost, paying with 0.0.2
Please enter the passphrase for key file new-account55.pem:
.i. Exported key from new-account55 to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

Now we give an example of re-keying an account administered by a Secp256k1 key from a pre-existing PEM
and .pass file, where the contents of the original key–located in `account12345.pem`–are replaced with the
contents of a new Secp256k1 key–`new-ecdsa-key.pem`–and a new passphrase:

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 57 -n localhost \
> accounts rekey -k new-ecdsa-key.pem 57
Targeting localhost, paying with 0.0.57
.i. Exported key from new-ecdsa-key to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

As before, the previous PEM and .pass files are given a _.bkup_ extension, while _localhost/keys/account57.pem_
and _localhost/keys/account57.pass_ are overwritten with the contents of the new Secp256k1 key.

In our final example, we replace the `0.0.57` key from a mnemonic,

```
$ cat new-account57.words
goddess maze eternal small normal october ... author
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 57 -n localhost \
> accounts rekey -k new-account57.words 57
Targeting localhost, paying with 0.0.2
.i. Exported key from new-account55 to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

# Get version info

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n previewnet -p 2 version
```

# Generate a new Ed25519 key

You can use yahcli to generate a new Ed25519 key in PEM and mnemonic forms; note that
ECDSA(secp256k1) keys are not yet supported. The most common pattern will likely be,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) keys gen-new -p novel.pem
.i. Generating a new key @ novel.pem
.i.  - The public key is: 4351607d4a00821e6cbd8e8c186bfa3a2b8fdb5ca81cf1e5f84e95a86875fd84
.i.  - Passphrase @ novel.pass
.i.  - Mnemonic form @ novel.words
.i.  - Hexed public key @ novel.pubkey
.i.  - DER-encoded private key @ novel.privkey
$ cat novel.pass
PkpcBBYCjd7K
$ cat novel.words
wait busy pull hobby antique connect case mammal museum hockey fox refuse settle twist snow topple culture clean alpha hair people donkey surface thought
```

Note this command does *not* require setting a target network or payer account. If desired,
you can you can choose the PEM passphrase with the `-x` option instead of getting a randomly
generated passphrase in a _.pass_ file.

# Printing key details

If you have a PEM or mnemonic file for an Ed25519 or Secp256k1 key pair and need to extract the public key, you can run,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) keys print-public -p novel.pem -x PkpcBBYCjd7K
.i. The public key @ novel.pem is: 4351607d4a00821e6cbd8e8c186bfa3a2b8fdb5ca81cf1e5f84e95a86875fd84
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) keys print-public -p novel.words
.i. The public key @ novel.words is: 4351607d4a00821e6cbd8e8c186bfa3a2b8fdb5ca81cf1e5f84e95a86875fd84
```

If you need both the public and private keys, use instead the `print-keys` subcommand,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) keys print-keys -p novel.pem -x PkpcBBYCjd7K
.i. The public key @ novel.pem is : 4351607d4a00821e6cbd8e8c186bfa3a2b8fdb5ca81cf1e5f84e95a86875fd84
.i. The private key @ novel.pem is: ea52bce1ad54a88e156f50840e856b941f9b0db09266660c953cd14205546ca2
.i.   -> With DER prefix; 302e020100300506032b657004220420ea52bce1ad54a88e156f50840e856b941f9b0db09266660c953cd14205546ca2
```

For this command, Secp256k1 keys are now supported. There are no special considerations for printing the private or
public components of either an Ed25519 or Secp256k1 key, i.e. these print commands should work equivalently for both types.

# Changing a staking election

You can elect to stake to either a node or another account. With no other arguments, the `accounts stake` subcommand
updates the payer account's election. For example,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 accounts stake --to-node-id 0
...
.i. SUCCESS - account 0.0.2 is now staked to NODE 0

$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 accounts stake --to-account-num 1001
...
.i. SUCCESS - account 0.0.2 is now staked to ACCOUNT 0.0.1001
```

## Electing for a non-payer account

You can also change the staking election of an account other than the payer **if** yahcli can access the account's key in
PEM or mnemonic form. For example,

```
$ ls localhost/keys/account1001.*
localhost/keys/account1001.pass		localhost/keys/account1001.pem		localhost/keys/account1001.words
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 accounts stake --to-node-id 0 1001
...
.i. SUCCESS - account 0.0.1001 is now staked to NODE 0
```

## Declining rewards

With any of the above commands, you can add the `--start-declining-rewards` or `--stop-declining-rewards` option to
set the corresponding field in the underlying HAPI `CryptoUpdate`. For example,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n stabletestnet -p 45949104 accounts stake --start-declining-rewards --to-node-id 2
Log level is WARN
Targeting stabletestnet, paying with 0.0.45949104

---- Raw update ----

accountIDToUpdate {
  accountNum: 45949104
}
staked_node_id: 2
decline_reward {
  value: true
}

--------------------

.i. SUCCESS - account 0.0.45949104 updated, now staked to NODE 2 with declineReward=true
```

# Scheduling a transaction

You can schedule a transaction to be signed by the recipient of the transaction. For example,

```
Use accounts create -S to create a new receiver account with signature required 0.0.R
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 accounts create -m test -r 2 -a 5 -S

Use accounts send --schedule to schedule a transfer to 0.0.R, and create a transaction 0.0.T that has been scheduled
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 --schedule accounts send --denomination hbar --to 0.0.R --memo "test" 8

Use  schedule sign scheduleId T paying with account 0.0.R to trigger the transfer
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 0.0.R schedule sign --scheduleId T

```

# Scheduling public key list update

You can schedule a transaction to update key list for targeted account by multiple signer. For example scenario,

```
Use accounts create to create a new account with signature required 0.0.R
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 accounts create -m test -r 2 -a 5 -S

Use accounts create -S to create a new account with signature required 0.0.T
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 accounts create -m test -r 2 -a 5 -S

Use accounts create -S to create a new account with signature required 0.0.S (This account we will change the key list)
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 accounts create -m test -r 2 -a 5 -S

Use key get public keys to get public keys of 0.0.R and 0.0.T
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 keys print-public -p ~/accountR.pem -x {passphrase}
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 keys print-public -p ~/accountT.pem -x {passphrase}

copyu them to text file each public key in seperate line (example: account.txt)

Use accounts update --schedule to schedule a key replacement for 0.0.S using the file path for the keys and targeted account. (This is trx Z)
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 2 --schedule accounts update --pathKeys ~/account.txt --targetAccount S --memo "test update"

Use  schedule sign scheduleId T paying with account 0.0.R, 0.0.T and 0.0.S to sign the replacemnt of the keys
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 0.0.R schedule sign --scheduleId Z
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 0.0.T schedule sign --scheduleId Z
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml -a 3 -n localhost -p 0.0.S schedule sign --scheduleId Z

Use info account S to check the key list of the account
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) --config /test-clients/yahcli/config.yml  -a 3 -n localhost -p 2 accounts info S
```

# Activating staking

After resetting a network like previewnet or testnet, we generally want to re-activate staking. We
can do this using the `activate-staking` command. For example,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -p 2 -n integration activate-staking
.i. SUCCESS - staking activated on network 'integration' with,
.i.   * Reward rate of               273972602739726
.i.   * 0.0.800 balance credit of    25000000000000000
.i.   * 0.0.8161 staked to node6 for 178571428571428571
.i.   * 0.0.8162 staked to node4 for 178571428571428571
.i.   * 0.0.8156 staked to node5 for 178571428571428571
.i.   * 0.0.8157 staked to node3 for 178571428571428571
.i.   * 0.0.8158 staked to node2 for 178571428571428571
.i.   * 0.0.8159 staked to node0 for 178571428571428571
.i.   * 0.0.8160 staked to node1 for 178571428571428571
```

The above example shows the default values used by the command; in particular,
1. The reward rate per 24-hour period is `273972602739726` tinybar.
2. The amount deposited into `0.0.800` is 250M hbar.
3. Each node gets one newly created staking account whose balance is min stake for the network size
as it appears in _config.yml_.

You can configure these values as below,

```
$ docker run -it -v $(pwd):/launch yahcli:$TAG -n localhost -p 2 activate-staking -p 1bh -r 2mh -b 3kh
Log level is WARN
Targeting localhost, paying with 0.0.2
.i. SUCCESS - staking activated on network 'localhost' with,
.i.   * Reward rate of               200000000000000
.i.   * 0.0.800 balance credit of    300000000000
.i.   * 0.0.1005 staked to node0 for 100000000000000000
```

Note the `kh`/`mh`/`bh` suffixes to express thousands, millions, and billions of hbar, respectively.

# (DAB) Creating a node

To create a new node, you can use the `nodes create` command.

If the gossip CA certificate is to be read from a PKCS#12 (.pfx) file, there are seven required options, and an
optional eighth `--description` argument. The seven required options are,
1. The number of the new node's fee collection account; if this account does not have a key in the _keys/_ directory,
yahcli will warn that at least one of the payer and admin key signatures must provide this account's signature.
2. A comma-separated list of the node's gossip endpoints, given in the form `{<IPV4>|<FQDN>}:<PORT>`.
3. A comma-separated list of the node's HAPI endpoints, given in the form `{<IPV4>|<FQDN>}:<PORT>`.
4. A path to the .pfx file with the node's CA-signed X.509 gossip certificate.
5. The alias of the node's CA-signed X.509 gossip certificate in the .pfx file.
6. A path to the node's self-signed X.509 HAPI TLS certificate.
7. A path to a _.pem_, _.words_, or _.hex_ file containing an admin key for the node; if there is a _.pass_ file
corresponding to a _.pem_ file, its contents will automatically be used for the PEM passphrase.

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 nodes create \
  --accountNum 23 \
  --description 'Testing 123' \
  --gossipEndpoints 127.0.0.1:50070,10.1.2.3:50070 \
  --serviceEndpoints a.b.com:50213 \
  --gossipCaCertificatePfx private-node1.pfx \
  --gossipCaCertificateAlias s-node1 \
  --hapiCertificate node1.crt \
  --adminKey adminKey.pem
Log level is WARN
Targeting localhost, paying with 0.0.2
.!. No key on disk for account 0.0.23, payer and admin key signatures must meet its signing requirements
Please enter the passphrase for .pfx file private-node1.pfx:
2024-08-22 15:37:29.998 INFO   216  HapiNodeCreate -

********************************************************************************
********************************************************************************
**                                                                            **
** Created node 'Testing 123' with id '7'.                                    **
**                                                                            **
********************************************************************************
********************************************************************************

.i. SUCCESS - created node7
```

If the gossip CA certificate is to be read from a PEM file, there are six required options, and an
optional seventh `--description` argument. The six required options are,
1. The number of the new node's fee collection account; if this account does not have a key in the _keys/_ directory,
yahcli will warn that at least one of the payer and admin key signatures must provide this account's signature.
2. A comma-separated list of the node's gossip endpoints, given in the form `{<IPV4>|<FQDN>}:<PORT>`.
3. A comma-separated list of the node's HAPI endpoints, given in the form `{<IPV4>|<FQDN>}:<PORT>`.
4. A path to the PEM file with the node's CA-signed X.509 gossip certificate.
5. A path to the node's self-signed X.509 HAPI TLS certificate.
6. A path to a _.pem_, _.words_, or _.hex_ file containing an admin key for the node; if there is a _.pass_ file
corresponding to a _.pem_ file, its contents will automatically be used for the PEM passphrase.

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 nodes create \
  --accountNum 23 \
  --description 'Testing 123' \
  --gossipEndpoints 127.0.0.1:50070,10.1.2.3:50070 \
  --serviceEndpoints a.b.com:50213 \
  --gossipCaCertificate s-public-node1.pem \
  --hapiCertificate s-public-node1.pem \
  --adminKey adminKey.pem
```

Independent of the gossip CA certificate format, the `--declineRewards` option can be used to indicate that the
new node should decline reward payments. Use `--declineRewards` to decline rewards, or `--declineRewards false`
to accept rewards. This value defaults to `true` (declines the rewards) if not specified.

The `--grpcProxyEndpoint` option, given in the form `{<IPV4>|<FQDN>}:<PORT>`, can be used to specify a gRPC proxy endpoint for the node. This is optional.

:warning: If the payer and admin keys do not meet the signing requirements of the new node's fee collection account,
there must be a key in the target network's _keys/_ directory for that account.

# (DAB) Deleting a node

To delete a node, you can use the `nodes delete` command. The only required option is the node ID to delete; but in
general you will also want to provide the `--adminKey` option with the path to the admin key for the node being deleted.
(This can be omitted if the yahcli payer key is the same as the admin key.)

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 nodes delete \
  --nodeId 3 \
  --adminKey adminKey.pem
Log level is WARN
Targeting localhost, paying with 0.0.2
.i. SUCCESS - node3 has been deleted
```

# (DAB) Updating a node

To update a node, you can use the `nodes update` command. The only required option is the node ID to update; but in
general you will also want to provide the `--adminKey` option with the path to the admin key for the node being updated.
(This can be omitted if the yahcli payer key is the same as the admin key.)

:warning: You can only change the fee collection account if the target network's properties include,

```
nodes.updateAccountIdAllowed=true
```

To change every available field using a new gossip certificate from a PKCS#12 (.pfx) file, the command might look like,

```
docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 nodes update \
  --nodeId 1 \
  --adminKey adminKey.pem \
  --description 'Testing 456' \
  --gossipEndpoints 127.0.0.1:60070,10.1.2.3:60070 \
  --serviceEndpoints a.b.com:60213 \
  --gossipCaCertificatePfx private-node1.pfx \
  --gossipCaCertificateAlias s-node1 \
  --hapiCertificate node1.crt \
  --newAdminKey newAdminKey.pem
Log level is WARN
Targeting localhost, paying with 0.0.2
Please enter the passphrase for .pfx file private-node1.pfx:
.i. SUCCESS - node1 has been updated
```

And to change every available field using a new gossip certificate in PEM format, the command might look like,

```
docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG) -n localhost -p 2 nodes update \
  --nodeId 1 \
  --adminKey adminKey.pem \
  --accountNum 42 \
  --description 'Testing 456' \
  --gossipEndpoints 127.0.0.1:60070,10.1.2.3:60070 \
  --serviceEndpoints a.b.com:60213 \
  --gossipCaCertificate s-public-node1.pem \
  --hapiCertificate s-public-node1.pem \
  --newAdminKey newAdminKey.pem
Log level is WARN
Targeting localhost, paying with 0.0.2
.!. No key on disk for account 0.0.42, payer and admin key signatures must meet its signing requirements
.i. SUCCESS - node1 has been updated
```

A node can also be updated to begin declining or accepting reward payments. Use `--startDecliningRewards` to
begin accepting reward payments, or `--stopDecliningRewards` to stop accepting reward payments. These params have
no default values.

A node's gRPC proxy endpoint can also be updated with the `--grpcProxyEndpoint` option, given in the form `{<IPV4>|<FQDN>}:<PORT>`. This is optional.

# Running `ivy` acceptance tests

The `ivy` subcommand runs HAPI acceptance tests.

Its most elaborate option is `scenarios`, which runs a port of the original `ValidationScenarios` JAR long used as
part of Hedera releases. Executing these scenarios uses (or creates) a _config.yml_ under the target network's
_scenarios/_ directory. For example, running every validation scenario against a just-started local network,

```
docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG} -p 2 -n localhost ivy scenarios \
  --crypto --file --contract --consensus --xfers --staking
```

will create a _localhost/scenarios/config.yml_ like,

```
bootstrap: 2
defaultFeeInHbars: 100
defaultNode: 3
defaultNodePaymentInTinybars: 100
ensureScenarioPayerHbars: 25
scenarioPayer: 1001
scenarios:
  consensus: {persistent: 1007}
  contract:
    persistent: {bytecode: 1005, luckyNo: 42, num: 1006, source: ./localhost/scenarios/Multipurpose.bin}
  crypto: {receiver: 1003, sender: 1002}
  file:
    persistent: {contents: ./localhost/scenarios/MrBleaney.txt, num: 1004}
sleepMsBeforeNextNode: 1000
```

The `ValidationScenarios` "novel" flag corresponds to adding `--new-entities` to the command line. For example,
in addition to validating the existence and behavior of the contract `1006` created above, we can also validate we
can still create and delete a new contract with,

```
docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:${TAG} -p 2 -n localhost ivy scenarios \
  --contract --new-entities
```

**Important:** Note the one important _difference_ between the original `ValidationScenarios` _config.yml_ and the
`ivy scenarios` _scenarios/config.yml_ is that the target network is **not** specified in the latter. The yahcli
configuration now specifies the target network(s), and the yahcli command line controls the choice of target.
