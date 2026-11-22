package ai.crystalgram.holo;

import android.graphics.Color;

/**
 * CrystalGram's own colours, and only these. Everything else in the app comes
 * from the platform theme, so the chrome stays Android's and only the bubbles
 * carry identity.
 *
 * Pastels on purpose: they read as distinct objects against Holo Dark's
 * background_holo_dark without the glare of saturated fills, and they pair
 * with dark text rather than white.
 */
public final class Palette {

    private Palette() {}

    /** Soft pastel lavender for messages the user sent. */
    public static final int LAVENDER = 0xFFD8CDF0;

    /** Soft pastel mint for messages the user received. */
    public static final int MINT = 0xFFCDE8D8;

    /**
     * Near-black text. Pastel fills need dark text; white on these would be
     * unreadable, and pure black is harsher than necessary.
     */
    public static final int BUBBLE_TEXT = 0xFF1A1A1A;

    /** Muted text for timestamps inside bubbles. */
    public static final int BUBBLE_TIME = 0xFF5A5A5A;

    /** Muted text for the second line of a chat list row. */
    public static final int PREVIEW = 0xFF9A9A9A;

    /** Timestamp/marker colour for a message that failed to send. */
    public static final int FAILED = 0xFFB04040;

    public static final int LAVENDER_STRIP = 0xFF6F5FA8;
    public static final int MINT_STRIP = 0xFF4E8A6C;

    public static int text(boolean outgoing) { return BUBBLE_TEXT; }
    public static int time(boolean outgoing) { return BUBBLE_TIME; }

    /** Accent used for the incoming avatar placeholder / chat strip. */
    public static int accent(boolean outgoing) {
        return outgoing ? LAVENDER_STRIP : MINT_STRIP;
    }

    /** Sanity: keeps the palette honest if someone edits a value by hand. */
    public static boolean isValid(int c) {
        return Color.alpha(c) != 0;
    }
}
