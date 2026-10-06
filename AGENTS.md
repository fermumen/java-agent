# AGENTS.md

## Target host

The only production target is a locked-down corporate Windows machine:

- A plain Java 11 JRE, such as IBM Semeru on OpenJ9. There is no JDK, so no `jshell`, `javac`, or `java Script.java`.
- `cmd.exe` built-ins only. git, rg, curl, Python, and Node are not installed, and PowerShell may be restricted.
- HTTPS goes through the system proxy and a TLS-inspecting gateway that only the Windows certificate store trusts.
- The model is served from an Azure OpenAI v1 endpoint.

Design and verify every change for that host; the Windows CI workflow is the build that counts. Linux is only the development sandbox: existing Linux code paths stay working so the test suite runs here, but new features are Windows-only. macOS is out of scope.

## YAGNI

Build only what the Windows target needs today: the smallest change that solves the problem in front of you. Add a portability layer, configuration knob, or abstraction only when a concrete need on the target host calls for it.
