import socket
import json
import threading
import time

def mock_bridge():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('127.0.0.1', 8080))
    server.listen(1)
    print("Mock Bridge listening on 8080...")

    while True:
        client, addr = server.accept()
        print(f"Client connected: {addr}")

        def handle_client(c):
            while True:
                try:
                    data = c.recv(1024)
                    if not data: break
                    lines = data.decode().split('\n')
                    for line in lines:
                        if not line: continue
                        cmd = json.loads(line)
                        print(f"Received: {cmd}")

                        command = cmd.get("command")
                        if command == "scan":
                            c.sendall(json.dumps({
                                "status": "scan_result",
                                "device_name": "MockHeartRate",
                                "device_address": "AA:BB:CC:DD:EE:FF",
                                "rssi": -45
                            }).encode() + b'\n')
                        elif command == "connect":
                            c.sendall(json.dumps({
                                "status": "connected",
                                "device": cmd.get("address")
                            }).encode() + b'\n')
                        elif command == "discover":
                            c.sendall(json.dumps({
                                "status": "services_discovered",
                                "device": "AA:BB:CC:DD:EE:FF",
                                "services": [
                                    {
                                        "uuid": "0000180d-0000-1000-8000-00805f9b34fb",
                                        "characteristics": [
                                            {"uuid": "00002a37-0000-1000-8000-00805f9b34fb", "properties": "NOTIFY"}
                                        ]
                                    }
                                ]
                            }).encode() + b'\n')
                        elif command == "ping":
                            c.sendall(json.dumps({"status": "pong"}).encode() + b'\n')
                except:
                    break
            c.close()

        threading.Thread(target=handle_client, args=(client,)).start()

if __name__ == "__main__":
    mock_bridge()
