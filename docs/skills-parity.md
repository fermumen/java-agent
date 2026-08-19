# Skills parity

The Java harness discovers `SKILL.md` candidates from the same workspace and
compatibility root families used by fx. Workspace roots take precedence over
the managed install root and global `.opencode`, `.codex`, `.claude`, `.agents`,
and `.claw` roots.

The compact metadata parser ports fx's relevant portable contracts:

- frontmatter, no-frontmatter fallback names, CRLF, quoted values, and exact
  end-of-file delimiters;
- folded (`>` and `>-`) and literal (`|`) description blocks;
- UTF-8 byte limits, duplicate recognized keys, malformed quotes, unsafe names,
  control bytes, and unsupported indentation;
- malformed candidates are isolated without hiding valid neighbors.

`java-agent skills` works without API authentication. It supports `list`,
`show`, `create`, `remove`, `path`, and local `add`/`install`, including
`--skill=<name>` selection. Creation uses fx's exact generated template.
Removal is confined to the managed `<JAVA_AGENT_HOME>/skills` root and refuses
compatibility-root skills.

The model-facing `skill` tool re-runs discovery before each read, validates the
advertised identity and location, rejects symlink/path escapes, and reads text
resources in bounded chunks. `install_skill` performs bounded atomic local
installs and refreshes discovery immediately.

Remote GitHub/skills.sh source normalization, shallow cloning, update flows,
and the full-screen skills menu remain parity work. No remote source is fetched
implicitly.
