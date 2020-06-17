---
title: "ScalaPBC"
layout: docs
---

# ScalaPBC: ScalaPB standalone compiler

ScalaPBC is a tool that lets you generate Scala sources from the command line (or from a maven build).

## Install with Coursier (Recommended)

The recommended way to install ScalaPBC is with Coursier.

> To install Coursier see [here](https://get-coursier.io/docs/cli-overview)

Install the latest version with:
```bash
coursier install --channel https://git.io/JvblJ scalapbc
```

To install a specific version (with sudo if necessary)
```bash
coursier bootstrap com.thesamet.scalapb::scalapbc:{{site.data.version.scalapb}} -o /usr/local/bin/scalapbc
```
## Download a zip

You can download the current release here: [scalapbc-{{site.data.version.scalapb}}.zip](https://github.com/scalapb/ScalaPB/releases/download/v{{site.data.version.scalapb}}/scalapbc-{{site.data.version.scalapb}}.zip).

Older versions can be found in the [releases page](https://github.com/scalapb/ScalaPB/releases).

Unzip the file, and inside you will find two scripts: `bin/scalapbc` (and
`bin/scalapbc.bat`) that can be used on Unix/Linux/Mac OS X (and Windows,
respectively). The following text assumes the `scalapbc` executable is now in your `PATH`.

## Usage

ScalaPBC is used exactly like protoc. In fact, scalapbc calls protoc.
It ships with [multiple versions](https://github.com/os72/protoc-jar) of protoc embedded in the jar and you can pick the one you want to use by passing the desired version as the
first command line argument:

```bash
scalapbc -v3.11.1 [options]
```

To generate Scala code, invoke ScalaPBC like this:

```bash
scalapbc -v3.5.1 --scala_out=some/output/directory myproto.proto
```

To generate both Scala code and Java code along with Java conversions:

```bash
scalapbc -v3.5.1 \
    --scala_out=java_conversions:some/output/directory \
    --java_out=some/output/directory \
    myproto.proto
```

> The generated code depends on `scalapb-runtime` to compile. You will To get the code to work, add a dependency on [https://mvnrepository.com/artifact/com.thesamet.scalapb/scalapb-runtime] to your project. The version of `scalapb-runtime` needs to match or be newer than the version of the plugin.

## Passing generator parameters

If you would like to pass additional options, like `java_conversions`,
`flat_package`, or `single_line_to_proto_string`, it can be done like this:

```bash
scalapbc my.proto --scala_out=OPT1,OPT2:path/to/output/dir/
```

where OPT1,OPT2 is a comma-separated list of options, followed by a colon
(`:`) and then the output directory. For example:

```bash
scalapbc my.proto --scala_out=flat_package,java_conversions:protos/src/scala/main/
```

The supported parameters are: `flat_package`, `java_conversions`, `grpc` and `single_line_to_proto_string`, `no_lenses`, `retain_source_code_info`.

Those parameters are described in [SBT settings]({{site.baseurl}}/sbt-settings.html#additional-options-to-the-generator)

## Loading additional generators from Maven

ScalaPBC (starting version 0.10.1) can fetch generators from Maven using Coursier:

```bash
scalapbc \
  --plugin-artifact=io.grpc:protoc-gen-grpc-java:1.27.2:default,classifier=linux-x86_64,ext=exe,type=jar -- \
  e2e/src/main/protobuf/service.proto \
  --grpc-java_out=/tmp/out \
  -Ie2e/src/main/protobuf -Ithird_party -Iprotobuf
```

If you use zio-grpc, you can use the following command to generate services that use ZIO. This also generates ScalaPB case classes for messages and the GRPC descriptors that the generated ZIO code depends on.

```bash
scalapbc --plugin-artifact=com.thesamet.scalapb.zio-grpc:protoc-gen-zio:{{site.data.versions.ziogrpc}}:default,classifier=unix,ext=sh,type=jar -- e2e/src/main/protobuf/service.proto --zio_out=/tmp/out --scala_out=grpc:/tmp/out -Ie2e/src/main/protobuf -Ithird_party -Iprotobuf
```

## Using ScalaPB as a proper protoc plugin

You may want to use ScalaPB code generator as a standard protoc plugin (rather
than using scalapbc as a wrapper or through SBT).

For Linux and Mac OS X, you can download a native executable version of the plugin for Scala from our [release page](https://github.com/scalapb/ScalaPB/releases):

* For Linux: [protoc-gen-scala-{{site.data.version.scalapb}}-linux-x86_64.zip](https://github.com/scalapb/ScalaPB/releases/download/v{{site.data.version.scalapb}}/protoc-gen-scala-{{site.data.version.scalapb}}-linux-x86_64.zip)
* For OS X: [protoc-gen-scala-{{site.data.version.scalapb}}-osx-x86_64.zip](https://github.com/scalapb/ScalaPB/releases/download/v{{site.data.version.scalapb}}/protoc-gen-scala-{{site.data.version.scalapb}}-osx-x86_64.zip)

Those zip files contain native executables of the plugin for the respective operating system built using GraalVM. If you are using another operating system (such as Windows), or prefer to use a JVM based plugin implementation, you will find in [scalapbc-{{site.data.version.scalapb}}.zip](https://github.com/scalapb/ScalaPB/releases/download/v{{site.data.version.scalapb}}/scalapbc-{{site.data.version.scalapb}}.zip) an executable named `bin/protoc-gen-scala` which requires a JVM to run (a JVM needs to be available on the path, or through the `JAVA_HOME` environment variable)

To generate code:

    protoc my.protos --plugin=/path/to/bin/protoc-gen-scala --scala_out=scala

On Windows:

    protoc my.protos --plugin=protoc-gen-scala=/path/to/bin/protoc-gen-scala.bat --scala_out=scala

For passing parameters to the plugin, see the section above.

> Note that the standalone plugin provided in `scalapbc` needs to be able to find a JVM in the path or through `JAVA_HOME` environment variable. If you encounter unexpected errors, try to execute the plugin directly from the command line, and the output printed may be useful for further debugging.

> The generated code depends on `scalapb-runtime` to compile. You will To get the code to work, add a dependency on [https://mvnrepository.com/artifact/com.thesamet.scalapb/scalapb-runtime] to your project. The version of `scalapb-runtime` needs to match or be newer than the version of the plugin.
