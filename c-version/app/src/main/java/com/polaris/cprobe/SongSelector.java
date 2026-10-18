package com.polaris.cprobe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, deterministic selector. No Android or network dependencies. */
final class SongSelector {
    private SongSelector() {}

    static final class Candidate {
        final String id;
        final String title;
        final String artist;
        final int rank;
        Candidate(String id, String title, String artist, int rank) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.rank = rank;
        }
    }

    static final class Selection {
        final Candidate song;
        final int score;
        Selection(Candidate song, int score) {
            this.song = song;
            this.score = score;
        }
    }

    /** Lower-case and strip only non-letter/non-number separators, keeping Hangul. */
    private static String norm(String s) {
        if(s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean isArtistMatch(String requested, String artist) {
        if(requested.isEmpty() || artist.isEmpty()) return false;
        if(artist.equals(requested)) return true;
        // The credit "서태지와 아이들" must accept "서태지".
        return requested.length() >= 2 && artist.contains(requested);
    }

    private static int titleScore(String wanted, String actual) {
        if(wanted.isEmpty() || actual.isEmpty()) return -10000;
        if(actual.equals(wanted)) return 140;
        // Korean particles are common in spoken queries: "너에게는" -> "너에게".
        if(wanted.length() > 2) {
            for(String particle : new String[]{"는", "은", "을", "를"}) {
                if(wanted.endsWith(particle) &&
                   actual.equals(wanted.substring(0, wanted.length()-particle.length())))
                    return 121;
            }
        }
        // A listed title may have a suffix such as "(2013 Remastered)".
        if(actual.startsWith(wanted) && wanted.length() >= 3) return 105;
        // A partial track title is weaker; do not use it unless artist matches.
        if(wanted.length() >= 4 && actual.contains(wanted)) return 80;
        return -10000;
    }

    private static int versionPenalty(String originalQuery, String candidateTitle) {
        String q = norm(originalQuery), t = norm(candidateTitle);
        int penalty = 0;
        for(String term : new String[]{"live","cover","remix","karaoke",
            "instrumental","inst","노래방","커버","라이브","리믹스"}) {
            if(t.contains(term) && !q.contains(term)) penalty += 14;
        }
        return penalty;
    }

    private static int score(String rawQuery, Candidate c) {
        if(c == null || c.id == null ||
           !c.id.matches("[A-Za-z0-9_-]{11}")) return -10000;
        String artist = norm(c.artist), title = norm(c.title);
        String[] parts = rawQuery == null ? new String[0] :
            rawQuery.trim().split("\\s+");
        if(parts.length == 0 || parts[0].isEmpty()) return -10000;

        int best = -10000;
        if(parts.length == 1) {
            // Without an artist, only an exact or close title can match.
            int t = titleScore(norm(rawQuery), title);
            if(t >= 105) best = t + 40;
        } else {
            // Support "성시경 너에게" and multi-word artists such as
            // "James Morrison You Give Me Something" without hardcoding artists.
            for(int split = 1; split < parts.length && split <= 5; split++) {
                StringBuilder requestedArtist = new StringBuilder();
                StringBuilder requestedTitle = new StringBuilder();
                for(int i = 0; i < parts.length; i++) {
                    if(i < split) requestedArtist.append(parts[i]);
                    else requestedTitle.append(parts[i]);
                }
                String a = norm(requestedArtist.toString());
                String t = norm(requestedTitle.toString());
                if(!isArtistMatch(a, artist)) continue;
                int ts = titleScore(t, title);
                if(ts < 80) continue;
                int artistScore = artist.equals(a) ? 125 : 105;
                best = Math.max(best, artistScore + ts);
            }
        }
        return best <= 0 ? best : best - versionPenalty(rawQuery, c.title);
    }

    /**
     * Never select the first arbitrary result. Require a strong title match
     * and, when supplied, a match against the *actual artist field*.
     * Ties across different artists for title-only requests are ambiguous.
     */
    static Selection select(String query, List<Candidate> options) {
        if(query == null || query.trim().isEmpty() || options == null)
            return null;
        List<Selection> ranked = new ArrayList<>();
        for(Candidate c : options) {
            int score = score(query, c);
            if(score >= 130) ranked.add(new Selection(c, score));
        }
        if(ranked.isEmpty()) return null;
        ranked.sort((a,b) -> {
            int diff = Integer.compare(b.score, a.score);
            return diff != 0 ? diff :
                Integer.compare(a.song.rank, b.song.rank);
        });
        Selection best = ranked.get(0);
        if(best.score < (query.trim().contains(" ") ? 205 : 160))
            return null;

        // If only the song title was supplied, do not randomly choose a
        // performer when two distinct artists have near-identical scores.
        if(!query.trim().contains(" ")) {
            for(int i=1; i<ranked.size(); i++) {
                Selection other = ranked.get(i);
                if(best.score - other.score > 10) break;
                if(!norm(best.song.artist).equals(norm(other.song.artist)))
                    return null;
            }
        }
        return best;
    }
}
