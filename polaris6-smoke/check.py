import subprocess,time,re
from pathlib import Path
OUT=Path('smoke');OUT.mkdir(exist_ok=True)
PKG='app.hush.music.polarisradio.debug'
def adb(*args,timeout=40):return subprocess.run(['adb',*args],check=True,text=True,capture_output=True,timeout=timeout).stdout
adb('install','-r','delivery/Polaris_6.0_100600.apk',timeout=90)
adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
adb('logcat','-c')
adb('shell','am','start','-n',PKG+'/com.dd3boh.outertune.MainActivity')
time.sleep(8)
subprocess.run(['adb','exec-out','screencap','-p'],check=True,stdout=(OUT/'phone-launch.png').open('wb'),timeout=30)
assert adb('shell','pidof',PKG).strip(),'App process not running'
log=adb('logcat','-d',timeout=30);(OUT/'launch-logcat.txt').write_text(log)
assert not re.search(r'FATAL EXCEPTION|ANR in '+re.escape(PKG),log),'Startup crash or ANR'
# Bound UI automation separately. A failure to dump an idle tree is not a playback test failure.
try:
 adb('shell','uiautomator','dump','/sdcard/window.xml',timeout=25)
 adb('pull','/sdcard/window.xml',str(OUT/'phone.xml'))
except Exception as e:(OUT/'uiautomator-note.txt').write_text(type(e).__name__)
adb('install','-r','delivery/Polaris_6.0_tests.apk',timeout=90)
result=adb('shell','am','instrument','-w',PKG+'.test/androidx.test.runner.AndroidJUnitRunner',timeout=90)
(OUT/'session-test.txt').write_text(result)
assert 'OK (1 test)' in result,result
(OUT/'logcat.txt').write_text(adb('logcat','-d',timeout=30))
(OUT/'package.txt').write_text(adb('shell','dumpsys','package',PKG))
print('PASS: final release installed and launched, no startup fatal, actual browse/voice/report test passed')
