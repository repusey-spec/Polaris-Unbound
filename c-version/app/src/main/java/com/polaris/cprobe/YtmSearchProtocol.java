package com.polaris.cprobe;

/**
 * Public, unauthenticated YouTube Music WEB_REMIX search conventions.
 * Cross-checked against sigma67/ytmusicapi tag 1.12.3:
 * ytmusicapi/parsers/search.py#get_search_params(filter="songs")
 * ytmusicapi/ytmusic.py cookies={"SOCS":"CAI"}
 * ytmusicapi/constants.py YTM_PARAMS="?alt=json"
 */
final class YtmSearchProtocol {
    private YtmSearchProtocol() {}

    static final String SONGS_FILTER="EgWKAQIIAWoMEA4QChADEAQQCRAF";
    static final String LEGACY_SONGS_FILTER="EgWKAQIIAWoKEAMQBBAJEAoQEA==";
    static final String CONSENT_COOKIE="SOCS=CAI";
    static final String SEARCH_URL="https://music.youtube.com/youtubei/v1/search?alt=json";
}
