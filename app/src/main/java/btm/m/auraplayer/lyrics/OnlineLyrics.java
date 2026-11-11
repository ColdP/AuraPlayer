// Imported from HyperMusicCover snapshot 2026-10-30; adapted for Aura Player via HyperChanger.
// Original licenses/notices: see NOTICE and docs/player-source-manifest.json.
package btm.m.auraplayer.lyrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The by-name catalogues, in the order HyperLyrics Enhanced asks them, and the one answer taken.
 *
 * Its order is by player: a NetEase player's song is looked for on NetEase first and a QQ
 * Music player's on QQ first, because a player's own catalogue is the one whose entry is that
 * recording; everything else starts with QQ, the bigger catalogue, then NetEase. HyperLyrics
 * Enhanced stops there. This goes on to Kuwo, KuGou and LrcLib, one after another, for a song
 * neither of the two had - with KuGou and Kuwo moved to the front for their own players.
 *
 * The first two are asked together and the earlier one in the order is preferred - an answer
 * from the second waits for the first to finish, so the choice is the order's and not the
 * network's - which costs one catalogue's latency rather than two. The rest are asked one at a
 * time: this is the path for a song the first two could not place, and it should not spend
 * five requests on every one that could.
 */
public final class OnlineLyrics {

    private OnlineLyrics() {
    }

    enum Src { QQ, NETEASE, KUWO, KUGOU, LRCLIB }

    /** One catalogue's answer, in the shape LyricSource parses. */
    static final class Found {
        final Src src;
        final String id;
        final String body;
        final String translation;
        final String roma;
        final boolean words;

        Found(Src src, String id, String body, String translation, String roma, boolean words) {
            this.src = src;
            this.id = id;
            this.body = body;
            this.translation = translation;
            this.roma = roma;
            this.words = words;
        }

        int source() {
            switch (src) {
                case QQ:
                    return 8;
                case NETEASE:
                    return 3;
                case KUWO:
                    return 9;
                case KUGOU:
                    return 4;
                default:
                    return 5;
            }
        }

        String who() {
            switch (src) {
                case QQ:
                    return "QQ Music";
                case NETEASE:
                    return "NetEase";
                case KUWO:
                    return "Kuwo";
                case KUGOU:
                    return "KuGou";
                default:
                    return "LrcLib";
            }
        }
    }

    /** The order for a player - HyperLyrics Enhanced's first two, then ours. */
    static List<Src> order(String pkg) {
        if ("com.netease.cloudmusic".equals(pkg)) {
            return Arrays.asList(Src.NETEASE, Src.QQ, Src.KUWO, Src.KUGOU, Src.LRCLIB);
        }
        if ("com.kugou.android".equals(pkg) || "com.kugou.android.lite".equals(pkg)) {
            return Arrays.asList(Src.KUGOU, Src.QQ, Src.NETEASE, Src.KUWO, Src.LRCLIB);
        }
        if ("cn.kuwo.player".equals(pkg)) {
            return Arrays.asList(Src.KUWO, Src.QQ, Src.NETEASE, Src.KUGOU, Src.LRCLIB);
        }
        // QQ Music's own and everyone else's: QQ, then NetEase.
        return Arrays.asList(Src.QQ, Src.NETEASE, Src.KUWO, Src.KUGOU, Src.LRCLIB);
    }

    /** The whole lookup's budget, the same as the race it runs in. */
    private static final long BUDGET_MS = 8000L;

    /** The first two in the order, together. Null when neither placed the song. */
    /** The rest of the order, one at a time. Null when none of them had it. */
    static Found rest(String pkg, NcmLyrics.Query q) {
        List<Src> order = order(pkg);
        for (int i = 2; i < order.size(); i++) {
            Found f = ask(order.get(i), q);
            if (f != null) return f;
        }
        return null;
    }

    /** Where a catalogue's answer is said to be from, as LyricSource numbers its sources. */
    static int sourceOf(Src src) {
        return new Found(src, null, null, null, null, false).source();
    }

    /** One catalogue's answer for the song, or null. */
    static Found ask(Src src, NcmLyrics.Query q) {
        try {
            switch (src) {
                case QQ: {
                    QqLyrics.Found f = QqLyrics.load(q);
                    return f == null ? null
                            : new Found(src, f.id, f.body, f.translation, f.roma, f.words);
                }
                case NETEASE: {
                    NcmLyrics.Found f = NcmLyrics.load(q);
                    return f == null ? null
                            : new Found(src, f.id, f.body, f.translation, f.roma, f.words);
                }
                case KUWO: {
                    KuwoLyrics.Found f = KuwoLyrics.load(q);
                    return f == null ? null
                            : new Found(src, f.id, f.body, f.translation, f.roma, f.words);
                }
                case KUGOU:
                case LRCLIB: {
                    // A KRC carries its translation and romanisation inside the body.
                    WebLyrics.Found f = WebLyrics.load(q, src == Src.KUGOU, src == Src.LRCLIB);
                    return f == null ? null : new Found(src, f.id, f.body, null, null, f.words);
                }
                default:
                    return null;
            }
        } catch (Throwable t) {
            Xp.w("[MCLyric] " + src + " failed: " + t);
            return null;
        }
    }

    /** For op ncm: the order, and what QQ Music and Kuwo make of the song. */
    public static String probe(String pkg, NcmLyrics.Query q) {
        StringBuilder sb = new StringBuilder("\n  order: ").append(describe(pkg));
        if (q == null) return sb.toString();
        for (Src s : new Src[]{Src.QQ, Src.KUWO}) {
            Found f = ask(s, q);
            sb.append("\n  ").append(s == Src.QQ ? "QQ Music" : "Kuwo").append(": ");
            if (f == null) {
                sb.append("no match");
                continue;
            }
            List<LyricLine> lines = LyricParse.parse(f.body, f.translation, f.roma);
            int tr = 0;
            for (LyricLine l : lines) if (l.translation != null) tr++;
            sb.append(f.id).append(' ').append(f.words ? "word-timed" : "line-timed").append(' ')
                    .append(lines.size()).append(" lines, ").append(tr).append(" with a translation")
                    .append(f.roma != null ? " (romanised)" : "");
        }
        return sb.toString();
    }

    /** For probes: the order a player's songs are looked for in. */
    static String describe(String pkg) {
        List<String> names = new ArrayList<>();
        for (Src s : order(pkg)) names.add(s.name().toLowerCase(java.util.Locale.ROOT));
        return String.join(">", names);
    }
}
