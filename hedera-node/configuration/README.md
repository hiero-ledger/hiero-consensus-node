# Services configuration

The child folders in this directory hold configuration
for various environments, for use with the **upcoming release of Services**. 
Each configuration file is described in a small sub-section below. 

All deployment paths are given relative to the top-level directory 
containing the deployed JAR (in DevOps-managed environments, this 
is _/opt/hgcapp/services-hedera/HapiApp2.0_.) If no deployment 
path is given, the file belongs in the top-level directory with the JAR.

## bootstrap.properties
* Deployed as _data/config/bootstrap.properties_
* Normally used with networks **not** starting from a saved state
* Contains overrides used at network startup
  - For example, more permissive bootstrap throttles

## application.properties
* Deployed as _data/config/application.properties_
* Genesis contents of file `0.0.121` 
  - :information_desk_person: &nbsp; Only relevant for networks **not** starting from a saved state 

## optional-upgrade/application.properties
* May be used, post-upgrade, as input to a yahcli `sysfiles upload properties` command at the discretion of DevOps

## upgrade/application.properties
* If present, should be used, post-upgrade, as input to a yahcli `sysfiles upload properties` command

## api-permission.properties
* Deployed as _data/config/api-permission.properties_
* Genesis contents of file `0.0.122` 
  - :information_desk_person: &nbsp; Only relevant for networks **not** starting from a saved state 

## optional-upgrade/api-permission.properties
* May be used, post-upgrade, as input to a yahcli `sysfiles upload permissions` command at the discretion of DevOps

## upgrade/api-permission.properties
* If present, should be used, post-upgrade, as input to a yahcli `sysfiles upload permissions` command

## node.properties
* Deployed as _data/config/node.properties_
* Contains overrides of node-level configuration
  - For example, more permissive Netty HTTP/2 settings

## upgrade/throttles.json
* If specified, this file serves as the sole input for the `yahcli sysfiles upload throttles` command post-upgrade, replacing any existing throttles with its contents. In its absence, the default is sourced from `:/hedera-node/hedera-file-service-impl/src/main/resources/genesis/throttles.json`.

## javaOptions
* Script to start `HGCApp` with all needed options

## log4j2.xml
* Services logging configuration

## settings.txt
* Platform configuration
* Performance-sensitive settings include:
  - `throttleTransactionQueueSize`
  - `throttle7extra`
  - `cpuVerifierThreadRatio`
  - `cpuDigestThreadRatio`
  - `maxOutgoingSyncs`
