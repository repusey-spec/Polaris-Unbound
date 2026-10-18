package com.polaris.cprobe;

/** No Android, HTTP access, or user credentials required. */
public final class YtmSearchProtocolCliTest {
    public static void main(String[] args) {
        if(!"EgWKAQIIAWoMEA4QChADEAQQCRAF".equals(
                YtmSearchProtocol.SONGS_FILTER))
            throw new AssertionError("ytmusicapi 1.12.3 public songs param changed");
        if(YtmSearchProtocol.LEGACY_SONGS_FILTER.equals(
                YtmSearchProtocol.SONGS_FILTER))
            throw new AssertionError("C05 wrong filter must not become the C06 primary");
        if(!"SOCS=CAI".equals(YtmSearchProtocol.CONSENT_COOKIE))
            throw new AssertionError("ytmusicapi public consent cookie mismatch");
        if(!YtmSearchProtocol.SEARCH_URL.endsWith("/search?alt=json"))
            throw new AssertionError("ytmusicapi search endpoint mismatch");
        System.out.println("C06 ytmusicapi 1.12.3 protocol fixtures: 4 OK");
    }
}
