"""Minimal RCON client for the local test server: python rcon.py "<command>" [...]
Port and password are read from run/server.properties."""
import os
import socket
import struct
import sys

PROPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "run", "server.properties")


def server_props():
    props = {}
    try:
        with open(PROPS, encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    k, v = line.rstrip("\n").split("=", 1)
                    props[k.strip()] = v.strip()
    except OSError:
        pass
    return props


def packet(rid, kind, body):
    data = struct.pack("<ii", rid, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def recv(s):
    n = struct.unpack("<i", s.recv(4))[0]
    buf = b""
    while len(buf) < n:
        buf += s.recv(n - len(buf))
    rid, kind = struct.unpack("<ii", buf[:8])
    return rid, buf[8:-2].decode("utf-8", "replace")


def run(cmds, host="127.0.0.1", port=None, password=None):
    props = server_props()
    port = port or int(props.get("rcon.port", 25575))
    password = password or props.get("rcon.password", "")
    s = socket.create_connection((host, port), timeout=15)
    s.sendall(packet(1, 3, password))
    rid, _ = recv(s)
    if rid == -1:
        raise SystemExit("rcon auth failed")
    for c in cmds:
        s.sendall(packet(2, 2, c))
        _, out = recv(s)
        print("> " + c)
        if out:
            print(out)
    s.close()


if __name__ == "__main__":
    run(sys.argv[1:])
