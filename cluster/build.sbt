name := "aegis-cluster"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.3.1" // Or 2.13.x depending on your Akka preference

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "com.google.protobuf" % "protobuf-java-util" % "3.25.3"
)

Compile / PB.targets := Seq(
  scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb"
)
Compile / PB.protoSources += file("../proto")
