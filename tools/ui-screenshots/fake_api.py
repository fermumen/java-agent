"""Fake OpenAI Responses endpoint scripted for UI screenshots.

Request 1 waits 3 s (thinking shimmer), then calls grep_files; request 2 calls
read_file; request 3 calls beanshell (approval box); request 4 streams a
markdown answer with usage. Usage: python3 fake_api.py <port>
"""
import json, time, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = ("import java.nio.file.*;\n"
          "var lines = Files.readAllLines(Paths.get(\"src/main/java/dev/fxjava/Spinner.java\"));\n"
          "int code = 0, comments = 0, blank = 0;\n"
          "for (String line : lines) {\n"
          "  String t = line.trim();\n"
          "  if (t.isEmpty()) blank++;\n"
          "  else if (t.startsWith(\"*\") || t.startsWith(\"/*\") || t.startsWith(\"//\")) comments++;\n"
          "  else code++;\n"
          "}\n"
          "print(\"code=\" + code + \" comments=\" + comments + \" blank=\" + blank);\n")

ANSWER = """## Shimmer indicator

The spinner paints `Thinking…` in **muted gray** and sweeps a bright band across it:

- `BAND` holds the gray levels by distance from the head (`23, 20, 17, 14`)
- `SWEEP_PAUSE` adds idle steps so it reads as a *pulse*, not a scroll
- Frames derive from the injected clock, so tests drive cadence exactly

| Constant | Value | Purpose |
|---|---|---|
| `BAND` | 4 levels | brightness falloff |
| `SWEEP_PAUSE` | 6 steps | gap between sweeps |

To make the band configurable, pass it through the constructor next to `label`.
"""

def completed(rid, output, inp, out):
    return {"type": "response.completed", "response": {"id": rid, "object": "response",
            "status": "completed", "output": output,
            "usage": {"input_tokens": inp, "output_tokens": out, "total_tokens": inp + out}}}

def call(n, name, args):
    return [{"id": f"fc_{n}", "type": "function_call", "call_id": f"call_{n}", "name": name,
             "arguments": json.dumps(args), "status": "completed"}]

count = 0
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_POST(self):
        global count
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        count += 1
        n = count
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        def send(ev):
            self.wfile.write(b"data: " + json.dumps(ev).encode() + b"\n\n"); self.wfile.flush()
        if n == 1:
            time.sleep(3.0)
            send(completed("r1", call(1, "grep_files", {"pattern": "shimmer", "path": "src/main/java"}), 4210, 38))
        elif n == 2:
            time.sleep(1.2)
            send(completed("r2", call(2, "read_file", {"path": "src/main/java/dev/fxjava/Spinner.java"}), 5120, 31))
        elif n == 3:
            time.sleep(1.2)
            send(completed("r3", call(3, "beanshell", {"script": SCRIPT}), 7930, 212))
        else:
            time.sleep(1.5)
            for i in range(0, len(ANSWER), 12):
                send({"type": "response.output_text.delta", "delta": ANSWER[i:i+12]}); time.sleep(0.03)
            send(completed("r4", [{"id": "m4", "type": "message", "role": "assistant", "status": "completed",
                 "content": [{"type": "output_text", "text": ANSWER, "annotations": []}]}], 9875, 486))
        self.close_connection = True

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
