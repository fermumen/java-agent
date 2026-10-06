"""Drives java-agent in a pseudo-terminal against fake_api.py and records the raw
output bytes, marking named frames for index.html to replay.

Usage: python3 record.py <java-agent.jar> [port]   (writes session.bin, marks.json)
"""
import json, os, pty, select, sys, time

jar = os.path.abspath(sys.argv[1])
port = sys.argv[2] if len(sys.argv) > 2 else '18080'
cols, rows = 92, 34
here = os.path.dirname(os.path.abspath(__file__))
workspace = os.path.abspath(os.path.join(here, '..', '..'))

pid, fd = pty.fork()
if pid == 0:
    os.environ.update(OPENAI_API_KEY='sk-demo', TERM='xterm-256color')
    os.execvp('bash', ['bash', '-c', f'stty cols {cols} rows {rows}; cd "{workspace}" && '
               f'exec java -jar "{jar}" --no-save --base-url http://127.0.0.1:{port}/v1'])

buf = bytearray()
marks = {}

def pump(seconds):
    end = time.time() + seconds
    while time.time() < end:
        ready, _, _ = select.select([fd], [], [], 0.02)
        if ready:
            try:
                buf.extend(os.read(fd, 65536))
            except OSError:
                return

def mark(name):
    marks[name] = len(buf)

def type_slowly(text):
    for ch in text:
        os.write(fd, ch.encode())
        pump(0.02)

pump(4); mark('idle')
type_slowly('Explain how the spinner shimmer works'); pump(0.4); mark('typing')
os.write(fd, b'\r'); pump(0.3)
for i in range(24):                      # one full shimmer sweep at the 80 ms spinner cadence
    pump(0.08); mark(f't{i:02d}')
pump(4.3); mark('approval')
os.write(fd, b'y'); pump(1.0); mark('streaming')
pump(6.0); mark('done')
os.write(fd, b'/st'); pump(0.6); mark('menu')
os.write(fd, b'\x15\x04'); pump(1.5)    # Ctrl+U, Ctrl+D: clear and exit

open(os.path.join(here, 'session.bin'), 'wb').write(bytes(buf))
json.dump({'cols': cols, 'rows': rows, 'marks': marks}, open(os.path.join(here, 'marks.json'), 'w'))
print(marks)
