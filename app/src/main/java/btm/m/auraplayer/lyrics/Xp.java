package btm.m.auraplayer.lyrics;

/** Logging adapter for the imported HyperMusicCover sources; no hook runtime is loaded. */
final class Xp {
    static void log(String text) { android.util.Log.d("AuraLyrics", text); }
    static void w(String text) { android.util.Log.w("AuraLyrics", text); }
}
