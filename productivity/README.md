# Productivity bundle

Build the shaded library bundle from the repository root:

```sh
mvn -f productivity/pom.xml clean verify
```

The build writes `productivity/target/productivity.jar` and stages a copy at
`target/productivity.jar`. The JAR's main class is `dev.fxjava.productivity.BshRun`,
a fail-closed BeanShell 2.0b6 runner for targets that only have a JRE (no
`jshell`, no `javac`, no `java Script.java`):

```sh
java -jar "target/productivity.jar" ValidateReport.bsh [args...]
java -jar "target/productivity.jar" - < snippet.bsh
```

The agent's `beanshell` tool calls this runner, passing inline scripts on
standard input. Script arguments are available as `bsh.args`. Scripts are read as UTF-8, with or
without a BOM. The runner exits 0 only when the whole script completes, keeps
the status of an explicit `System.exit(n)`, exits 1 for parse errors, evaluation
errors, and uncaught exceptions, and exits 64 for usage errors. The stock
`bsh.Interpreter` entry point prints failures and still exits 0, so do not use
it for validation.

BeanShell 2.0b6 is the last release on Maven Central. It accepts Java 1.4-style
syntax plus for-each, autoboxing, and string `switch`. It does not parse
generics, lambdas, method references, or try-with-resources. Varargs calls need
an explicit array, such as `String.format("%s", new Object[] {x})`. `throw`
accepts only `Exception` types, so throw `IllegalStateException` instead of
`AssertionError`. These limits were checked on Temurin JRE 11, 17, and 21.

For an artifact-producing task, write a reusable script, throw an exception
when a required result is missing, then reopen the saved file and check its
contents before reporting success.

The deterministic smoke runner, `ProductivitySmoke.java`, is a build-time check.
Maven compiles it with `javac --release 11`, so newer language features or APIs
fail the build. It creates temporary XLSX, PDF, DOCX, PPTX, CSV, and chart PNG
artifacts, reopens or extracts them, and checks their values. It also exercises
the bundled parser, math, image, text, YAML, and metadata services. It then
launches `java -jar productivity.jar` child processes to check that a
BeanShell script can write and reopen a workbook, that a BOM-prefixed script
runs, and that thrown exceptions, parse errors, evaluation errors, and missing
scripts all exit nonzero. No provider, API key, or external service is used.

The smoke checks are deterministic library and artifact-pipeline checks. They
do not measure answer quality from a live model. They pass on a Linux JDK 11
build. Windows `cmd.exe` and provider-backed task quality were not evaluated
here.
