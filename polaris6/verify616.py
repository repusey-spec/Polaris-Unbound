from pathlib import Path
import sys
r=Path(sys.argv[1])
root=r/"app/src/main/java/com/dd3boh/outertune"
cb=(root/"playback/MediaLibrarySessionCallback.kt").read_text()
nav=(root/"polaris/Polaris613Nav.kt").read_text()
auto=(root/"playback/AutoCar.kt").read_text()
checks={
"Home records still independent":'return listOfNotNull(radio,music)' in cb,
"Home last radio 1 line":'.replace(10.toChar(),\' \').replace(13.toChar(),\' \').take(70)' in cb,
"last song album URI recorded":'.putString("artwork_uri",item.mediaMetadata.artworkUri?.toString().orEmpty())' in nav,
"last song album URI restored":'.setArtworkUri(artwork)' in nav,
"Home rows omit secondary text":'startsWith("최근 음악 ·")' in auto and 'startsWith("최근 라디오 ·")' in auto,
"native radio art":'Polaris613Nav.KR -> R.drawable.polaris616_kr' in auto and 'Polaris613Nav.WORLD -> R.drawable.polaris616_world' in auto,
"native streaming art":'R.drawable.polaris616_playlist' in auto and 'R.drawable.polaris616_download' in auto,
"cache bust browser":'appendQueryParameter("v","616")' in nav,
"version":'versionCode = 100616' in (r/"app/build.gradle.kts").read_text(),
"four approved PNG resources":all((r/("app/src/main/res/drawable-nodpi/polaris616_"+k+".png")).read_bytes().startswith(b"\\x89PNG\\r\\n\\x1a\\n") for k in ("kr","world","playlist","download")),
"same 4 image resources used by content provider":all("R.drawable.polaris616_"+k in (root/"polaris/Polaris613ArtProvider.kt").read_text() for k in ("kr","world","playlist","download")),
"old icon cache invalidated":'polaris-616-icons' in (root/"polaris/Polaris613ArtProvider.kt").read_text(),
}
for label,ok in checks.items(): print(("PASS" if ok else "FAIL")+": "+label)
assert all(checks.values())
