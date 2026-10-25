# Polaris + Hush v13.14.7 기능·의존성 감사 (경량화 기준)

검토 기준: 2026-10-26, 통합 소스 `experiment/polaris-hush-radio-01` commit `f1f354899387eee16e8d07e815996f1d9b900a1f`, Build #37.
**이 문서는 분석용이며 소스 삭제 또는 APK 재빌드를 수행하지 않는다.**

## 1. 실제 빌드 방식

`.github/workflows/polaris-hush-radio.yml` → Hush `maxinjohn/Hush` tag `v13.14.7`, commit `8f3e55d1114f1b844f0a21af8c365d090a43799f` 고정 checkout → 가사 서브모듈 복구 → `polaris-hush/*.kt` 및 Java 두 파일 복사 → `patch_hush_browse.py`, `patch_hush_radio_all.py`, `patch_hush_phone_ui.py`, `patch_hush_mini_player.py`, `patch_hush_radio_recovery.py`, `patch_hush_branding.py` 적용 → `:app:assembleGmsMobileArm64Debug` → APK 라벨/패키지/서명/버전 확인 → artifact upload.

원본 Hush는 별도 고정 체크아웃으로 매 빌드 시 주입된다. 이 Polaris 저장소에는 Hush 전체 수정판이 커밋되어 있지 않다. 따라서 삭제는 (a) 원본의 특정 파일과 참조를 일관되게 변경하는 **버전 고정 패치 스크립트**나 (b) 관리되는 소스 포크를 통해 수행해야 한다. 단순히 Polaris 저장소의 사용하지 않는 Hush 파일을 지워도 APK에 반영되지 않는다.

## 2. 구조/핵심 의존성

### 남겨야 하는 작동 코어
- **`:app`, `:core`**: `MainActivity.kt`, `NavigationBuilder.kt`, Hush Compose 검색/탐색, `core/.../innertube/YouTube.kt`/InnerTube API, NewPipe extractor, 로그인/인증 및 BotGuard/cipher 처리.
- **Playback**: `MusicService.kt` (단일 Media3 `MediaLibraryService`, ExoPlayer/Cast wrapper), `MediaLibrarySessionCallback.kt` (Android Auto browse/search/onSetMediaItems 및 음성 검색), `PlayerConnection.kt`, `MiniPlayerComponents.kt`, 미디어 세션·오디오 포커스·큐 복구.
- **Polaris**: `polaris-hush/RadioCatalog.kt`, `PolarisRadioResolver.kt`, `PolarisRadioStreams.kt`, `PolarisRadioPlayback.kt`, `PolarisRadioRecovery.kt`, `CurrentProgramResolver.java`, `PolarisRadioLayout.kt`, `PolarisRadioGrid.kt`, `PolarisRadioScreen.kt`, `PolarisTopTabs.kt`, `PolarisMiniPlayerInfo.kt`, `StationArt.java`.
- **AA 및 음악 로그인**을 유지하는 동안 YouTube/Google 세션, 네트워크, Media3, 필수 Room/DataStore/Hilt 의존성을 먼저 제거하지 않는다. Hush voice playback을 일반 `MusicRecognition` (Shazam)과 혼동하지 말 것.

