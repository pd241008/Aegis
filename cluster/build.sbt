name := "aegis-cluster"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.3.1" // Or 2.13.x depending on your Akka preference

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "com.google.protobuf" % "protobuf-java-util" % "3.25.3",
  "com.google.code.gson" % "gson" % "2.11.0",
  "org.scalameta" %% "munit" % "1.0.0" % Test
)

Test / PB.protoSources += file("../proto")

Compile / PB.targets := Seq(
  scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb"
)
Compile / PB.protoSources += file("../proto")

assembly / assemblyJarName := "aegis-cluster.jar"
assembly / mainClass := Some("com.aegis.cluster.Main")
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
  case PathList("META-INF", xs @ _*)             => MergeStrategy.first
  case PathList("google", "protobuf", xs @ _*)   => MergeStrategy.first
  case "module-info.class"                        => MergeStrategy.discard
  case x if x.endsWith(".proto")                 => MergeStrategy.rename
  case x                                          => MergeStrategy.first
}
