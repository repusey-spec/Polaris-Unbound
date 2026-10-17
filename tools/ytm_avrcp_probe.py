#!/usr/bin/env python3
"""Read-only Bluetooth AVRCP browser test for YouTube Music on a paired phone.

Requires Debian/BlueZ and python3-dbus, plus a *real* Classic Bluetooth
A2DP sink + AVRCP controller connection. Does not access Google credentials.
No audio is played and no tracks are selected by this diagnostic.

Examples:
  python3 tools/ytm_avrcp_probe.py
  python3 tools/ytm_avrcp_probe.py --device 01:23:45:67:89:AB --json
"""
import argparse
import json
import re
import sys

BLUEZ = "org.bluez"
PLAYER = "org.bluez.MediaPlayer1"
FOLDER = "org.bluez.MediaFolder1"
DEVICE = "org.bluez.Device1"
MAX_ITEMS = 20


def plain(value):
    """Reduce D-Bus variants to printable JSON-compatible data."""
    if isinstance(value, dict):
        return {str(k): plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [plain(v) for v in value]
    if isinstance(value, (bool, int, float, str)):
        return value
    return str(value)


def record_for_player(path, ifaces, objects, address):
    props = ifaces[PLAYER]
    device_path = str(props.get("Device", path.split("/player")[0]))
    dev = objects.get(device_path, {}).get(DEVICE, {})
    device_address = str(dev.get("Address", "")).upper()
    if address and device_address != address:
        return None
    return {
        "path": str(path),
        "device": device_address,
        "connected": bool(dev.get("Connected", False)),
        "player": str(props.get("Name", "(unknown)")),
        "browsable": bool(props.get("Browsable", False)),
        "searchable": bool(props.get("Searchable", False)),
        "status": str(props.get("Status", "(unknown)")),
        "has_media_folder_interface": FOLDER in ifaces,
    }


def gather(bus, address):
    import dbus
    managed = dbus.Interface(bus.get_object(BLUEZ, "/"),
                             "org.freedesktop.DBus.ObjectManager")
    objects = managed.GetManagedObjects()
    results = []
    for path, ifaces in objects.items():
        if PLAYER not in ifaces:
            continue
        player = record_for_player(path, ifaces, objects, address)
        if player is None:
            continue
        if FOLDER in ifaces and player["connected"]:
            try:
                folder = dbus.Interface(bus.get_object(BLUEZ, path), FOLDER)
                response = folder.ListItems(
                    dbus.Dictionary(
                        {"Start": dbus.UInt32(0), "End": dbus.UInt32(MAX_ITEMS - 1)},
                        signature="sv"),
                    timeout=15,
                )
                parsed = []
                for item_path, props in response:
                    props = plain(props)
                    parsed.append({
                        "path": str(item_path),
                        "name": str(props.get("Name", props.get("Title", "(unnamed)"))),
                        "type": str(props.get("Type", "(unknown)")),
                        "playable": bool(props.get("Playable", False)),
                    })
                player["items"] = parsed
                player["list_items"] = "success"
            except dbus.DBusException as ex:
                player["list_items"] = "error"
                player["error_name"] = str(ex.get_dbus_name())
                player["error"] = str(ex)
        elif FOLDER not in ifaces:
            player["list_items"] = "not_available"
        else:
            player["list_items"] = "remote_disconnected"
        results.append(player)
    return results


def main():
    parser = argparse.ArgumentParser(
        description="Probe BT AVRCP browsing from a genuine Linux Bluetooth controller.")
    parser.add_argument("--device", help="paired Android phone Bluetooth address")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = parser.parse_args()
    address = args.device.upper() if args.device else None
    if address and not re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", address):
        parser.error("--device must be a six-byte Bluetooth MAC address")
    try:
        import dbus
    except ImportError:
        print("Missing module: install python3-dbus (and run with /usr/bin/python3).",
              file=sys.stderr)
        return 2
    try:
        results = gather(dbus.SystemBus(), address)
    except dbus.DBusException as ex:
        print(f"BlueZ DBus unavailable: {ex}", file=sys.stderr)
        return 3

    if args.json:
        print(json.dumps({"players": results}, indent=2, ensure_ascii=False))
    else:
        if not results:
            print("NO REMOTE AVRCP PLAYERS: first pair/connect Android phone to "
                  "Linux Bluetooth AUDIO receiver, then retry.")
        for p in results:
            print(f"\nPLAYER {p['player']} — {p['device']} "
                  f"({p['status']}, connected={p['connected']})")
            print(f"Browsable={p['browsable']} Searchable={p['searchable']} "
                  f"MediaFolder={p['has_media_folder_interface']}")
            print(f"ListItems={p['list_items']}")
            if p.get("error"):
                print(f"Reason: {p['error_name']}: {p['error']}")
            for item in p.get("items", []):
                print(f"  [{item['type']}] {item['name']} "
                      f"playable={item['playable']} ({item['path']})")
    print("\nNOTE: The Linux host must act as AVRCP Controller. "
          "A normal Polaris Android phone app cannot access this privileged role.",
          file=sys.stderr)
    return 0 if any(p.get("list_items") == "success" for p in results) else 1


if __name__ == "__main__":
    sys.exit(main())
