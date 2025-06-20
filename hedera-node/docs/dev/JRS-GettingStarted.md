# Java Regression Suite(JRS) Developer Testing
# **Table of Contents**

- [Description](#description)
- [Overview](#overview)
    - [File Types](#file-types)
    - [Naming Conventions for the JSONs](#naming-conventions)
    - [Instructions for kicking off Regression from local machine](#instructions)
- [Services Nightly Regression](#nightly-regression)
- [GCP SetUp](#gcp_setup)

<a name="description"></a>

# **Description**
This document describes the steps needed to run a JRS test using the infrastructure in 
swirlds-platform-regression. To be able to run using JRS, it is necessary to [set up GCP](https://github.com/hashgraph/hedera-services/blob/docs/dev/GCP-setup.md) on your local machine.

<a name="overview"></a>

# **Overview**
The Java Regression Suite (JRS) runs on a remote machine. It relies on two types of json files, i.e., Regression configuration json and experiment configuration json described below. JRS infrastructure provisions the machines and run experiments based on the configurations set in the JSONs.

<a name="file-types"></a>
## **File Types**
1. _Regression configuration JSON :_ The file name usually starts with `GCP_` (Eg : `GCP-Personal-Neeha-4N.json`,`GCP-Daily-Services-Comp-Basic-4N-1C.json`). Based on the credentials after [GCP setup](#gcp_setup), it holds information of 
     - `cloud` cloud configuration that includes the private keys, regions in which nodes need to be instantiated etc.,
     - `experiments` experiments to run.
     - `slack` slack details needed to post the results. Use `hedera-regression-test` channel for testing. `hedera-regression`/`hedera-regression-summary` channel needs to be used exclusively for nightly regression results.
     - `result` results folder in the local machine to download the logs after test.  
     - other minor details.
    
2. _Experiment configuration JSON :_ It holds specific information on the set up of the experiment and validations to be done to tell if it passed.  It majorly includes 
   - `duration` duration of the test in seconds.
   - `settings` platform settings that needs to be overridden.
   - `runConfig` types of the run configuration based on the test type. Eg: `RECONNECT`, `RESTART`, `RECOVER` etc.,
   - `hederaServicesConfig`  configuration for the clients when running services tests. It includes test suites to be run on client, `CI_PROPERIES_MAP`  to be used, using fixed node for payments etc.,
   - `app` application jar to be run. Eg: `HederaNode.jar` for services tests.
   - `startSavedState` saved state location, if any saved state needs to be loaded at the beginning of test.
   - `validatorConfigs` to be run at end of the experiment to validate if it passed Eg: `ERROR`, `RESTART`, `HEDERA_NODE` etc.,

In order to avoid duplication of data, both JSONs support inheritance from `parentList`. Settings, configurations, validators etc., can be defined in a parent JSON and can be reused as defined in the example below.

```
"parentList": [
    "configs/services/default/standard-services-settings.json"
	"configs/services/default/standard-services-configs.json",
	"configs/services/default/standard-services-validators.json"
  ],
```

**NOTE:** For any developer to run tests, a personal Regression JSON should be created with their personal cloud configuration details.
It should be created under `swirlds-platform/regression/configs/services/suites/personal/GCP-Personal-XXX.json` (XXX is name of the developer)

**NOTE:** `name` field in any Regression JSON should be of limited length. While creating instances timestamp is added to this name and GCP has limitation on the length of the name constructed for creating instances.
Providing very long `name` will cause failure to create instances.


**Example of a Personal JSON**
```
{
  "parentList": [
	"configs/services/JRS-Default-Services.json",
	"configs/services/GCP-Default-Services-4N.json",
	"configs/services/suites/daily/JRS-Daily-Default-Services-Slack.json"
  ],
  "name": "GCP-Personal-GCPTEST",
  "result": {
	"uri": "results/Personal"
  },
  "cloud": {
	"login": "gcptest",
	"keyLocation": "./keys/my-key",
	"projectName": "hedera-regression",
	"regionList": [
	  {
		"region": "us-east1-b",
		"numberOfNodes": 4,
		"numberOfTestClientNodes": 1
	  }
	]
  },
  "experiments": [
	"configs/services/tests/Misc-Basic-14-20m.json" --> experiment json
  ],
  "slack": {
	"channel": "hedera-regression-test",
	"summaryChannel": "hedera-regression-test",
	"notifyOn": "ERROR",
	"notifyUserIds": [
	  "SLACK_USER_ID"
	]
  }
}
```
**Example of a basic experiment JSON**

```
{
  "name": "Misc-Basic-14-20m",
  "description": "SuiteRunner-Basic-Suites-JRS",
  "parentList": [
	"configs/services/default/standard-services-configs.json",
	"configs/services/default/standard-services-settings.json",
	"configs/services/default/standard-services-validators.json"
  ],
  "duration": 1200,
  "hederaServicesConfig": {
	"testSuites": [
	  "ControlAccountsExemptForUpdates",
	  "UmbrellaRedux",
	  "TopicCreateSpecs",
	  "SubmitMessageSpecs",
	  "TopicUpdateSpecs",
	  "HCSTopicFragmentationSuite",
	  "TopicGetInfoSpecs",
	  "CryptoCreateSuite",
	  "CryptoRecordSanityChecks",
	  "SignedTransactionBytesRecordsSuite",
	  "SuperusersAreNeverThrottled",
	  "FileRecordSanityChecks",
	  "VersionInfoSpec"
	],
	"fixedNode": true
  }
}
```

<a name="naming-conventions"></a>
## **Naming Conventions for the JSONs** 
Naming conventions described in [file](https://github.com/swirlds/swirlds-platform-regression/blob/main/docs/regression-test-naming-standards.md) are required to be followed for both types of configuration JSONs. 
Any new naming conventions need to be added to the file if required, after seeking approval from the code owners in `swirlds-platform-regression` repository.

<a name="instructions"></a>
## **Instructions for kicking off Regression from local machine**

- Open terminal.
- Clone `hedera-services` and `swirlds-platform` repositories.
- Checkout the branches needed in both the repositories.  
- Add the experiment to be run in user's personal JSON `configs/services/suites/personal/GCP-Personal-XXX.json` in experiments section, as the example below.
  ```
  "experiments": [
    "configs/services/tests/reconnect/SmartContractOps-NIReconnect-14-21m.json"
    ]
  ```
- Run the following command to start regression. 
  `cd swirlds-platform/regression; 
  ./regression_services.sh configs/services/suites/personal/GCP-Personal-XXX.json path_to_hedera-services_repository > cron-personal-test.err 2>&1`

    - `regression_services.sh` will compile both the repositories before provisioning instances.
- Add `& disown -h` at the end of the above command if it needs to run in background. If not use screen.

<a name="nightly-regression"></a>

# **Services Nightly Regression**

Current Services nightly regression runs the following tests based on the cron timings:
- _Performance :_ Test performance of the system for all services and mixed operations.
- _Restart :_ Nodes enter freeze and restarted in middle of the test.
- _State Recovery :_ Events are replayed on a node.
- _Reconnect :_ one or more nodes network connection is interrupted (NI-Reconnect) or java process is killed (ND-Reconnect) to make the node fall behind. Once node is back up online, it will reconnect with other nodes.
- _Migration :_ Start nodes from an older state.
- _Network Error :_ Simulate network errors by packet delays, packet loss etc.,
- _Network Delay :_ Add emulated delays between nodes to simulate a distributed network using a network delay matrix.
- _Basic :_ Basic validation tests.
- _AccountBalances Validate :_ Validate AccountBalances from proto and its signature files.
- _Update :_ Update node software using update feature.

All the above tests are under the following path `swirlds-platform/regression/configs/services/suites` under `daily` or `weekly`  with appropriate names.

**NOTE** : To validate the regression results follow steps defined in [regression-validation-checklist.md](https://github.com/swirlds/swirlds-platform-regression/blob/main/docs/regression-validation-checklist.md).

<a name="gcp_setup"></a>

# **GCP setup to run tests**

Steps to set up GCP are listed in this [document](https://github.com/hashgraph/hedera-services/blob/docs/dev/GCP-setup.md).