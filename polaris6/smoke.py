import subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
OUT=Path('smoke');OUT.mkdir(exist_ok=True)
PKG='app.hush.music.polarisradio.debug'
def adb(*args):return subprocess.run(['adb',*args],check=True,text=True,capture_output=True).stdout
def snap(label):
 adb('shell','uiautomator','dump','/sdcard/window.xml')
 adb('pull','/sdcard/window.xml',str(OUT/(label+'.xml')))
 subprocess.run(['adb','exec-out','screencap','-p'],check=True,stdout=(OUT/(label+'.png')).open('wb'))
 return ET.fromstring((OUT/(label+'.xml')).read_text())
def tap(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1)
adb('install','-r','delivery/Polaris_6.0_100600.apk')
adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
adb('logcat','-c')
adb('shell','am','start','-n',PKG+'/com.dd3boh.outertune.MainActivity')
time.sleep(5)
# Navigate only observed setup/permission controls. Never enter credentials or accept installs.
for i in range(20):
 tree=snap('step-%02d'%i)
 nodes=list(tree.iter('node'));texts=[n.attrib.get('text','') for n in nodes]
 if 'Radio' in texts and 'Streaming' in texts:break
 candidates=[n for n in nodes if n.attrib.get('enabled')=='true' and n.attrib.get('text','').strip().lower() in ['get started','start','next','skip','not now','no','done','finish','continue','allow','while using the app','ok']]
 if not candidates:break
 tap(candidates[-1])
tree=snap('home')
texts=[n.attrib.get('text','') for n in tree.iter('node')]
if 'Streaming' in texts:
 tap(next(n for n in tree.iter('node') if n.attrib.get('text')=='Streaming'))
 tree=snap('streaming')
 if any(n.attrib.get('text')=='Radio' for n in tree.iter('node')):
  tap(next(n for n in tree.iter('node') if n.attrib.get('text')=='Radio'))
  tree=snap('radio')
  report=next((n for n in tree.iter('node') if 'TXT' in n.attrib.get('text','')),None)
  if report is not None:tap(report);snap('report')
log=adb('logcat','-d');(OUT/'logcat.txt').write_text(log)
(OUT/'package.txt').write_text(adb('shell','dumpsys','package',PKG))
assert not re.search(r'FATAL EXCEPTION|ANR in '+re.escape(PKG),log), 'App crashed or ANR during smoke test'
assert 'versionCode=100600' in (OUT/'package.txt').read_text()
print('PASS: install identity, startup, observed setup controls and no fatal exception')
