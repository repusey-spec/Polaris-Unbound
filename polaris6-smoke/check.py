import subprocess,time,re
from pathlib import Path
OUT=Path('smoke');OUT.mkdir(exist_ok=True)
PKG='app.hush.music.polarisradio.debug'
def adb(*args,timeout=40):return subprocess.run(['adb',*args],check=True,text=True,capture_output=True,timeout=timeout).stdout
def collect():
 for name,args in [
  ('logcat.txt',('logcat','-d','-v','threadtime')),
  ('package.txt',('shell','dumpsys','package',PKG)),
  ('activities.txt',('shell','dumpsys','activity','activities')),
  ('services.txt',('shell','dumpsys','activity','services',PKG)),
  ('lastanr.txt',('shell','dumpsys','activity','lastanr')),
  ('processes.txt',('shell','ps','-A'))]:
  try:(OUT/name).write_text(adb(*args,timeout=20))
  except Exception as e:(OUT/(name+'.error')).write_text(str(e))
adb('install','-r','delivery/Polaris_6.0_100600.apk',timeout=90)
adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
adb('logcat','-c')
adb('shell','am','start','-n',PKG+'/com.dd3boh.outertune.MainActivity')
time.sleep(8)
subprocess.run(['adb','exec-out','screencap','-p'],check=True,stdout=(OUT/'phone-launch.png').open('wb'),timeout=30)
assert adb('shell','pidof',PKG).strip(),'App process not running'
log=adb('logcat','-d',timeout=30);(OUT/'launch-logcat.txt').write_text(log)
assert not re.search(r'FATAL EXCEPTION|ANR in '+re.escape(PKG),log),'Startup crash or ANR'
# Run service instrumentation from a stopped activity, independently of onboarding animations.
adb('shell','am','force-stop',PKG)
adb('install','-r','delivery/Polaris_6.0_tests.apk',timeout=90)
try:
 with (OUT/'session-test.txt').open('w') as stream:
  proc=subprocess.Popen(['adb','shell','am','instrument','-w','-r',PKG+'.test/androidx.test.runner.AndroidJUnitRunner'],text=True,stdout=stream,stderr=subprocess.STDOUT)
  try:proc.wait(timeout=90)
  except subprocess.TimeoutExpired:
   proc.kill();proc.wait()
   (OUT/'session-timeout.txt').write_text('Instrumentation exceeded 90 seconds')
   try:
    adb('root');adb('wait-for-device')
    pid=adb('shell','pidof',PKG).strip().split()[0]
    adb('shell','kill','-3',pid);time.sleep(2)
   except Exception as e:(OUT/'thread-dump-error.txt').write_text(str(e))
  finally:collect()
 result=(OUT/'session-test.txt').read_text();print(result,flush=True)
 assert 'OK (1 test)' in result,result
 print('PASS: final release installed and launched, no startup fatal, browse/voice/report test passed',flush=True)
except BaseException:
 collect()
 raise
