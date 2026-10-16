package com.nectariich.create_flippies.content.rotary_switch;

import java.util.List;

/**
 * Everything about a rotary switch that is fixed the moment it is made: how big
 * its front is, how many detents it has, and what its contacts do at each of them.
 *
 * This is what the items and the blocks are, one apiece, because it is the one
 * thing about a switch that cannot be changed once it is placed: everything else a
 * switch looks like — what is printed on its front, the paint on the body, the
 * shape of the handle — is {@link RotarySwitchAppearance} and can be set with a
 * wrench afterwards. Position count cannot join it, because the detent the handle
 * is resting in is saved as a number and the circuit is built from it; a switch
 * that lost a position under a running handle would have to decide where the
 * handle went, and a switch that gained one would have to decide what the new
 * detent does electrically. Size cannot either, for the reason
 * {@link RotarySwitchSize} gives.
 *
 * Handle angles are degrees about the mounting axis, negative turning clockwise as
 * seen from the front, so a switch's detents run left to right across the printed
 * arc in the order they are numbered.
 *
 * Every section has one common riding over the studs listed here, and at any
 * detent it is on exactly one of them. That is why the three-position switch names
 * its studs after the way the handle is turned rather than after a resting state:
 * its middle detent is a selection like the other two and lands on a neutral stud
 * of its own, so there is no position left over for "normally" to mean anything
 * against. See {@link RotarySwitchContact}.
 */
public enum RotarySwitchKind {

    /** Upright is the first marking, a quarter turn clockwise the second. */
    TWO_POSITION(RotarySwitchSize.MED, "2pos", new float[] { 0, -45 },
            new RotarySwitchContact[] { RotarySwitchContact.NORMALLY_OPEN,
                    RotarySwitchContact.NORMALLY_CLOSED },
            new RotarySwitchContact[] { RotarySwitchContact.NORMALLY_CLOSED,
                    RotarySwitchContact.NORMALLY_OPEN }),

    /** Upright is the middle marking, with a quarter turn either side of it. */
    THREE_POSITION(RotarySwitchSize.MED, "3pos", new float[] { 45, 0, -45 },
            new RotarySwitchContact[] { RotarySwitchContact.COUNTER_CLOCKWISE, RotarySwitchContact.NEUTRAL,
                    RotarySwitchContact.CLOCKWISE },
            new RotarySwitchContact[] { RotarySwitchContact.COUNTER_CLOCKWISE, RotarySwitchContact.NEUTRAL,
                    RotarySwitchContact.CLOCKWISE }),

    /** The same three detents on the larger front; see {@link RotarySwitchSize}. */
    LARGE_THREE_POSITION(RotarySwitchSize.LARGE, "3pos", new float[] { 45, 0, -45 },
            new RotarySwitchContact[] { RotarySwitchContact.COUNTER_CLOCKWISE, RotarySwitchContact.NEUTRAL,
                    RotarySwitchContact.CLOCKWISE },
            new RotarySwitchContact[] { RotarySwitchContact.COUNTER_CLOCKWISE, RotarySwitchContact.NEUTRAL,
                    RotarySwitchContact.CLOCKWISE });

    /**
     * The sections on the rack, in the order they sit on it, A nearest the panel.
     *
     * Every switch has all three and they all do the same thing at the same time,
     * so this is a property of the gear rather than of any one kind. It lives here
     * and not on {@link RotarySwitchBlock} because the block's own tables are built
     * from the kinds, and a constant read the other way would have the two classes
     * initialising each other.
     */
    public static final String[] SECTIONS = { "A", "B", "C" };

    private final RotarySwitchSize size;
    private final String id;
    private final float[] handleAngles;
    private final RotarySwitchContact[] studs;
    private final RotarySwitchContact[] made;

    RotarySwitchKind(RotarySwitchSize size, String id, float[] handleAngles, RotarySwitchContact[] studs,
            RotarySwitchContact[] made) {
        this.size = size;
        this.id = id;
        this.handleAngles = handleAngles;
        this.studs = studs;
        this.made = made;
    }

    public RotarySwitchSize size() {
        return size;
    }

    /** This kind's detent count as it appears in names; not unique on its own. */
    public String id() {
        return id;
    }

    /** Registry name of this kind's block and item, and the stem of its model paths. */
    public String blockId() {
        return "rotary_switch_" + size.id() + "_" + id;
    }

    public int positions() {
        return handleAngles.length;
    }

    /**
     * The detent a switch is made in.
     *
     * The middle one wherever there is a middle. A three-position switch is a
     * selector with a neutral in the centre of its arc, and one placed resting hard
     * over on its counter-clockwise stud reads as a switch somebody has already
     * thrown — and wires it as one too, since that stud is made from the moment it
     * goes on. A switch with an even number of detents has no middle and starts at
     * the first marking, which for the two-position switch is the upright one and the
     * open contact.
     */
    public int defaultPosition() {
        return positions() % 2 == 1 ? positions() / 2 : 0;
    }

    /**
     * Where the handle points at this detent, in degrees about the mounting axis.
     * Zero is upright and negative turns clockwise as seen from the front, so -45
     * is the quarter turn down and to the right.
     */
    public float handleAngle(int position) {
        return handleAngles[Math.floorMod(position, positions())];
    }

    /**
     * The studs one section carries, in the order they stand across the rack and so
     * in the order their terminals are numbered after the common.
     */
    public RotarySwitchContact[] studs() {
        return studs;
    }

    /**
     * Terminals one section has: its common, and one per stud.
     *
     * All three sections do the same thing at the same time — one handle turns them
     * — so the layout is stated once rather than once per section.
     */
    public int contactsPerSection() {
        return studs.length + 1;
    }

    public int terminalCount() {
        return SECTIONS.length * contactsPerSection();
    }

    /** Terminal index of one section's common, within that section. */
    public int commonIndex() {
        return 0;
    }

    /** Terminal index of one stud, within its section. */
    public int studIndex(int stud) {
        return stud + 1;
    }

    /** Which stud every section's common is resting on at this detent. */
    public RotarySwitchContact contact(int position) {
        return made[Math.floorMod(position, positions())];
    }

    /**
     * Which of {@link #studs()} the common is on at this detent, or -1 if it is on
     * none of them.
     *
     * Asked for by index rather than by contact so that the wires can be driven
     * without a lookup, and so that a stack of wires built before a switch knew its
     * kind — see {@code RotarySwitchBlockEntity.buildCircuit} — can be walked
     * safely by its own length.
     */
    public int madeStud(int position) {
        RotarySwitchContact contact = contact(position);
        for (int stud = 0; stud < studs.length; stud++)
            if (studs[stud] == contact)
                return stud;
        return -1;
    }

    /**
     * The fronts this switch can be printed with, in the order the wrench menu
     * cycles them. Asked of {@link RotarySwitchMarking} rather than held here so
     * that a marking states which switch it belongs to exactly once.
     */
    public List<RotarySwitchMarking> markings() {
        return RotarySwitchMarking.of(this);
    }

    /** What a freshly placed switch is printed with. */
    public RotarySwitchMarking defaultMarking() {
        return markings().get(0);
    }
}
