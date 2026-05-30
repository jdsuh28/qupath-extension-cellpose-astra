# ASTRA Extension Release Packaging

The installable ASTRA artifact is the runtime JAR published by this repository's
release process and referenced by the ASTRA QuPath catalog.

Source tags identify the extension source snapshot used for a release. Release
JARs contain the resources QuPath needs at runtime, including ASTRA workflow
resources, runtime metadata, and packaged helper scripts.

Generated build outputs, generated Javadocs, IDE metadata, operating-system
artifacts, and local Gradle state are not source files and must not be tracked
or packaged accidentally.

Bytecode obfuscation is not enabled. Obfuscating Java classes would only make
compiled extension bytecode harder to inspect; it would not protect text
resources bundled inside the JAR. Any future runtime-protection work must
account for bundled runtime resources explicitly.
