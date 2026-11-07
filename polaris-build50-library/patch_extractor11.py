#!/usr/bin/env python3
"""Focused 0.26.3 parser backport and cooperative Rhino budget. No latest-dev binary."""
from pathlib import Path
import sys,shutil
r=Path(sys.argv[1]);here=Path(__file__).resolve().parent
p=r/'extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeSignatureUtils.java';s=p.read_text()
assert '(var [A-z]=' in s
# Upstream f71ec634 signature-array fix, restricted to valid identifier characters.
s=s.replace('(var [A-z]=','(var [A-Za-z_$][A-Za-z0-9_$]*=')
old='if (m.groupCount() > 1) {'
assert s.count(old)==1
s=s.replace(old,'if (m.groupCount() > 1 && m.group(2) != null && m.group(2).matches("\\\\d+,")) {')
p.write_text(s)
p=r/'extractor/src/main/java/org/schabi/newpipe/extractor/utils/JavaScript.java';s=p.read_text()
s=s.replace('import org.mozilla.javascript.Context;','''import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;''')
s=s.replace('    private JavaScript() {','''    // Instruction checks stop stale/infinite player JS; thread interruption is propagated.
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final ContextFactory BOUNDED_FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            final Context context = super.makeContext();
            context.setInstructionObserverThreshold(10_000);
            context.setInterpretedMode(true);
            return context;
        }

        @Override
        protected void observeInstructionCount(final Context context, final int count) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("JavaScript interrupted");
            }
            final Long deadline = DEADLINE.get();
            if (deadline != null && System.nanoTime() >= deadline) {
                throw new IllegalStateException("JavaScript execution budget exceeded");
            }
        }
    };

    private JavaScript() {''')
s=s.replace('try (Context context = Context.enter()) {','''DEADLINE.set(System.nanoTime() + 2_000_000_000L);
        try (Context context = BOUNDED_FACTORY.enterContext()) {''')
s=s.replace('''            context.compileString(function, null, 1, null);
        }''','''            context.compileString(function, null, 1, null);
        } finally {
            DEADLINE.remove();
        }''')
s=s.replace('''            return result.toString();
        }''','''            return result.toString();
        } finally {
            DEADLINE.remove();
        }''')
p.write_text(s)
t=r/'extractor/src/test/java/org/schabi/newpipe/extractor/services/youtube/PolarisSignatureTest.java';t.write_text('''package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.utils.JavaScript;
import static org.junit.jupiter.api.Assertions.*;

class PolarisSignatureTest {
    private static String fixture(final String global, final String function, final String arg) {
        return "var " + global + "=\\\"rv;join\\\".split(\\\";\\\");"
            + "var HH={rv:function(a){a.reverse()}};"
            + function + "=function(" + arg + "){" + arg + "=" + arg
            + ".split(\\\"\\\");HH[" + global + "[0]](" + arg + ");return " + arg + ".join(\\\"\\\")};";
    }
    @Test void multiCharacterGlobalArrayAndAlternateParameter() throws Exception {
        final String js = fixture("AB", "f", "q");
        final String code = YoutubeSignatureUtils.getDeobfuscationCode(js);
        assertEquals("cba", JavaScript.run(code, "deobfuscate", "abc"));
    }
    @Test void legacySignatureStillWorks() throws Exception {
        final String js = fixture("A", "dec", "a") + "c&&(c=dec(decodeURIComponent(c)))";
        assertEquals("cba", JavaScript.run(YoutubeSignatureUtils.getDeobfuscationCode(js), "deobfuscate", "abc"));
    }
    @Test void signatureTimestampStillExtracted() throws Exception {
        assertEquals("12345", YoutubeSignatureUtils.getSignatureTimestamp("signatureTimestamp:12345"));
    }
    @Test void infiniteJavaScriptStopsWithinBudget() {
        final long start = System.nanoTime();
        assertThrows(IllegalStateException.class, () -> JavaScript.run("function spin(){while(true){}}", "spin"));
        assertTrue(System.nanoTime() - start < 6_000_000_000L);
    }
    @Test void interruptedJavaScriptPropagatesCancellation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> JavaScript.run("function spin(){while(true){}}", "spin"));
        } finally { Thread.interrupted(); }
    }
}
''')
print('Pinned extractor: multi-character signature arrays, parameter capture and interruptible 2s JS budget')
p=r/'build.gradle.kts';s=p.read_text().replace('languageVersion.set(JavaLanguageVersion.of(11))','languageVersion.set(JavaLanguageVersion.of(21))').replace('options.encoding = Charsets.UTF_8.toString()','options.encoding = Charsets.UTF_8.toString()\n        options.release.set(11)');p.write_text(s)
