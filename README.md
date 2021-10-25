[![GitHub license](https://img.shields.io/badge/license-GPLv3-blue.svg)](https://raw.githubusercontent.com/AidosKuneen/aidos-node/master/LICENSE)

## Introduction

ARI is the Aidos Reference Implementation. It's the implementation of a full Node with a JSON-REST HTTP interface (API).
It's based upon Java 8 and therefore a requirement.

## Install

1. You can either download a build version from the [Release Page](https://github.com/AidosKuneen/aidos-node/releases) or build by yourself by following the instructions hereafter.

2. Get [Maven](http://maven.apache.org/download.cgi)

3. Clone this repository:

  ```
  git clone https://github.com/AidosKuneen/aidos-node
  ```

## How To Build

Maven is needed in order to compile and build the ARI.

* To compile:

```
$ mvn clean compile
```

* To create an executable build.

```
$ mvn package
```

Creating an executable build generates an executable jar package called "ari-${release-version}.jar" in the root directory of the project.

## How To Execute

```
java -jar ari-${version}.jar [{-r,--receiver-port} 14265] [{-p,--peer-discovery}] [{-w,--remote-wallet}] [{-l,--local} ipv4/ipv6][{-c,--enabled-cors} *] [{-d,--debug}] [{-e,--experimental}]
```
				
The following argument is mandatory to start a Node:  
* `-r` or `--receiver-port` define the Mesh port

Also you have to either select  
* `-p` or `--peer-discovery` enables the API for remote access (will only enable commands that are necessary for peer discovery)
* `-w` or `--remote-wallet` enable the node for remote access and limit the API to commands that are needed for enabling light wallet access (including peer discovery)  

to enable and get access to peer discovery.

The following parameters are optional:

* `-l` or `--local ipv4/ipv6` , to select which ip address the node should be reachable on. Without this argument the ip address is selected automatically. This parameter is mostly helpful if you have multiple interfaces or if you want to run a node on DS-Lite to specify your ipv6

* `-c` or `--enabled-cors` enable the API cross origin filter: cors domain defined between ''

* `-d` or `--debug` prints on the standard output, more debug informations

* `-e` or `--experimental` activates experimental features.

* `-h` prints the usage
 

For instance a valid call would be:

```
java -jar target/ari-1.0.0.jar -r 14265 -p
```

Note that the API runs on port 14266 by default.

## LICENSE
[GNU General Public License v3.0](https://github.com/AidosKuneen/aidos-node/blob/master/LICENSE)
