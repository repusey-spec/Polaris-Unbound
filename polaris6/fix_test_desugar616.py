import hashlib,json,os,subprocess,sys,urllib.request,zipfile
from pathlib import Path

delivery=Path('delivery');work=Path(os.environ['RUNNER_TEMP'])/'polaris615-test-runtime';work.mkdir(exist_ok=True)
app=delivery/'Polaris_6.16_100616.apk'
expected=(delivery/'SHA256.txt').read_text().split()[0]
assert hashlib.sha256(app.read_bytes()).hexdigest()==expected
inputs=[]
for artifact in ['desugar_jdk_libs','desugar_jdk_libs_configuration']:
    dest=work/(artifact+'.jar')
    url=f'https://dl.google.com/dl/android/maven2/com/android/tools/{artifact}/2.1.5/{artifact}-2.1.5.jar'
    dest.write_bytes(urllib.request.urlopen(url,timeout=40).read())
    with zipfile.ZipFile(dest) as z:
        if artifact.endswith('configuration'):
            (work/'desugar.json').write_bytes(z.read('META-INF/desugar/d8/desugar.json'))
        if any(n.endswith('.class') for n in z.namelist()):inputs.append(str(dest))
sdk=Path(os.environ['ANDROID_HOME']);tools=sdk/'build-tools/36.0.0'
runtime=work/'runtime';runtime.mkdir(exist_ok=True)
command=['java','-Xmx2g','-cp',str(tools/'lib/d8.jar'),'com.android.tools.r8.L8','--release','--min-api','24','--lib',str(sdk/'platforms/android-36/android.jar'),'--desugared-lib',str(work/'desugar.json'),'--output',str(runtime),*inputs]
subprocess.run(command,check=True)
dexes=sorted(runtime.glob('classes*.dex'));assert dexes,'L8 produced no runtime DEX'
# Supply ONLY the test-only Kotlin MapsKt.toMap(Map) facade missing from
# R8-minified production. Do NOT embed the whole Kotlin stdlib: its
# CollectionsKt class shadows obfuscated production helpers and crashes.
shim_dir=work/'shim-src'/'kotlin'/'collections'
shim_dir.mkdir(parents=True,exist_ok=True)
shim=shim_dir/'MapsKt.java'
shim.write_text("""package kotlin.collections;
public final class MapsKt {
  private MapsKt() {}
  public static <K,V> java.util.Map<K,V> toMap(java.util.Map<K,V> input) {
    return new java.util.HashMap<K,V>(input);
  }
}
""")
shim_classes=work/'shim-classes';shim_classes.mkdir(exist_ok=True)
subprocess.run(['javac','--release','8','-d',str(shim_classes),str(shim)],check=True)
shim_dex_dir=work/'shim-dex';shim_dex_dir.mkdir(exist_ok=True)
subprocess.run(['java','-Xmx2g','-cp',str(tools/'lib/d8.jar'),
    'com.android.tools.r8.D8','--release','--min-api','24',
    '--lib',str(sdk/'platforms/android-36/android.jar'),
    '--output',str(shim_dex_dir),
    str(shim_classes/'kotlin/collections/MapsKt.class')],check=True)
shim_dexes=sorted(shim_dex_dir.glob('classes*.dex'))
assert shim_dexes,'D8 produced no tiny Kotlin test shim DEX'
dexes+=shim_dexes
test=delivery/'Polaris_6.16_tests.apk';unsigned=work/'tests-unsigned.apk'
with zipfile.ZipFile(test) as old,zipfile.ZipFile(unsigned,'w') as new:
    for info in old.infolist():
        name=info.filename
        if name=='classes2.dex':continue
        if name.startswith('META-INF/') and (name.endswith(('.RSA','.DSA','.EC','.SF')) or name=='META-INF/MANIFEST.MF'):continue
        new.writestr(info,old.read(name))
    for index,dex in enumerate(dexes,2):new.write(dex,f'classes{index}.dex')
aligned=work/'tests-aligned.apk'
subprocess.run([str(tools/'zipalign'),'-f','4',str(unsigned),str(aligned)],check=True)
subprocess.run([str(tools/'apksigner'),'sign','--ks',str(work/'polaris.jks'),'--ks-key-alias','polarisdebug','--ks-pass','pass:android','--key-pass','pass:android','--out',str(test),str(aligned)],check=True)
subprocess.run([str(tools/'apksigner'),'verify','--print-certs',str(test)],check=True)
assert hashlib.sha256(app.read_bytes()).hexdigest()==expected,'Production APK changed'
Path('smoke').mkdir(exist_ok=True)
Path('smoke/runtime-fix.json').write_text(json.dumps({'production_apk_sha256':expected,'test_apk_sha256':hashlib.sha256(test.read_bytes()).hexdigest(),'runtime':'desugar_jdk_libs 2.1.5 + MapsKt.toMap test shim only','dex_files':len(dexes)},indent=2))
print('PASS: complete test-only time runtime; production APK unchanged')
