# BLE-to-Network Bridge 🚀

Welcome to the **BLE-to-Network Bridge**! This highly resilient and configurable Android application allows a non-rooted Android device to act as a bi-directional gateway. It bridges local/remote TCP network socket connections straight into physical Bluetooth Low Energy (BLE) peripherals.

Designed for embedded architects, IoT engineers, and local workspace development, you can control BLE devices using simple JSON payloads over raw TCP sockets via Termux (using `netcat`), Python scripts, or workstations on the same local Wi-Fi.

---

## 🎨 Layout & Architecture Highlight

- **Connection Manager Card**: Real-time display of your local subnet Wi-Fi IP address, port specification field (defaults to `8080`), and an absolute Service ON/OFF Toggle.
- **Client Session Supervisor**: Scrollable list of active network clients. Includes an immediate **BOOT** button to programmatically terminate sessions or free occupied descriptors.
- **Thread-safe Scrollable Console Logging**: Rolling 200-message console mapping errors, hardware status, and packet telemetry.
- **Dynamic Granularity Levels**: Toggled via a custom dropdown selection:
  - `LEVEL 1: Errors Only` — High contrast filtering for exception detection.
  - `LEVEL 2: Connections & General` — Standard visibility mapping client cycles.
  - `LEVEL 3: Full Telemetry` — Raw hex payloads, ASCII translations, packet lengths, and device parameters.

---

## ⚙️ Core Resiliency Implementations

- **Sequential Task Queue**: BLE operations are processed through a thread-safe, non-blocking coroutine queue, avoiding hardware race conditions and concurrency limits.
- **Automated 3-Second Timeout**: Prevents device queue deadlocks on dropped transactions.
- **Automatic MTU Expansion**: Instructs the connected peripheral to increase its payload size up to `512` bytes on successful handshakes.
- **Error 133 Cache Purge**: Automatically sweeps, disconnects, and closes GATT references whenever error states occur, protecting against cache saturation.

---

## 🔒 Permissions Map

The application declares and handles the modern dynamic Android permission group:
- `android.permission.BLUETOOTH_SCAN`: Discovers near peripherals.
- `android.permission.BLUETOOTH_CONNECT`: Handshakes GATT channels.
- `android.permission.INTERNET`: Listens on the designated wildcard interface (`0.0.0.0`).
- `android.permission.ACCESS_WIFI_STATE`: Resolves current Wi-Fi sub-routes.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_CONNECTED_DEVICE`: Keeps connection servers alive while in background/sleep mode.

---

## 🛠️ Step-by-Step Compilation & Deployment

1. **Clone & Open**: Load the codebase in a modern version of Android Studio.
2. **Project Synchronization**: Wait for Gradle to build the project. The project is fully configured using Kotlin DSL, Kotlin 2.2+, and Jetpack Compose 1.7+.
3. **Compile and Run**: Run the app on your physical device or emulator. Ensure Bluetooth is turned **ON** on your testing device.
4. **Grant Permissions**: Upon initial startup, accept the runtime permission invitations.
5. **Set listening Port & Start**: Enter the target port (e.g., `8080`) and toggle the server **ON** to start your background gateway!

---

## 💻 Working Example: Control via Netcat (Termux)

Start editing, reading, or auditing sensors directly from the command line on your device!

### 1. Start a BLE Scanner
Query surrounding devices to retrieve target MAC addresses:
```bash
# Connect to your Android gateway from Termux loopback
nc 127.0.0.1 8080
```
*Send the Scan command:*
```json
{"command": "scan"}
```
*Incoming streaming results:*
```json
{"status":"scan_result","timestamp":1781426173812,"device_name":"HeartPulse","device_address":"AA:BB:CC:DD:EE:FF","rssi":-58}
```

### 2. Connect to a Peripheral
```json
{"command": "connect", "address": "AA:BB:CC:DD:EE:FF"}
```
*Successful routing returns:*
```json
{"status":"connected","timestamp":1781426180120,"device":"AA:BB:CC:DD:EE:FF","service":"N/A","characteristic":"N/A","hex_payload":"","ascii_payload":""}
```

### 3. Read raw Battery Level
```json
{
  "command": "read",
  "service": "0000180F-0000-1000-8000-00805f9b34fb",
  "characteristic": "00002A19-0000-1000-8000-00805f9b34fb"
}
```
*Telemetry Response:*
```json
{"status":"read_success","timestamp":1781426190458,"device":"AA:BB:CC:DD:EE:FF","service":"0000180f-0000-1000-8000-00805f9b34fb","characteristic":"00002a19-0000-1000-8000-00805f9b34fb","hex_payload":"64","ascii_payload":"d"}
```
*(Battery is `0x64` = 100%)*

### 4. Enable Notifications with Streaming Stream
Start listening to sensor changes dynamically:
```json
{
  "command": "notify",
  "service": "0000180d-0000-1000-8000-00805f9b34fb",
  "characteristic": "00002a37-0000-1000-8000-00805f9b34fb",
  "enable": true
}
```

---

## 🐍 Python Automation Template

This robust, modular Python template connects to your gateway socket, runs a continuous reading cycle on a background thread, executes safe writes, and parses responses dynamically.

Save this script as `ble_bridge.py`:

```python
import socket
import json
import threading
import time
import sys

