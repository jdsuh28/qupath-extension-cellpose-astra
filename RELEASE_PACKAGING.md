# ASTRA Extension Release Packaging

ASTRA extension source tags are extension-owned source snapshots. They are not expected to contain the vendored ASTRA base repository tree.

Runtime release JARs are the installable QuPath artifacts. A release JAR vendors the ASTRA runtime resources used by the compact scripts, including `astra/manifests/master-contract.json`, pipeline entrypoints, shared helpers, and runner sources.

When validating a published release, inspect the generated JAR rather than the extension source tag for vendored ASTRA runtime resources. The source tag records the launcher/build code that produced the artifact; the JAR records the runtime payload delivered to QuPath.
