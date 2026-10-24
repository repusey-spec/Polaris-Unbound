#!/usr/bin/env python3
"""Mount Polaris Radio as the phone's default top-level route, keeping Hush intact."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
nav = root / "app/src/main/kotlin/app/hush/music/ui/screens/NavigationBuilder.kt"
s = nav.read_text()
anchor = '    composable(Screens.ROUTE_HOME) {'
assert s.count(anchor) == 1
s = s.replace(anchor, '''    composable("polaris_radio") {
        app.hush.music.polaris.PolarisRadioScreen(navController)
    }
''' + anchor, 1)
nav.write_text(s)
main = root / "app/src/main/kotlin/app/hush/music/MainActivity.kt"
s = main.read_text()
anchor = '''                                    startDestination =
                                        if (launchMusicRecognitionFromShortcut) {'''
assert s.count(anchor) == 1
s = s.replace(anchor, '''                                    startDestination =
                                        if (launchMusicRecognitionFromShortcut) {''', 1)
# Change default landing route only, preserving shortcut intent behavior.
old = '''                                            when (tabOpenedFromShortcut ?: defaultOpenTab) {'''
assert s.count(old) == 1
s = s.replace(old, '''                                            when (tabOpenedFromShortcut ?: defaultOpenTab) {''', 1)
old = '''                                                NavigationTab.HOME -> Screens.ROUTE_HOME'''
assert s.count(old) == 1
s = s.replace(old, '''                                                NavigationTab.HOME -> "polaris_radio"''', 1)
main.write_text(s)
print("Polaris Radio mounted as default route; Hush Home remains available")
