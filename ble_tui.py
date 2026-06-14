#!/usr/bin/env python3
import sys
import subprocess
import json
import socket
import threading
import time
from datetime import datetime

# --- Bootstrap Dependencies ---
def bootstrap():
    required_packages = ["textual", "rich"]
    print("[*] Checking dependencies...")
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "--upgrade"] + required_packages)
    except Exception as e:
        print(f"[!] Warning: Could not update dependencies: {e}")

import os
if __name__ == "__main__" and "SKIP_BOOTSTRAP" not in os.environ:
    bootstrap()

from textual.app import App, ComposeResult
from textual.widgets import Header, Footer, Static, Input, Button, ListItem, ListView, RichLog, Label, TabbedContent, TabPane, Select
from textual.containers import Container, Horizontal, Vertical, Grid
from textual.binding import Binding
from textual.reactive import reactive
from rich.text import Text
from rich.panel import Panel

# --- Bridge Client ---
class BleBridgeClient:
    def __init__(self, host, port, on_message_callback):
        self.host = host
        self.port = port
        self.on_message = on_message_callback
        self.sock = None
        self.running = False
        self.listener_thread = None

    def connect(self):
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(5.0)
            self.sock.connect((self.host, self.port))
            self.running = True
            self.listener_thread = threading.Thread(target=self._listen_loop, daemon=True)
            self.listener_thread.start()
            return True
        except Exception as e:
            return str(e)

    def _listen_loop(self):
        buffer = ""
        while self.running:
            try:
                data = self.sock.recv(4096)
                if not data:
                    self.running = False
                    self.on_message({"status": "disconnected", "message": "Connection closed by bridge"})
                    break

                buffer += data.decode('utf-8')
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    if line.strip():
                        try:
                            msg = json.loads(line)
                            self.on_message(msg)
                        except json.JSONDecodeError:
                            pass
            except Exception:
                self.running = False
                self.on_message({"status": "disconnected", "message": "Socket error"})
                break

    def send_command(self, cmd_dict):
        if not self.sock or not self.running:
            return False
        try:
            payload = json.dumps(cmd_dict) + "\n"
            self.sock.sendall(payload.encode('utf-8'))
            return True
        except Exception:
            self.running = False
            return False

    def disconnect(self):
        self.running = False
        if self.sock:
            self.sock.close()
            self.sock = None

# --- TUI Components ---

class DeviceItem(ListItem):
    def __init__(self, name, address, rssi, device_type="Generic"):
        super().__init__()
        self.device_name = name
        self.address = address
        self.rssi = rssi
        self.device_type = device_type

    def compose(self) -> ComposeResult:
        yield Vertical(
            Horizontal(
                Label(f"[bold]{self.device_name}[/bold]"),
                Label(f" {self.rssi} dBm", classes="rssi"),
            ),
            Horizontal(
                Label(f"[dim]{self.address}[/dim]"),
                Label(f" [italic blue]{self.device_type}[/italic blue]", classes="dev-type"),
            ),
            classes="device-item-row"
        )

