# fx filesystem parity

Windows is the authoritative filesystem target for the Java port. Portable tests
still run on development hosts, while `WorkspaceToolsWindowsTest` owns Windows
path separators, case-insensitive lookup, drive/root confinement, and the
cross-tool mutation flow.

The Java tests derive from these fx owners:

- `fx/src/tools/filesystem/*.zig`: decoding, validation, output, limits,
  classification, normal execution, missing paths, replacement, and same-path
  behavior.
- `fx/src/core/workspace/pathing.zig`: lexical traversal, canonical containment,
  symlink containment, existing targets, and create targets.
- `fx/src/core/workspace/list_files_listing.zig`: compact listing suffixes,
  ordering, empty output, and truncation.
- `fx/src/core/workspace/grep_search.zig`: literal search, direct-file and
  directory traversal, text filtering, modes, pagination, context, and caps.
- `fx/tests/e2e/file-tool-paths.test.ts`: canonical path behavior and mutation
  flows. Unix home aliases and POSIX-only symlink cases are intentionally not
  targets.
- `fx/tests/e2e/file-tool-permissions.test.ts`: external-path permission
  decisions. This remains part of the permission-parity phase.

## Current contract owners

| Java test | Contract |
| --- | --- |
| `FxCoreFilesystemContractTest` | list, read, grep, write, edit |
| `FxFilesystemParityTest` | all 13 tools, schemas, classification, and the remaining eight implementations |
| `WorkspaceToolsWindowsTest` | Windows-native paths and cross-tool behavior |
| `WorkspaceToolsTest` | workspace escape and symlink safety regression tests |

The tool catalog is deliberately compact: `FxCoreFileTools` implements the five
high-frequency tools, `FxFileTools` implements the remaining eight, and
`WorkspaceTools` owns only the shared workspace boundary and captured command
execution.