### 선택적 Hush 기능 (다른 경로와 결합되어 있으므로 단계적 분리)
| 기능 | 실제 파일/모듈 | 결합점 및 주의 | 우선순위 |
|---|---|---|---|
| Last.fm 외부 스크로블 | `:lastfm`, `utils/ScrobbleManager.kt`, `scrobbling/*`, `LastFMSettings.kt` | `App.kt` 초기화, `MusicService.kt` 상태 수집, `NavigationBuilder.kt`, 설정 및 저장된 토큰 | 1 |
| ListenBrainz | `ListenBrainzManager.kt`, `IntegrationScreen.kt` | 서비스/Preference 및 요청 로직을 함께 제거해야 기능 삭제가 됨 | 1 |
| Waze 브리지 | `waze-shim`, `assets/waze-shims.zip`, `waze/*`, `WazeCommandReceiver.kt` | `App.kt`, `MusicService.kt`, `SettingsDataBuilders.kt`, `AndroidManifest.xml`, Gradle 자동 패키징과 연결. **Android Auto 서비스와는 별개** | 1-2 |
| 스마트폰 위젯 | `widget/*`, `MusicServiceWidgetUpdater.kt` | 다수 manifest receiver, MusicService 알림/재생 갱신 | 2 |
| 음악 인식/퀵 타일 | `:shazamkit`, `musicrecognition/*`, `MusicRecognitionScreen.kt` | `RECORD_AUDIO`, Quick Settings 서비스, Navigation, Hush 음악 ID 검색과 구분 | 2 |
| Music Together | `together/*`, `MusicTogetherScreen.kt` | MusicService 실시간 동기화, 미니플레이어 버튼, Ktor 서버·웹소켓 | 2 |
| Podcast, 앨범/인기차트/발견 및 커뮤니티 UI | `ui/screens/podcast/*`, `ui/screens/*`, `viewmodels/*`, `NavigationBuilder.kt` | 단순 화면 삭제 전 앱 내부/AA 재생목록 참조 확인 | 2-3 |
| 재생 알람/AOD | `playback/alarm/*`, `aod/*` | alarm/boot 권한, 리시버/타일, player callbacks | 2 |
| 가사·AI 번역 | `:lyrics:* ` 8개 모듈, `lyrics/*`, `ai/*` | MusicService, UI, LyricsHelper, App init, 재생 메타데이터에 걸쳐 있음 | 3 |
| Spotify 보관함 | `:spotifycore`, `spotify/*`, `ui/screens/playlist/SpotifyPlaylistScreen.kt` | Android Auto 및 재생 큐 매칭에 연결. 사용하지 않는지 먼저 확인 | 3 |
| SpotiFLAC 다중 소스/무손실 | `spotiflac/*`, `app/libs/gobackend.aar`, `libgojni.so`, `app/build.gradle.kts` | 플레이어 소스 선택·캐시·오류 복구·백그라운드 세션 갱신·다운로드와 깊이 결합. 용량 효과 크지만 가장 위험 | 4 |
| YouTube 로그인/InnerTube, Media3 세션, BotGuard/PO-token | `:core`, `auth/*`, `utils/potoken/*`, `MusicService`, `MediaLibrarySessionCallback` | 정확한 검색·재생·AA 음성 인식의 근간 | **삭제 금지** |

### Gradle 모듈
`settings.gradle.kts` 등록: `:app`, `:benchmark`, `:core`, 가사 8개(`kugou, lrclib, simpmusic, paxsenix, betterlyrics, unison, youlyplus, musixmatch`), `:lastfm`, `:canvas`, `:shazamkit`, `:spotifycore`, `:moriextractor`, `:waze-shim`. `:benchmark`는 릴리스 앱에 직접 포함되는 기능이 아닌 빌드/벤치마크 모듈이다. `:waze-shim`은 앱에 직접 implementation되는 모듈이 아닌 내장 APK ZIP 생성 경로가 있다. `:core`는 설정에 중복 선언이 있다.

## 3. Build #37 파일 단위 실측

`/mnt/data/Polaris-Radio-MiniPlayer-B37.apk`를 ZIP으로 열어 조사함(소스 추정 수치 아님).
- APK 전체: `67,654,275` bytes = **64.52 MiB**.
- DEX 40개: ZIP 압축 합계 **44.99 MiB**. Debug 빌드이므로 컴파일러 최적화/R8 적용 후 용량을 별도로 비교해야 함.
- arm64 라이브러리: **8.60 MiB**, 그 대부분이 `lib/arm64-v8a/libgojni.so` (SpotiFLAC Go 런타임 관련).
- `assets/waze-shims.zip`: **1.80 MiB**.
- `resources.arsc`와 폰트·UI 리소스 또한 유의미한 비중.
- 이 수치는 개별 코드 기능의 독립 삭제 시 절감량을 뜻하지 않음. 트랜지티브 의존성과 코드 공유 때문에 각각 실제 APK 측정 필요.

