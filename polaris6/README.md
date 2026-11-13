# Polaris 6.0

Polaris 6.0 is based on InterTune and OuterTune under GPL-3.0. The streaming engine and its home/navigation pages come from InterTune v0.12.10, commit `4ff39e9e12a20f93d5dfd24c52fe925cbbb50020`, including all pinned submodules. InterTune is maintained by ItzSkyeYT and its contributors; their copyright notices and licensing remain in the source. Original source: https://github.com/ItzSkyeYT/InterTune/tree/4ff39e9e12a20f93d5dfd24c52fe925cbbb50020

The reproducible integration source is `intertune.patch.gz.b64`, applied to that exact commit by `.github/workflows/polaris6.yml`. Decode base64, decompress gzip, and use `git apply` in the pinned clone. No Hush playback resolver, Go native library, Waze archive, or old overlay patch chain is built into this APK.

Polaris adds its existing launcher artwork, 13 radio channels, 4×4 persistent preset layout, Radio/Streaming selector, Korean radio voice routing, read-only station artwork provider, service-owned live radio recovery and a local TXT report. One ExoPlayer/MediaLibrarySession serves both modes; radio media sources bypass YouTube resolution and song cache. Next-song preloading is disabled for live radio and restored for music. Streaming home destinations remain native InterTune.

Install identity: `app.hush.music.polarisradio.debug`, versionName `6.0`, versionCode `100600`, using the existing Polaris signer. Core release uses R8/resource shrinking. Full/FFmpeg flavour is intentionally excluded.

Old Hush Room schemas, settings and cache/download indexes are not compatible. Version 6 uses isolated names and never deletes or migrates the old files implicitly. Radio presets keep their original SharedPreferences keys. The new streaming account must be signed into and the library resynchronised in the new engine; old app-internal downloads do not silently become new-engine downloads.

App diagnostics are bounded, asynchronous and local. They include audio underruns, decoder/sink errors, player state, AA changes, resource samples while playing, scrubbed native engine logs, last app crash and Android process exit metadata/ANR trace when available. TXT export retains more than the bounded clipboard/preview. These are app-visible signals, not a guarantee that analog distortion, an automotive head-unit fault, a kernel freeze or another app's private errors are recorded.

Local/source/unit checks and an emulator launch do not prove performance on a Galaxy S8 with Android Auto and navigation over several hours. Such results must be identified separately from build success.