# Change these to match your Connection Card dashboard configurations
BRIDGE_IP = "192.168.1.100"  # Or "127.0.0.1" for Termux loopback
BRIDGE_PORT = 8080

class BleBridgeClient:
    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.sock = None
        self.running = False
        self.listener_thread = None

    def connect(self):
        """Initializes TCP socket connection to the Android bridge."""
        try:
            print(f"[+] Connecting to BLE Bridge at {self.host}:{self.port}...")
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.connect((self.host, self.port))
            self.running = True
            
            # Launch listener background thread
            self.listener_thread = threading.Thread(target=self._listen_loop, daemon=True)
            self.listener_thread.start()
            print("[+] Connection established and listener thread active.")
            return True
        except Exception as e:
            print(f"[-] Connection failed: {e}")
            return False

    def send_command(self, cmd_dict):
        """Encodes and sends a single-line command dictionary to the bridge."""
        if not self.sock or not self.running:
            print("[-] Cannot send command: Not connected.")
            return False
        try:
            payload = json.dumps(cmd_dict) + "\n"
            self.sock.sendall(payload.encode('utf-8'))
            print(f"[TX] {payload.strip()}")
            return True
        except Exception as e:
            print(f"[-] Send error: {e}")
            self.disconnect()
            return False

    def _listen_loop(self):
        """Continuous background buffer processing loops Parsing JSON items."""
        buffer = ""
        while self.running:
            try:
                data = self.sock.recv(4096)
                if not data:
                    print("[-] Closed by remote host socket connection.")
                    self.running = False
                    break
                
                buffer += data.decode('utf-8')
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    if line.strip():
                        self._handle_response(line)
            except Exception as e:
                print(f"[-] Rx socket processing interrupt: {e}")
                self.running = False
                break

    def _handle_response(self, raw_line):
        """Parses output string and displays standard telemetry."""
        try:
            payload = json.loads(raw_line)
            status = payload.get("status", "N/A")
            timestamp = payload.get("timestamp", 0)
            
            print(f"\n[RX @ {timestamp}] [Status: {status.upper()}]")
            
            if status == "scan_result":
                print(f"   * Found Device: {payload.get('device_name')} [{payload.get('device_address')}] (RSSI: {payload.get('rssi')}dBm)")
            elif status in ["read_success", "notification_received"]:
                print(f"   * Device: {payload.get('device')}")
                print(f"   * Char  : {payload.get('characteristic')}")
                print(f"   * Hex   : {payload.get('hex_payload')}")
                print(f"   * ASCII : {payload.get('ascii_payload')}")
            elif status == "error":
                print(f"   * WARNING Error: {payload.get('message')}")
            else:
                print(f"   * Raw payload response: {payload}")
                
        except json.JSONDecodeError:
            print(f"[RX Raw String]: {raw_line.strip()}")

    def disconnect(self):
        """Ensures socket files are closed cleanly."""
        print("[*] Disconnecting client...")
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        print("[+] Slept.")

# --- Interactive Test Run Lifecycle ---
def main():
    client = BleBridgeClient(BRIDGE_IP, BRIDGE_PORT)
    if not client.connect():
        sys.exit(1)

    try:
        # Example automation sequence:
        # 1. Ask for scan
        client.send_command({"command": "scan"})
        time.sleep(5) # Let scan run for 5 seconds

        # 2. To test connection to a target peripheral, uncomment below:
        # TARGET_MAC = "AA:BB:CC:DD:EE:FF"
        # client.send_command({"command": "connect", "address": TARGET_MAC})
        # time.sleep(3)
        #
        # # 3. Read custom Battery level characteristic
        # client.send_command({
        #     "command": "read",
        #     "service": "0000180f-0000-1000-8000-00805f9b34fb",
        #     "characteristic": "00002a19-0000-1000-8000-00805f9b34fb"
        # })
        # time.sleep(3)

        # Keep client running to stream live console logs and GATT notifications
        print("\n[+] Listening to incoming data stream. Use Ctrl+C to terminate.")
        while client.running:
            time.sleep(1)

    except KeyboardInterrupt:
        print("\n[-] Automation interrupted by keyboard request.")
    finally:
        client.disconnect()

if __name__ == "__main__":
    main()
```
