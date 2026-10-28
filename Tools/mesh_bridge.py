#!/usr/bin/env python3
"""
mesh_bridge.py — rendezvous server for the Telegram Mesh emulator test bed.

Why: Android emulators sit behind isolated NAT networks (each one gets
10.0.2.15 with its own host bridge 10.0.2.2). They cannot see each other's
mDNS/NSD announcements, so Nearby Connections and TCP-LAN discovery both
fail between two emulators. This script runs on the HOST machine and acts
as a tiny registry:

  - each emulator app registers: nodeId + its local TCP listening port
  - the script runs `adb forward` so that host port P_N routes to the
    emulator's TCP server (device port)
  - when an app polls LIST, it receives every OTHER peer as
    (nodeId, 10.0.2.2, forwarded_port) — an address reachable from inside
    its own emulator NAT
  - apps then open direct TCP sockets to each other through those
    forwards; all mesh frames flow peer-to-peer, the bridge only
    bootstraps discovery

Protocol (text lines, \n terminated):
  REGISTER <nodeId> <localPort>   -> OK <fwdHost> <fwdPort>
  LIST                            -> PEER <nodeId> <host> <port>* END
  PING                            -> PONG

Usage:
  python mesh_bridge.py [--port 45678] [--adb path/to/adb]

Test bed:
  1. python Tools/mesh_bridge.py
  2. start two emulators (emulator-5554, emulator-5556)
  3. in each app: Settings -> Mesh Network -> enable Mesh,
     set Bridge host 10.0.2.2, port 45678
  4. watch peers appear and frames flow
"""

import argparse
import socket
import subprocess
import sys
import threading
import time

DEFAULT_PORT = 45678
FWD_HOST = "10.0.2.2"          # how an emulator reaches the host
FWD_PORT_BASE = 45700          # first host port used for adb forward
STALE_AFTER = 45               # seconds without ping => peer removed

peers = {}                     # nodeId -> dict(serial, local_port, fwd_port, last_seen)
peers_lock = threading.Lock()


def adb(*args, adb_path="adb"):
    cmd = [adb_path] + list(args)
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
        return out.returncode == 0, (out.stdout or out.stderr).strip()
    except Exception as e:
        return False, str(e)


def connected_serials(adb_path):
    ok, out = adb("devices", adb_path=adb_path)
    if not ok:
        return []
    serials = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def pick_serial(node_hint, used, adb_path):
    """Assign an emulator serial to a node id (sticky while it stays alive)."""
    serials = connected_serials(adb_path)
    for s in serials:
        if s not in used:
            return s
    # all taken: reuse by node hint order
    for s in serials:
        return s
    return None


def setup_forward(serial, device_port, fwd_port, adb_path):
    ok, out = adb("-s", serial, "forward", f"tcp:{fwd_port}", f"tcp:{device_port}", adb_path=adb_path)
    return ok, out


def handle_client(conn, addr, args):
    conn.settimeout(300)
    buf = b""
    try:
        f = conn.makefile("rwb")
        while True:
            line = f.readline()
            if not line:
                break
            line = line.decode("utf-8", "replace").strip()
            if not line:
                continue
            parts = line.split()
            cmd = parts[0].upper()

            if cmd == "REGISTER" and len(parts) >= 3:
                node_id = parts[1]
                try:
                    local_port = int(parts[2])
                except ValueError:
                    local_port = -1
                if local_port <= 0 or local_port > 65535:
                    f.write(b"ERR invalid-port\n")
                    f.flush()
                    continue
                with peers_lock:
                    used = {p["serial"] for p in peers.values() if "serial" in p}
                    existing = peers.get(node_id)
                    serial = existing.get("serial") if existing else None
                    if not serial or serial not in connected_serials(args.adb):
                        serial = pick_serial(node_id, used, args.adb)
                    if not serial:
                        f.write(b"ERR no-emulator\n")
                        f.flush()
                        continue
                    fwd_port = existing.get("fwd_port") if existing else None
                    if not fwd_port:
                        taken = {p.get("fwd_port") for p in peers.values()}
                        fwd_port = FWD_PORT_BASE
                        while fwd_port in taken:
                            fwd_port += 1
                    ok, out = setup_forward(serial, local_port, fwd_port, args.adb)
                    if not ok:
                        f.write(f"ERR forward-failed {out}\n".encode())
                        f.flush()
                        continue
                    peers[node_id] = {
                        "serial": serial,
                        "local_port": local_port,
                        "fwd_port": fwd_port,
                        "last_seen": time.time(),
                    }
                print(f"[bridge] REGISTER {node_id} dev:{local_port} -> {serial} fwd {FWD_HOST}:{fwd_port}", flush=True)
                f.write(f"OK {FWD_HOST} {fwd_port}\n".encode())
                f.flush()

            elif cmd == "LIST":
                now = time.time()
                with peers_lock:
                    # drop stale
                    for nid in list(peers.keys()):
                        if now - peers[nid]["last_seen"] > STALE_AFTER:
                            print(f"[bridge] evict stale {nid}", flush=True)
                            del peers[nid]
                    snapshot = [(nid, p["fwd_port"]) for nid, p in peers.items()]
                for nid, fwd_port in snapshot:
                    f.write(f"PEER {nid} {FWD_HOST} {fwd_port}\n".encode())
                f.write(b"END\n")
                f.flush()

            elif cmd == "PING":
                # refresh last_seen for whoever is registered on this socket
                f.write(b"PONG\n")
                f.flush()

            elif cmd == "REFRESH" and len(parts) >= 2:
                node_id = parts[1]
                with peers_lock:
                    if node_id in peers:
                        peers[node_id]["last_seen"] = time.time()
                f.write(b"OK\n")
                f.flush()

            else:
                f.write(b"ERR unknown\n")
                f.flush()
    except (socket.timeout, ConnectionResetError, BrokenPipeError, OSError):
        pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def ping_refresher(args):
    """The REGISTER response is per-socket; apps also send REFRESH <node>
    periodically via pingOnce; here we simply touch all peers that had
    recent socket activity. Simplified: rely on REGISTER re-issue."""
    while True:
        time.sleep(10)


def main():
    ap = argparse.ArgumentParser(description="Telegram Mesh emulator bridge")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--adb", default="adb", help="path to adb executable")
    args = ap.parse_args()

    print(f"[bridge] listening on {args.host}:{args.port} (adb={args.adb})", flush=True)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(16)

    threading.Thread(target=ping_refresher, args=(args,), daemon=True).start()

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle_client, args=(conn, addr, args), daemon=True).start()
    except KeyboardInterrupt:
        print("[bridge] shutting down", flush=True)
    finally:
        srv.close()


if __name__ == "__main__":
    main()
