# Productivity bundle

Build the shaded library bundle from the repository root:

```sh
mvn -f productivity/pom.xml clean verify
```

The build writes `productivity/target/productivity.jar` and stages a copy at
`target/productivity.jar`. The deterministic smoke runner uses Java
source-file execution and the built JAR. Maven first compiles the runner with
`javac --release 11`, so newer language features or APIs fail the build. It creates temporary XLSX, PDF,
DOCX, PPTX, CSV, and chart PNG artifacts, reopens or extracts them, and checks
their values. It also exercises the bundled parser, math, image, text, YAML,
and metadata services. No provider, API key, or external service is used.

For an artifact-producing task, prefer a reusable Java source file with a
`main` method. Throw an exception or `AssertionError` when a required result is
missing, then reopen the saved file and check its contents before reporting
success. For example, `ProductivitySmoke.java` shows the round-trip pattern for
spreadsheets, PDFs, Office files, CSV, and charts. Run a task script with:

```sh
java --class-path "target/productivity.jar" ValidateReport.java
```

Use JShell for exploration, or for a snippet that explicitly captures and
checks failures. A JShell diagnostic can print an exception and continue to
later snippets, so do not use a printed success line as the only pass/fail
signal for artifact validation. The Maven smoke runner launches a deliberately
failing Java source-file process and verifies that the process exits nonzero.

The smoke checks are deterministic library and artifact-pipeline checks. They
do not measure answer quality from a live model. The runner was executed on
the available Linux JDK 17; an actual Java 11 runtime, Windows `cmd.exe`, and
provider-backed task quality were not evaluated here.
