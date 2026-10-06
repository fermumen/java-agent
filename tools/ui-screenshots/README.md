# UI screenshots

Real screenshots of the interactive shell: java-agent runs in a pseudo-terminal
against a scripted fake Responses API, the raw output bytes are recorded with
named frame marks, and each frame is replayed into xterm.js and captured with
headless Chromium. Use it after any change to the transcript, composer, spinner,
tool lines, or approval box: unit tests assert bytes, but only a rendered frame
shows cursor bugs such as blank gaps left behind by an erase.

## Run

From this directory, with `target/java-agent.jar` built (`mvn package`):

```sh
python3 fake_api.py 18080 &                 # scripted model: think, 3 tools, streamed answer
python3 record.py ../../target/java-agent.jar
kill %1
npm install @xterm/xterm@5 playwright@1
cp <dejavu-fonts>/DejaVuSansMono.ttf <dejavu-fonts>/DejaVuSans.ttf .   # braille spinner glyphs need DejaVuSans
python3 -m http.server 18090 --bind 127.0.0.1 &
CHROMIUM=<chromium-binary> node shoot.mjs   # writes out/<frame>.png and out/row-tNN.png
ffmpeg -framerate 12.5 -pattern_type glob -i 'out/row-t*.png' \
  -vf "split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse" -loop 0 out/shimmer.gif
```

Frames: `idle`, `typing`, `t00`–`t23` (one shimmer sweep, 80 ms apart),
`approval`, `streaming`, `done`, `menu`. Edit the step list at the bottom of
`record.py` and the responses in `fake_api.py` to stage other scenarios.

## Sandbox notes

- Playwright's downloaded Chromium lacks system libraries here; use Nix's:
  `nix --extra-experimental-features 'nix-command flakes' build --no-link --print-out-paths nixpkgs#chromium`
  (also `nixpkgs#dejavu_fonts` and `nixpkgs#ffmpeg-headless`).
- The replay uses `convertEol`, i.e. Windows line endings. On Linux and macOS the
  real shell still staircases `println` output after `stty raw` (bare LF with
  OPOST off); see `docs/terminal-ui-notes.md`.
- The `beanshell` step fails in the recording unless `productivity.jar` is built;
  the red cross is expected.
