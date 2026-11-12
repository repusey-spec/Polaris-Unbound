from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]);h=Path(__file__).resolve().parent/'resources14/extractor'
b=r/'extractor/src/main/java/org/schabi/newpipe/extractor'
for f in h.glob('*.java'):
 dest=b/'services/youtube'/f.name if f.name=='YoutubeSignatureUtils.java' else b/'utils/jsextractor'/f.name
 dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
p=b/'services/youtube/YoutubeSignatureUtils.java';s=p.read_text()
s=s.replace('[;,]([A-Za-z0-9_$]', '[;,{]([A-Za-z0-9_$]')
s=s.replace('if (m.groupCount() > 1) {','if (m.groupCount() > 1 && m.group(2) != null && m.group(2).matches("\\\\d+,")) {')
p.write_text(s)
p=r/'extractor/src/test/java/org/schabi/newpipe/extractor/services/youtube/PolarisSignatureTest.java';s=p.read_text();i=s.rfind('}')
s=s[:i]+'''    @Test void nestedSignatureBodyIsExtractedToBalancedBrace() throws Exception {
        final String js = "var AB=\\\"rv;join\\\".split(\\\";\\\");var HH={rv:function(a){a.reverse()}};"
            + "dec=function(a){a=a.split(\\\"\\\");if(a.length){HH[AB[0]](a);}return a.join(\\\"\\\")};c&&(c=dec(decodeURIComponent(c)))";
        assertEquals("cba", JavaScript.run(YoutubeSignatureUtils.getDeobfuscationCode(js), "deobfuscate", "abc"));
    }
    @Test void additionalNumericArgumentAndNestedBodyArePreserved() throws Exception {
        final String js = "var AB=\\\"rv;join\\\".split(\\\";\\\");var HH={rv:function(a){a.reverse()}};"
            + "dec=function(k,a){a=a.split(\\\"\\\");if(k===42){HH[AB[0]](a);}return a.join(\\\"\\\")};c&&(c=dec(42,decodeURIComponent(c)))";
        assertEquals("cba", JavaScript.run(YoutubeSignatureUtils.getDeobfuscationCode(js), "deobfuscate", "abc"));
    }
''' +s[i:];p.write_text(s)
assert 'observeInstructionCount' in (b/'utils/JavaScript.java').read_text()
print('Backported balanced JS lexer and signature patterns from upstream 1092d2c; retained bounded Rhino')
