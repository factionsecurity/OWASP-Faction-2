package com.faction.clientportal.util.reporting;

/**
 * The reserved hex colours a template author paints to say "colour this from the finding".
 *
 * <p>Each colourable dimension owns a <em>slot</em>: severity, likelihood and impact take 1–3, and
 * a user-defined field is allocated one from {@link #FIRST_CUSTOM_SLOT} upward. A slot has two
 * sentinels — a light one and a dark one — that mean exactly the same thing.
 *
 * <p>The pair exists for the author, not the generator. With one hex per dimension, colouring a
 * severity chip means painting the cell and its text the same value, so Word shows amber on amber:
 * the generated report is correct and the template is unreadable while you build it. Painting
 * {@code 1A0701} text on a {@code FAC701} cell is near-black on amber, which reads.
 *
 * <p>Which member goes where carries no meaning. The attribute already says whether a colour is a
 * fill or a font colour — {@code w:fill} against {@code w:color} — so the generator never needed
 * telling, and there is no wrong way round for an author to get caught by.
 *
 * <p>Both prefixes are chosen to be implausible as a deliberate choice in a real template while
 * still being valid RRGGBB. The trailing byte is the slot, in hex.
 */
public final class ColourSentinels {

    private ColourSentinels() {}

    /** Prefix of the light member of every pair — a saturated amber. */
    private static final String LIGHT_PREFIX = "FAC7";

    /** Prefix of the dark member — near-black, so it reads against its own light twin. */
    private static final String DARK_PREFIX = "1A07";

    public static final int SLOT_SEVERITY   = 1;
    public static final int SLOT_LIKELIHOOD = 2;
    public static final int SLOT_IMPACT     = 3;

    /** Slots 1–3 are the built-in dimensions; a user-defined field is allocated from here up. */
    public static final int FIRST_CUSTOM_SLOT = 4;

    /** The highest slot the trailing byte can express. */
    public static final int MAX_SLOT = 255;

    /** The light sentinel for a slot, e.g. {@code FAC701}. */
    public static String light(int slot) {
        return LIGHT_PREFIX + slotHex(slot);
    }

    /** The dark sentinel for a slot, e.g. {@code 1A0701}. */
    public static String dark(int slot) {
        return DARK_PREFIX + slotHex(slot);
    }

    /**
     * The slot a painted colour refers to, or null when it is an ordinary colour.
     *
     * <p>Case-insensitive: Word returns whatever case the file happens to hold, and an author
     * typing a hex by hand may use either. A sentinel that only matched uppercase would fail
     * silently — the cell would simply stay that colour in the delivered report.
     */
    public static Integer slotOf(String hex) {
        if (hex == null || hex.length() != 6) return null;
        String upper = hex.toUpperCase();
        if (!upper.startsWith(LIGHT_PREFIX) && !upper.startsWith(DARK_PREFIX)) return null;
        int slot;
        try {
            slot = Integer.parseInt(upper.substring(4), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return isUsableSlot(slot) ? slot : null;
    }

    /**
     * Whether a slot number can be expressed and allocated. Slot 0 is deliberately unclaimed, which
     * keeps the first real slot at 01 — the value existing templates already paint.
     */
    public static boolean isUsableSlot(int slot) {
        return slot >= 1 && slot <= MAX_SLOT;
    }

    private static String slotHex(int slot) {
        if (!isUsableSlot(slot)) {
            throw new IllegalArgumentException("Colour slot out of range: " + slot);
        }
        return String.format("%02X", slot);
    }
}
