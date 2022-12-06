# Nessie Query Engine Integrations Tests

(Abbreviated as _NesQuEIT_, pronounced `[neskwit]`)

[![Nessie / Iceberg](https://github.com/projectnessie/query-engine-integration-tests/actions/workflows/main.yml/badge.svg)](https://github.com/projectnessie/query-engine-integration-tests/actions/workflows/main.yml)

This project is meant to test various query engines (Spark, Flink, Presto, etc) against
[Nessie](https://projectnessie.org/) and [Apache Iceberg](https://iceberg.apache.org/).

Test runs are driven by either configuring the released versions of Nessie and Iceberg, or by using
Git clones of the respective heads of [Nessie's main branch](https://github.com/projectnessie/nessie)
and/or [Iceberg's master branch](https://github.com/apache/iceberg).

Goals:
* Be and stay open source!
* Run the query engine integration tests "from your laptop" w/o having to provision external
  machinery.
* Ability to run tests "from your IDE" (in IntelliJ), although it might require tweaking
  [this](./gradle.properties) and/or [this](./frameworks-versions.properties) file to specify the
  required version(s).
* Run the integration tests rather quickly even on tiny-ish instances, like GitHub's default runners.
* Optionally run tests against external machines, like already configured Spark/Flink/Presto
  clusters or Nessie instances.

Possible tests:
* Test Spark against Iceberg + Nessie. All Spark + Scala versions that both Iceberg + Nessie
  support can be used.
* Test Flink against Iceberg + Nessie. All Flink versions That Iceberg supports can be used.
* Test Presto against Iceberg + Nessie. All Presto versions that support the currently used Iceberg
  version can be used.
* Cross-engine tests using Spark + Flink simultaneously against Iceberg + Nessie.
  All Spark + Scala + Flink versions that both Iceberg + Nessie support can be used.

Possible tests with a local Nessie Git clone:
* Run tests in Nessie that test against Apache Iceberg 

Possible tests with a local Iceberg Git clone:
* Run tests in Apache Iceberg that test against Nessie 

## Introduction

The _NesQuEIT_ for Nessie leverages Gradle included builds for referenced projects, where possible.
This means, that IDEs (at least IntelliJ) allows you to work on all three code bases (integrations
tests, Nessie and Iceberg).

A major advantage of this project is that tests can be debugged in an IDE like IntelliJ.

**IMPORTANT** Setup the required included builds as described below **before** you open this project
in your IDE!!

Tests may run against an ephemeral Nessie instance per (Gradle) test task, but tests may also run
concurrently against a _shared_ and _external_ Nessie instance. Writing to the default branch will
likely result in intermittent or reproducible test failures.

The Gradle build automatically generates one Gradle project for each configured configuration of
query engine versions in combination with Iceberg + Nessie. For example: For Nessie 0.40 and
Iceberg 0.14, 5 Gradle projects with Spark 3.1, 3.2, 3.3, and Scala 2.12 + 2.13 are created
(Spark 3.1 is only supported with Scala 2.12). Similar for Flink, 2 Gradle projects with Flink 1.14
and 1.15 are created.
Use `./gradlew projects -Dnessie.versionNessie=0.40.1 -Dnessie.versionIceberg=0.14.0` to see the
list of projects available for those released versions. 

Tests for each query engine (Spark, Flink, Presto) use the same test code, so a lot of code
duplication is prevented.

Tests also leverage JUnit 5 extensions to provide e.g. a Spark session or a JDBC connection for
Presto.

The project can be imported into IntelliJ ([see below](#for-developers)).

### Presto integration

Unlike Spark + Flink, which are integrated "from Iceberg", Presto integrates Iceberg (the other
way around). While _NesQuEIT_ can already use local Git clones of Nessie + Iceberg, it would be
good to also include a local Git clone of Presto. However, Presto does not use Gradle but Maven.
A Gradle plugin to include Maven builds in Gradle builds is in early development, but is meant to
support using [Presto's main branch](https://github.com/prestodb/presto) directly in this project.

Ideally the cross-engine test should also include Presto, but dependency issues against Parquet
prevent this.

## Running the tests

By default, _NesQuEIT_ includes source builds of Nessie Iceberg. If you want to use released versions
instead of source builds, you can do so by using the following system properties.

| System property              | Meaning and default                                                                                                                                                                                       |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `nessie.versionNessie`       | The version of Nessie to use. Defaults to the included build in `included-builds/nessie`.                                                                                                                 |
| `nessie.versionNessieServer` | The version of Nessie to use for the Nessie server being launched. Defaults to `nessie.versionNessie`.                                                                                                    |
| `nessie.versionIceberg`      | The version of Nessie to use. Defaults to the included build in `included-builds/iceberg/`.                                                                                                               |
| `nessie.externalNessieUrl`   | Nessie REST API endpoint to use from tests instead of a Nessie server launched for each test. **DO NOT USE THE `nessie.client.uri` PROPERTY!**                                                            |
| `nessie.test.keepReferences` | Nessie related tests delete all references created by them. If it is helpful to keep the references, set this property to `true`.                                                                         |
| `nessie.client.*`            | All system properties starting with `nessie.client.` are passed as configuration options to the Iceberg `NessieCatalog`, and further down to the Nessie client. (The prefix `nessie.client.` is removed.) |
| `spark.master.url`           | Spark URL to use from tests, defaults to `local[2]`. Example: see below.                                                                                                                                  |
| `spark.log.level`            | Spark log level. Defaults to `WARN`.                                                                                                                                                                      |
| `flink.remote.host`          | Hostname of the externally provided Flink cluster. Tests will use a JVM-local Flink, if this property is not set.                                                                                         |
| `flink.remote.port`          | Port number of the externally provided Flink cluster. Only used, when `flink.remote.host` is specified. Defaults to `8081`.                                                                               |
| `flink.config.*`             | Additional Flink configuration settings added to a Flink `Configuration` object, with the `flink.config.` prefix removed. Only used, when `flink.remote.host` is specified.                               |
| `presto.session.*`           | Additional Presto session properties, added with the `presto.session.` prefix removed.                                                                                                                    |
| `presto.jdbc.host-port`      | The optional JDBC host and port to connect to an already provisioned Presto endpoint.                                                                                                                     |
| `nessie.inttest.location.*`  | Supports specifying URIs for these types (replace the `*`): `iceberg.warehouse`, `hive.metastore.catalog`, `presto.local.path`.                                                                           |
| `withMavenLocal`             | When set to `true`, the local maven repository will be added to the queried repositories.                                                                                                                 |

## Rerunning tests

Gradle assumes that tests do not need to be run, if the inputs (aka source files, configuration
including system properties, etc.) do not change. For integration tests however, it is often
necessary to rerun tests. There are two options:

1. Unconditionally run the tests. This can be achieved by setting the Gradle _project_ property
   `testRerun`. Example:
   ```bash
   ./gradlew :nqeit-iceberg-flink-1.15:intTest -PtestRerun
   ```
2. Run tests multiple times. This unconditionally runs and repeats all matching tests. Specify
   the number of repetitions using the Gradle _project_ property `testRepetitions`. Example:
   ```bash
   ./gradlew :nqeit-iceberg-flink-1.15:intTest -PtestRepetitions=3
   ```

## Scenarios

### Example: Using released versions of Nessie and Iceberg

```bash
./gradlew\
  :nqeit-iceberg-spark-3.1:intTest\
  :nqeit-iceberg-spark-3.2:intTest\
  -Dnessie.versionNessie=0.30.0\
  -Dnessie.versionIceberg=0.14.0
```

### Example: Using Nessie from a Git worktree + released Iceberg version

(See below now to set up a Git worktree for Nessie.)

```bash
./gradlew\
  :nqeit-iceberg-spark-3.1:intTest\
  :nqeit-iceberg-spark-3.2:intTest\
  -Dnessie.versionIceberg=0.14.0
```

### Example: Using an external Spark cluster + Nessie server from Git clone

Local Spark cluster:
```bash
$SPARK_HOME/sbin/start-master.sh
$SPARK_HOME/sbin/start-worker.sh
```

Running the Spark/Iceberg tests:
```bash
# 1. Ensure your local Spark master + worker(s) are up and running
# 2. Get Spark master URL

./gradlew\
  :nqeit-iceberg-spark-3.1:intTest\
  :nqeit-iceberg-spark-3.2:intTest\
  -Dspark.master.url=spark://localhost:7077
```

### Example: Using an external Spark cluster + external Nessie server

Local Spark cluster (Spark 3.2 for example):
```bash
$SPARK_HOME/sbin/start-master.sh
$SPARK_HOME/sbin/start-worker.sh
```

Running the Spark/Iceberg tests:
```bash
# 1. Ensure your local Spark master + worker(s) are up and running
# 2. Get Spark master URL
# 3. Start local Nessie server
# 4. Get Nessie REST API base URI

./gradlew\
  :nqeit-iceberg-spark-3.1:intTest\
  :nqeit-iceberg-spark-3.2:intTest\
  -Dspark.master.url=spark://localhost:7077\
  -Dnessie.externalNessieUrl=http://localhost:19120/api/v1
```

### Example: Using an external Flink cluster + external Nessie server

Local Flink cluster (Flink 1.15 for example):
```bash
$FLINK_HOME/bin/start-cluster.sh
```

Running the Flink/Iceberg tests:
```bash
# 1. Ensure your local Flink cluster is up and running
# 2. Get Flink host + port values
# 3. Start local Nessie server
# 4. Get Nessie REST API base URI

./gradlew\
  :nqeit-iceberg-flink-1.14:intTest\
  :nqeit-iceberg-flink-1.15:intTest\
  -Dflink.remote.host=127.0.0.1\
  -Dflink.remote.port=8081\
  -Dnessie.externalNessieUrl=http://localhost:19120/api/v1
```

## Flink, Iceberg, Spark, Scala and Nessie versions

The matrix to list all possible project version combinations would be pretty huge. This project aims
to reduce the amount of test and build code needed. The same test code is used to run against
different versions of the tested query engine. This means that there is only one set of test classes
and only one build script for Iceberg+Flink and for Iceberg-Spark. The build scripts take care of
generating the "correct" test version matrix.

## Using external Spark

The integration tests "just use" the local (embedded) or provided (external) Spark cluster, but do
not provide any libraries to it. This means, that there's no "spark-submit" or the like. Using or
relying on the existence of for example Iceberg or Nessie classes _inside_ the Spark workers will
result in failures.

However, using "standard Spark SQL" or "standard Spark Dataset/DataFrames/RDDs" works as expected.

## Using Dremio
The dremio integration tests requires a Base-URL, PAT(Personal Access Token), Project-id and the Catalog-name. If the following arguments are not provided, the tests will be skipped.

### Example: Running the Dremio-Iceberg tests

```bash
./gradlew :nqeit-iceberg-dremio:intTest -PtestJvmArgs="\
-Ddremio.url=<dremio-url> \
-Ddremio.token=<token> \ 
-Ddremio.project-id=<project-id> \ 
-Ddremio.catalog-name=<catalog-name>
```

### Example: Running Dremio-iceberg tests with external Nessie Server

```bash
./gradlew :nqeit-iceberg-dremio:intTest \
-PtestJvmArgs="\
-Ddremio.url=<dremio-url> \
-Ddremio.token=<token> \ 
-Ddremio.project-id=<project-id> \ 
-Ddremio.catalog-name=<catalog-name> \
-Dnessie.client.uri=<nessie-uri> \ 
-Dnessie.client.authentication.type=BEARER \
-Dnessie.client.authentication.token=<token>"
```

### Example: Running Cross Engine tests for Spark, Flink and Dremio with external Nessie Server

```bash
# 1. Ensure that the following env variables are set: AWS_SECRET_ACCESS_KEY, AWS_ACCESS_KEY_ID, AWS_REGION
# 2. AWS credentials are set in the Catalog Settings.
# 3. Privileges(CREATE TABLE, DROP, INSERT and SELECT) are provided in the Catalog Settings.

./gradlew :nqeit-cross-engine-3.2-2.12-1.14:intTest --tests "org.projectnessie.integtests.crossengine.ITCrossEngineDremio" -PtestJvmArgs="\
-Dnessie.client.uri=<nessie-uri> \
-Dnessie.client.authentication.type=BEARER \
-Dnessie.client.authentication.token=<token> \
-Dnessie.client.ref=<ref_name> \
-Ddremio.url=<dremio-url> \
-Ddremio.token=<token> \
-Ddremio.project-id=<project-id> \
-Ddremio.catalog-name=<catalog-name> \
-Dnessie.inttest.location.iceberg.warehouse==s3://my-bucket \
-Dnessie.client.io-impl=org.apache.iceberg.aws.s3.S3FileIO"
```
Note:
1. `dremio-url` and `dremio-token` must be set according to the following documentation: [dremio-url and dremio-token](https://docs.dremio.com/cloud/api/)
2. The `project-id` can be found on the Sonar Projects Setting page, under General Information named as "Project-ID".
3. The `nessie-uri` and `catalog-name` can be found on the Catalog Settings page, under General Information named as "Catalog Endpoint" and "Catalog Name".

## For developers

The recommended way to "link" this project to "latest Nessie" and "latest Iceberg" is to put those
into the [`included-builds/`](included-builds) directory.

All "linked projects" (Nessie, Iceberg) must include the code changes (patches) that are necessary
to make the code bases work together. Think: Nessie requires code changes on top of the `main`
branch to let Nessie's Spark extensions work with the latest version of Iceberg. For this reason,
we maintain "integrations branches" with the necessary changes.

### Git worktree

Notes:
* including Nessie source builds only works with Nessie built with Gradle.
* including Iceberg source builds only works with recent Iceberg from the master branch.

The easiest way to implement this locally is to use [Git worktree](https://git-scm.com/docs/git-worktree).

1. Clone this repository and save the path in `NESSIE_INTEGRATION_TESTS`
   ```shell
   git clone https://github.com/projectnessie/nessie-integration-tests
   NESSIE_INTEGRATION_TESTS=$(realpath nessie-integration-tests)
   ```
2. Go to your local Nessie clone and create a Git worktree in the [`included-builds/`](included-builds)
   directory.
   ```shell
   cd PATH_TO_YOUR_LOCAL_NESSIE_CLONE
   git branch -b integ-bump/iceberg origin/integ-bump/iceberg
   git worktree add ${NESSIE_INTEGRATION_TESTS}/included-builds/nessie integ-bump/iceberg
   ```
3. Go to your local Iceberg clone and create a Git worktree in the [`included-builds/`](included-builds)
   directory.
   ```shell
   cd PATH_TO_YOUR_LOCAL_ICEBERG_CLONE
   git branch -b master-nessie origin/master
   git worktree add ${NESSIE_INTEGRATION_TESTS}/included-builds/iceberg master-nessie
   ```
   Note: the above example uses a Git worktree with a branch "detached" from the
   origin's `master` branch. As long as there are no code changes necessary, it might be way more
   convenient to just create a symbolic link to your local Iceberg clone containing the already
   checked out `master` branch.

#### Symbolic links

**DISCLAIMER** Including the Nessie build does **not** work correctly in IntelliJ!
Do _always_ use a Git worktree (or Git clone) as discussed above.

As an alternative, you can also create symbolic links called `nessie` and `iceberg` to your local
clones/worktrees with the "right" code. Example:
```shell
ln -s INSERT_PATH_TO_YOUR_LOCAL_NESSIE_CLONE included-builds/nessie
ln -s INSERT_PATH_TO_YOUR_LOCAL_ICEBERG_CLONE included-builds/iceberg
```

### Checking if everything works

Canary build:
```bash
./gradlew :nessie:clients:client:jar :iceberg:iceberg-nessie:jar :iceberg:iceberg-core:jar
```

Run Iceberg/Nessie tests:
```bash
./gradlew :iceberg:iceberg-nessie:test
```

Run Nessie Spark 3.2 Extensions tests:
```bash
./gradlew :nessie:clients:spark-32-extensions:intTest
```

Run the actual integrations tests:
```bash
./gradlew intTest
```

## Included Maven projects

This project can also use Maven projects, currently Presto, which has support for Nessie.

While Gradle supports included builds and supports substitution of dependencies, Maven builds can
only rely on local Maven repositories. This means, that before any Maven based project can be
tested, Nessie and Iceberg need to be built and published to the local Maven repository.

This project has, in theory, everything that's needed to publish snapshot artifacts to your local
Maven repo directly by just running `./gradlew publisLocal`. Sadly doesn't work yet.

## In CI

CI builds are triggered using GitHub actions. See [main.yml](.github/workflows/main.yml).

CI will fetch the latest commit from any project's main/master branch and apply the necessary
changes by merging the configured "patch branch".
