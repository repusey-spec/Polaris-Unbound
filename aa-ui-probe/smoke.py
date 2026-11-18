#!/usr/bin/env python3
"""Android 13 launch, in-phone real MediaBrowserService tree check, no playback."""
import re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
out=Path("smoke");out.mkdir(exist_ok=True)
pkg="dev.polaris.aauiprobe"
def adb(*args,timeout=55):
    return subprocess.run(["adb",*args],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,check=True).stdout
apk="delivery/Polaris_AA_UI_Probe_v02.apk"
print(adb("install","-r",apk,timeout=100))
adb("logcat","-c")
print(adb("shell","am","start","-n",pkg+"/.MainActivity"))
time.sleep(3)
assert adb("shell","pidof",pkg).strip(),"Phone activity did not start"
(out/"phone-launch.png").write_bytes(subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=20,check=True).stdout)

def locate_button():
    adb("shell","uiautomator","dump","/sdcard/polaris-ui.xml")
    xml=adb("exec-out","cat","/sdcard/polaris-ui.xml")
    (out/"ui-dump.xml").write_text(xml)
    for n in ET.fromstring(xml).iter("node"):
        if "탭 순서 및 하위 목록 구조 확인" in n.attrib.get("text",""):
            match=re.findall(r"\[(\d+),(\d+)\]",n.attrib.get("bounds",""))
            if len(match)==2:
                (x,y),(r,b)=((int(q),int(w)) for q,w in match)
                return ((x+r)//2,(y+b)//2)
    return None

pos=None
for i in range(5):
    try:pos=locate_button()
    except Exception as e:print("uiautomator retry:",e)
    if pos:break
    adb("shell","input","swipe","540","1590","540","420","300")
    time.sleep(.8)
assert pos,"Phone diagnostic button wasn't found on scrollable view"
adb("shell","input","tap",str(pos[0]),str(pos[1]))
time.sleep(4)
log=adb("logcat","-d","-s","PolarisUIProbe:I","*:S")
(out/"media-browser-selftest-log.txt").write_text(log)
assert "SELFTEST PASS" in log,("Real MediaBrowser tree failed",log)
full=adb("logcat","-d")
(out/"full-launch-logcat.txt").write_text(full)
assert "FATAL EXCEPTION" not in full,"Startup/UI crash in logcat"
(out/"phone-selftest.png").write_bytes(subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=20,check=True).stdout)
print("PASS: phone UI launch, real MediaBrowserService root 4 tabs, CAN empty, Radio domestic/overseas plus recents, Streaming playlists/downloads plus 10 liked sample items")
print("NOTE: emulator does NOT establish car host grid display; AA in vehicle is separate test.")