## 4. 권장 삭제·안정성 게이트

1. **기준 보호**: Build #37 APK/서명/소스 SHA 보존, v0.58 main 미변경. 실험 브랜치에서 기능 한 묶음씩 제거.
2. **Tier 1 — 외부 스크로블**: Last.fm/ListenBrainz 설정 노출 → 토큰/스케줄러 → 앱/서비스 네트워크 요청 → 모듈/의존성 순서로 완전 분리. 로그인 데이터는 지우지 않고 사용하지 않게 처리(기존 사용자 데이터 보존).
3. **Tier 1-2 — Waze 전용 부가기능**: 앱 내 Waze 관리, 시작 시 자동 재연결, shim ZIP 생성/패키징, 전용 receiver/permission을 함께 제거. 단일 Android Auto `MediaLibrarySession`과 MediaButtonReceiver는 유지.
4. **Tier 2 — 음악 인식·위젯·알람·동시재생**: 각 기능을 서비스/manifest/UI/Gradle 기준으로 잘라내고 중간 컴파일. 단, 실제 사용 여부를 명확히 구분.
5. **Tier 3 — Spotify/가사/AI/다운로드**: 관련 설정·데이터·DB migrations와 AA playlist 참조를 조사하고 단계 제거.
6. **Tier 4 — SpotiFLAC**: YouTube-only 검색→재생→오류 재시도 경로가 정상임을 먼저 검증. Source priority 및 prefetch/cache fallback 제거 후 `gobackend.aar` 삭제. 기본값 `SpotiFLACEnabledKey=false`라고 해서 native를 바로 삭제하면 안 됨.

### 모든 실험에서 통과해야 하는 회귀 시험
- 폰: Polaris 런처 아이콘 및 설치 업데이트, 고정 Radio/Streaming 인덱스와 타일 겹침 없음; 라디오 13개 목록과 4×4 롱프레스, ID 보존, 동적 URL 갱신/끊김 후 복구, 하단 편성표.
- Streaming: 로그인 계정 보관함·검색 결과의 곡 선택·재생/일시정지/스킵·백그라운드 재생·미니플레이어, URL/cipher/BotGuard.
- AA: 기존 Hush 한 MediaLibrarySession 사용, browse root `Radio/Streaming`, 검색·음성·PlayFromSearch, 음성 도중 오디오 포커스와 재개, 미디어 버튼.
- CI: Android 빌드·APK 검사 통과, 동일 서명 인증서, 이전보다 높은 versionCode, 산출물명/크기 비교.
- APK 용량뿐 아니라 초기 화면 표시 시간, 메모리, 네트워크/배터리 비용도 비교. **작아지는 것과 빨라지는 것은 다름**.

## 5. 현재 알려진 비경량화 UI 이슈
Build #37 Radio 화면의 고정 상단 `Radio / Streaming` 인덱스가 첫 번째 타일 행 위로 겹침. 이는 Hush `Scaffold`/NavHost의 콘텐츠 inset 및 TopBar 위치 문제로, 모듈 삭제와 별개로 추적. 하단 라디오/스트리밍 재생바는 사용자 실기기 확인에 따라 정상으로 간주하고 보호.

## 6. 작업 상태
본 분석 시점에는 어떠한 Hush 기능도 삭제하지 않음. 제거 시에는 **Hush upstream commit에 적용하는 버전 고정 파서 패치가 실제 CI 빌드에 반영되는지**부터 검증해야 하며, 원본 Hush/ArchiveTune GPL 저작권 헤더는 유지한다.
