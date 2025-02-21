# Contributing

## How to get involved?

All forms of contribution are welcome, including:
- Bug reports, proposals: https://github.com/TouK/nussknacker/issues/new/choose
- Pull requests: https://github.com/TouK/nussknacker/pulls

## Release strategy

We are trying to have regular releases (about once every two months), similar to [the way Kafka does it](https://cwiki.apache.org/confluence/display/KAFKA/Time+Based+Release+Plan).

Our version uses the schema `epoch.major.patch`, which means that changes on both first and second part may be not backward compatible.

## Branch naming conventions

We use the following conventions for branch names:
- `staging` - current, cutting edge changes - tested only via CI (manual tests are performed only before release), can have some "non final" features
- `release/X.Y` - release branch that is cut-off a few weeks before a release (see [Release strategy](#release-strategy) for details), also holds backported patches
- `preview/xxx` - preview branches - pushing to them will cause publication of snapshots on Sonatype and Docker images on DockerHub
- `master` - the latest released version

## Working with Pull requests

If you want to discuss some changes before reporting them, feel free to [start a discussion](https://github.com/TouK/nussknacker/discussions/new?category=q-a) or ask via [mailing list](https://groups.google.com/forum/#!forum/nussknacker).

If you have already prepared a change just submit a [Pull request](https://github.com/TouK/nussknacker/compare) using `staging` branch as target and someone should review it.

Please add changes after a review by using new commits (don't amend old ones) to be able to easily see what was changed.

After resolution of all comments and approval, pull request should be merged using "Squash and merge commit"

### Changelog

All significant changes should be added to [Changelog](docs/Changelog.md). All changes which break API compatibility
should be added to [Migration guide](docs/MigrationGuide.md).

### Documentation

New features and components should have appropriate [Documentation](docs). In particular, all settings
should be documented (with type, default values etc.) in appropriate sections of
[Configuration documentation](docs/installation_configuration_guide/README.md).

When writing documentation please follow these instructions:
* all links to other sections in documentation (in this repo) should point to `md` or `mdx` files 
    
  example of correct link:
    ```
    [main configuration file](./Common.md#configuration-areas)
    ```
  example of incorrect link:
    ```
    [main configuration file](./Common#configuration-areas) 
    ```
* all links to `https://nussknacker.io/documentation/`, but other than `Documentation` section in that page, e.g. to `About` or `Quickstart` sections: 
  * should **not** point to `md` or `mdx` files
  * should **not** be relative
    
  example of correct link:
    ```
    [Components](/about/GLOSSARY#component)
    ```
  example of incorrect links:
    ```
    [Components](/about/GLOSSARY.md#component)
    [Components](../about/GLOSSARY#component)
    ```

## Working with code

### Setup

- JDK >= 9 is needed - we have specified target Java version to Java 8, but using some compiler flags available only on JDK >= 9 (--release flag)
- For building backend - standard `sbt` setup should be enough
- For building of frontend `node` and `npm` will be needed - see [client README](designer/client/README.md) for detailed instruction
- Some tests require `docker`

### Running

#### Running Designer from IntelliJ

Before running from IDE you have to manually build:
- build fronted using [Building frontend instruction](#building-frontend) below (only if you want to test/compile FE, see `Readme.md` in `designer/client` for more details)
- run `sbt prepareDev` - it prepares components, models and copies FE files (generated above)

Run existing configuration `NussknackerApp` automatically loaded from `./run/NussknackerApp.run.xml`

#### Running full version of Designer from command line

Building:
- build fronted using [Building frontend instruction](#building-frontend) below
- run `./buildServer.sh` in `designer`

Run `./runServer.sh` in `designer`

#### Running using integration environment

- Clone [nussknacker-quickstart](https://github.com/TouK/nussknacker-quickstart)
- Run `docker-compose -f docker-compose-env.yml -f docker-compose-custom.yml up -d` inside it

#### Running Designer with model classes on the same classses as designer

To shorten loopback loop during testing of your locally developed components, you can run Nussknacker UI 
in IDE with model classes on the same classpath. Thanks to that, you can skip (re)building of components jars stage.
To test flink-streaming components just run `RunFlinkStreamingModelLocally` from your IDE.
Be aware that it uses stubbed version of DeploymentManager so it won't be possible to deploy scenarios.

If you want to test other components, just extends helper class `LocalNussknackerWithSingleModel`
and add dependency to `designer` module like in flink-streaming case.

#### Setting up Kubernetes environment

To run streaming Lite scenarios with K8s, we recommend using [k3d](https://k3d.io) with
[nussknacker-quickstart](https://github.com/TouK/nussknacker-quickstart) setup
- run integration environment, as described above
- `K3D_FIX_DNS=1 PROJECT_ROOT=$(pwd) k3d cluster create --network nussknacker_network --config=.k3d/single-cluster.yml` 
  This will create K8s cluster, which has access to Docker network used by integration environment. [K3D_FIX_DNS](https://github.com/rancher/k3d/issues/209).
  This cluster will use nginx ingress controller instead of default Traefik to make `rewrite-target` annotation works correctly
- run `sbt buildAndImportRuntimeImageToK3d` (can be skipped if you intend to use e.g. `staging-latest` Docker image)

#### Accessing service

Service should be available at http://localhost:8080/api

#### Troubleshooting

1. If you want to build frontend and have access to it from served application, you can build it using [Building frontend instruction](#building-frontend) below.
It will at the end:
- copy main application static files to `./designer/server/target/scala-XXX/classes/web/static/` and make them accessible via http://localhost:8080/
- copy submodules static files to `./designer/server/target/scala-XXX/classes/web/submodules/` and make them accessible via http://localhost:8080/submodules/*

2. If you want to test the verification mechanism (used during redeployment of Flink scenarios), you need to make a directory with savepoints available from your dev host. You can use `./bindSavepointsDirLocally.sh` script for that.
   At the end you need to turn `FLINK_SHOULD_VERIFY_BEFORE_DEPLOY` flag on in environment variables.

### Building

#### Building frontend
```
./designer/buildClient.sh
```
For more details see [client README](designer/client/README.md)

#### Building tarball
```sbt dist/Universal/packageZipTarball```

#### Building stage - stage is a local directory with all the files laid out as they would be in the final distribution
```
sbt dist/Universal/stage
```

#### Publish Docker images to local repository
```
sbt dist/Docker/publishLocal
```

#### Publish jars to local maven repository
```sbt publishM2```

### Automated testing

You can run tests via `sbt test` or using your IDE.

#### Kubernetes tests

K8s tests are not run by default using `sbt test`. You need to run tests in `ExternalDepsTests` scope e.g. `sbt liteK8sDeploymentManager/ExternalDepsTests/test`.
They will use local `KUBECONFIG` to connect to K8s cluster. If you want to setup your local one, we recommend usage of [k3d](https://k3d.io/).
Tests use some images that should be available on your cluster. Importing of them is done automatically for `k3d`. 
For other clusters you can select other image tag using `dockerTagName` system environment variable or import image manually.  

#### Azure integration tests

Azure integration tests are not run by default using `sbt test`. You need to run tests in `ExternalDepsTests` scope e.g. `sbt schemedKafkaComponentsUtils/ExternalDepsTests/test`.
To run them you should have configured one of authentication options described here:
https://github.com/Azure/azure-sdk-for-java/wiki/Azure-Identity-Examples#authenticating-with-defaultazurecredential e.g. Intellij plugin, Azure CLI or environment variables.
Tests connect to schema registry registered in Event Hubs Namespace from AZURE_EVENT_HUBS_NAMESPACE environment variable (by default nu-cloud).

### Code conventions

#### Java interoperability

The Nussknacker project is developed in Scala, but we want to keep API interoperable with Java in most of places.
Below you can find out some hints how to achieve that:
- Use abstract classes instead of traits in places where the main inheritance tree is easy to recognize
- Don't use package objects. If you need to have more classes in one file, prefer using files with name as a root class
  and subclasses next to root class or class with non-package object
- Be careful with abstract types - in some part of API will be better to use generics instead
- Be careful with `ClassTag`/`TypeTag` - should be always option to pass simple `Class[_]` or sth similar
- Prefer methods intead of function members (def foo: (T) => R)
- Avoid using AnyVal if the class is in API that will be used from Java