class BleTuiApp(App):
    CSS = """
    Screen {
        background: #121212;
    }

    #sidebar {
        width: 30;
        min-width: 20;
        max-width: 40%;
        background: #1e1e1e;
        border-right: solid #333;
    }

    #main-content {
        flex-grow: 1;
    }

    .device-item-row {
        padding: 0 1;
        height: 2;
        border-bottom: thin #333;
    }

    .rssi {
        color: #00ff00;
        text-align: right;
        width: 100%;
    }

    .dev-type {
        text-align: right;
        width: 100%;
    }

    #console {
        height: 10;
        background: #000;
        border-top: solid #333;
        color: #00ff00;
        font-family: 'Courier New', Courier, monospace;
    }

    #status-bar {
        height: 1;
        background: #0056d2;
        color: white;
    }

    TabPane {
        padding: 1;
    }

    .connected {
        color: #00ff00;
    }

    .disconnected {
        color: #ff0000;
    }

    .connecting {
        color: #ffff00;
    }
    """

    BINDINGS = [
        Binding("q", "quit", "Quit", show=True),
        Binding("s", "toggle_scan", "Start/Stop Scan", show=True),
        Binding("c", "clear_console", "Clear Console", show=True),
        Binding("h", "send_heartbeat", "Pulse", show=True),
    ]

    bridge_status = reactive("Disconnected")
    is_scanning = reactive(False)
    heartbeat_enabled = reactive(False)

    def __init__(self):
        super().__init__()
        self.client = None
        self.discovered_devices = {}
        self.heartbeat_timer = None
        self.last_connected_device = None
        self.services_data = []
        self.selected_service = None
        self.selected_char = None

    def compose(self) -> ComposeResult:
        yield Header()
        yield Horizontal(
            Vertical(
                Label("[bold]BRIDGE STATUS[/bold]"),
                Label("Disconnected", id="bridge-status-label", classes="disconnected"),
                Horizontal(
                    Input(placeholder="IP (127.0.0.1)", id="bridge-ip", value="127.0.0.1"),
                    Input(placeholder="Port (8080)", id="bridge-port", value="8080"),
                ),
                Button("Connect", id="connect-btn", variant="primary"),
                Label("\n[bold]SCANNER[/bold]"),
                ListView(id="device-list"),
                Button("Start Scan", id="scan-btn"),
                id="sidebar"
            ),
            Vertical(
                TabbedContent(
                    TabPane("Explorer", id="tab-explorer"),
                    TabPane("Console", id="tab-console"),
                    TabPane("Fuzzer", id="tab-fuzzer"),
                    TabPane("Settings", id="tab-settings"),
                    id="tabs"
                ),
                RichLog(id="console", highlight=True, markup=True),
                id="main-content"
            )
        )
        yield Label("Ready", id="status-bar")
        yield Footer()

    def on_mount(self) -> None:
        self.log_to_console("[bold blue]BLE Bridge TUI Started[/bold blue]")
        self.query_one("#tab-explorer").mount(
            Vertical(
                Label("Connect to a device to explore services.", id="explorer-placeholder"),
                ListView(id="service-list"),
                id="explorer-container"
            )
        )

        # Fuzzer tab content
        self.query_one("#tab-fuzzer").mount(
            Vertical(
                Label("[bold]BUILT-IN COMMANDS[/bold]"),
                Horizontal(
                    Button("AT", id="btn-at"),
                    Button("AT+VERSION", id="btn-version"),
                    Button("HELP", id="btn-help"),
                    Button("HELLO", id="btn-hello"),
                    classes="fuzzer-buttons"
                ),
                Label("\n[bold]CUSTOM COMMAND[/bold]"),
                Horizontal(
                    Select([("None", ""), ("\\r\\n", "\r\n"), ("\\n", "\n"), ("\\r", "\r")], value="", id="line-ending", prompt="Endings"),
                    Input(placeholder="Payload (HEX or Text)", id="fuzz-input"),
                ),
                Horizontal(
                    Button("Send Text", id="send-text-btn"),
                    Button("Send Hex", id="send-hex-btn"),
                )
            )
        )

        # Settings tab content
        self.query_one("#tab-settings").mount(
            Vertical(
                Label("[bold]PREFERENCES[/bold]"),
                Horizontal(
                    Label("Heartbeat Pulse (10s): "),
                    Button("Disabled", id="toggle-heartbeat"),
                ),
                Horizontal(
                    Label("Auto MTU (on connect): "),
                    Button("Enabled", id="toggle-auto-mtu"),
                ),
            )
        )

    def log_to_console(self, message):
        timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        self.query_one("#console").write(Text.from_markup(f"[{timestamp}] {message}"))

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "connect-btn":
            self.handle_bridge_connect()
        elif event.button.id == "scan-btn":
            self.action_toggle_scan()
        elif event.button.id == "btn-at":
            self.send_fuzz("AT")
        elif event.button.id == "btn-version":
            self.send_fuzz("AT+VERSION")
        elif event.button.id == "btn-help":
            self.send_fuzz("HELP")
        elif event.button.id == "btn-hello":
            self.send_fuzz("HELLO")
        elif event.button.id == "send-text-btn":
            self.send_custom(False)
        elif event.button.id == "send-hex-btn":
            self.send_custom(True)
        elif event.button.id == "toggle-heartbeat":
            self.toggle_heartbeat()

    def handle_bridge_connect(self):
        if self.client and self.client.running:
            self.client.disconnect()
            self.bridge_status = "Disconnected"
            self.query_one("#connect-btn").label = "Connect"
            return

        ip = self.query_one("#bridge-ip").value or "127.0.0.1"
        port_str = self.query_one("#bridge-port").value or "8080"
        try:
            port = int(port_str)
        except ValueError:
            self.log_to_console("[red]Invalid port[/red]")
            return

        self.client = BleBridgeClient(ip, port, self.handle_message)
        res = self.client.connect()
        if res is True:
            self.bridge_status = "Connected"
            self.query_one("#connect-btn").label = "Disconnect"
            self.log_to_console(f"[green]Connected to bridge at {ip}:{port}[/green]")
        else:
            self.log_to_console(f"[red]Connection failed: {res}[/red]")

    def watch_bridge_status(self, status: str):
        label = self.query_one("#bridge-status-label")
        label.update(status)
        label.set_class(status.lower() == "connected", "connected")
        label.set_class(status.lower() == "disconnected", "disconnected")

    def handle_message(self, msg):
        status = msg.get("status")
        if status == "scan_result":
            addr = msg.get("device_address")
            name = msg.get("device_name", "Unknown")
            rssi = msg.get("rssi", 0)
            dtype = msg.get("device_type", "Generic")
            if addr not in self.discovered_devices:
                self.discovered_devices[addr] = name
                self.call_from_thread(self.add_device_to_list, name, addr, rssi, dtype)
        elif status == "connected":
            dev = msg.get("device")
            self.last_connected_device = dev
            self.log_to_console(f"[green]BLE Connected: {dev}[/green]")
            if self.query_one("#toggle-auto-mtu").label == "Enabled":
                self.client.send_command({"command": "request_mtu", "mtu": 512})
            self.client.send_command({"command": "discover"})
        elif status == "services_discovered":
            self.services_data = msg.get("services", [])
            self.call_from_thread(self.update_explorer)
        elif status == "read_success":
            val_hex = msg.get("hex_payload")
            val_ascii = msg.get("ascii_payload")
            char = msg.get("characteristic")
            self.log_to_console(f"[yellow]READ {char}:[/yellow] HEX={val_hex} ASCII='{val_ascii}'")
        elif status == "notification_received":
            val_hex = msg.get("hex_payload")
            char = msg.get("characteristic")
            self.log_to_console(f"[cyan]NOTIFY {char}:[/cyan] {val_hex}")
        elif status == "error":
            self.log_to_console(f"[red]BRIDGE ERROR: {msg.get('message')}[/red]")
        elif status == "disconnected":
            self.bridge_status = "Disconnected"
            self.log_to_console(f"[red]Bridge disconnected: {msg.get('message')}[/red]")

    def add_device_to_list(self, name, addr, rssi, dtype):
        lst = self.query_one("#device-list")
        lst.append(DeviceItem(name, addr, rssi, dtype))

    def update_explorer(self):
        try:
            placeholder = self.query_one("#explorer-placeholder")
            placeholder.display = False
        except: pass

        lst = self.query_one("#service-list")
        lst.clear()
        for s in self.services_data:
            s_uuid = s.get("uuid")
            header = ListItem(Label(f"[bold blue]Service: {s_uuid}[/bold blue]"))
            header.disabled = True
            lst.append(header)
            for c in s.get("characteristics", []):
                c_uuid = c.get("uuid")
                props = c.get("properties")
                item = ListItem(Label(f"  [cyan]Char: {c_uuid}[/cyan] ({props})"))
                item.service_uuid = s_uuid
                item.char_uuid = c_uuid
                lst.append(item)

    def action_toggle_scan(self):
        if not self.client or not self.client.running:
            self.log_to_console("[red]Connect to bridge first[/red]")
            return

        if not self.is_scanning:
            self.discovered_devices = {}
            self.query_one("#device-list").clear()
            if self.client.send_command({"command": "scan"}):
                self.is_scanning = True
                self.query_one("#scan-btn").label = "Stop Scan"
                self.log_to_console("[blue]Scan started...[/blue]")
        else:
            # Note: Bridge stops automatically after 10s, but we can't explicitly stop it via current JSON API
            # I added a reset_bluetooth which might be overkill.
            self.is_scanning = False
            self.query_one("#scan-btn").label = "Start Scan"
            self.log_to_console("[blue]Scan stopped (local view)[/blue]")

    def on_list_view_selected(self, event: ListView.Selected):
        if event.list_view.id == "device-list":
            item = event.item
            if isinstance(item, DeviceItem):
                self.log_to_console(f"[yellow]Connecting to {item.address}...[/yellow]")
                self.client.send_command({"command": "connect", "address": item.address})
        elif event.list_view.id == "service-list":
            item = event.item
            if hasattr(item, "char_uuid"):
                self.selected_service = item.service_uuid
                self.selected_char = item.char_uuid
                self.log_to_console(f"[bold green]Selected Char for I/O:[/bold green] {self.selected_char}")
                # Auto-read on selection if possible
                self.client.send_command({
                    "command": "read",
                    "service": self.selected_service,
                    "characteristic": self.selected_char
                })

    def send_fuzz(self, text):
        if not self.client or not self.client.running: return
        if not self.selected_char:
            self.log_to_console("[red]Error: Select a characteristic in Explorer first.[/red]")
            return

        ending = self.query_one("#line-ending").value.replace("\\r", "\r").replace("\\n", "\n") or ""
        full_text = text + ending
        hex_payload = "".join("{:02x}".format(ord(c)) for c in full_text)

        self.log_to_console(f"[bold white]Writing Text:[/bold white] {text!r}")
        self.client.send_command({
            "command": "write",
            "service": self.selected_service,
            "characteristic": self.selected_char,
            "payload_hex": hex_payload
        })

    def send_custom(self, is_hex):
        if not self.client or not self.client.running: return
        if not self.selected_char:
            self.log_to_console("[red]Error: Select a characteristic in Explorer first.[/red]")
            return

        val = self.query_one("#fuzz-input").value
        if not val: return

        if is_hex:
            hex_payload = val.replace(" ", "").replace(":", "")
            self.log_to_console(f"[bold white]Writing HEX:[/bold white] {hex_payload}")
        else:
            ending = self.query_one("#line-ending").value.replace("\\r", "\r").replace("\\n", "\n") or ""
            full_text = val + ending
            hex_payload = "".join("{:02x}".format(ord(c)) for c in full_text)
            self.log_to_console(f"[bold white]Writing Text:[/bold white] {val!r}")

        self.client.send_command({
            "command": "write",
            "service": self.selected_service,
            "characteristic": self.selected_char,
            "payload_hex": hex_payload
        })

    def toggle_heartbeat(self):
        self.heartbeat_enabled = not self.heartbeat_enabled
        btn = self.query_one("#toggle-heartbeat")
        btn.label = "Enabled" if self.heartbeat_enabled else "Disabled"
        if self.heartbeat_enabled:
            self.start_heartbeat()
        else:
            if self.heartbeat_timer:
                self.heartbeat_timer.cancel()

    def start_heartbeat(self):
        if not self.heartbeat_enabled: return
        if self.client and self.client.running:
            self.client.send_command({"command": "ping"})
        self.heartbeat_timer = threading.Timer(10.0, self.start_heartbeat)
        self.heartbeat_timer.daemon = True
        self.heartbeat_timer.start()

    def action_send_heartbeat(self):
        if self.client and self.client.running:
            self.client.send_command({"command": "ping"})
            self.log_to_console("[dim]Pulse sent...[/dim]")

    def action_clear_console(self):
        self.query_one("#console").clear()

if __name__ == "__main__":
    app = BleTuiApp()
    app.run()
