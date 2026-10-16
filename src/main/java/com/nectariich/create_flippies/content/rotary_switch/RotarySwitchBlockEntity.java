package com.nectariich.create_flippies.content.rotary_switch;

import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.nectariich.create_flippies.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * The contact stack behind a {@link RotarySwitchBlock}: for each of the three
 * sections, one switched wire from the common terminal to every stud the switch's
 * {@link RotarySwitchKind} gives it. Exactly one of each section's wires conducts,
 * so turning the handle transfers all three lines at once.
 */
public class RotarySwitchBlockEntity extends ElectricBlockEntity {

    /** Contact resistance of a made contact, in ohms. */
    public static final float CONTACT_RESISTANCE = 0.01f;

    /**
     * How much of the way to the target detent the handle travels each tick.
     *
     * High, because a real rotary switch does not glide: a sprung detent takes the
     * handle the last of the way itself and it arrives with a clack. Detents are 45
     * degrees apart, so at this rate the handle is three quarters of the way there
     * after one tick and lands inside {@link #HANDLE_SNAP_DEGREES} on the third or
     * fourth — about a fifth of a second, fast enough to read as a snap while still
     * leaving the drag gesture something continuous to look at.
     */
    public static final float HANDLE_EASING = 0.75f;

    /**
     * How close to the detent counts as arrived, in degrees. An eased approach never
     * actually gets there, so without this the handle would creep for as long as the
     * switch existed and never stop asking to be redrawn.
     */
    public static final float HANDLE_SNAP_DEGREES = 0.5f;

    /**
     * How loud a detent is. Under the breaker's 0.85 on purpose: a switch handle is
     * a smaller piece of gear than a breaker latch, and the two are levelled against
     * each other in the recordings (see {@code tools/convert_audio.py}), so this is
     * the only place the difference between them is expressed.
     */
    private static final float TURN_VOLUME = 0.7f;

    /** Which detent the handle sits in; 0 is the first marking on the front. */
    private int position;

    /**
     * Front, paint and handle. Per-switch rather than per-block, so one item covers
     * every look and a wrench can re-dress a switch already on the wall. Set in the
     * constructor rather than by an initialiser only because it needs the kind,
     * which is read off the block state.
     */
    private RotarySwitchAppearance appearance;

    /**
     * Per section, one wire from the common to each stud, in the order
     * {@link RotarySwitchKind#studs} lists them.
     *
     * Deliberately declared without an initializer and allocated in
     * {@link #buildCircuit} instead. SmartBlockEntity builds its behaviours from
     * its own constructor, so Power Grid asks for the circuit while this class is
     * still a superclass under construction — before any initializer here has run,
     * and early enough that one would afterwards overwrite whatever the circuit
     * was built with.
     */
    private SwitchedWire[][] contacts;

    public float handleAngle;
    public float prevHandleAngle;

    public RotarySwitchBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROTARY_SWITCH.get(), pos, state);
        appearance = RotarySwitchAppearance.defaultFor(kind());
        position = kind().defaultPosition();
        handleAngle = kind().handleAngle(position);
        prevHandleAngle = handleAngle;
        // Power Grid asked for the circuit from inside super(), before this field
        // existed, so the contacts were made for detent zero whatever this is
        applyContacts();
    }

    /**
     * The whole switch, not the cell it stands in.
     *
     * Everything here is drawn from partials by a block entity renderer, so the
     * inherited box — this block alone — culls the switch as soon as that cell leaves
     * the view frustum, while the contact rack hanging seven and a half pixels
     * through the panel is still plainly in shot. The box comes from the mounted
     * form's footprint at the mount a standalone switch sits on, which is the centre
     * of its own face: one statement of the switch's real extent, shared with the
     * control that declares it to a host block.
     */
    @Override
    protected AABB createRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof RotarySwitchBlock))
            return super.createRenderBoundingBox();
        ControlMount mount = ControlMount.centred(state.getValue(RotarySwitchBlock.FACING),
                state.getValue(RotarySwitchBlock.ROTATION));
        return RotarySwitchControlType.of(kind()).footprint().bounds(mount).move(worldPosition);
    }

    /**
     * A wrench turn spins the switch and keeps this block entity, so the box worked
     * out above is no longer the box the switch occupies.
     */
    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        invalidateRenderBoundingBox();
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        RotarySwitchKind kind = kind();
        int sections = RotarySwitchBlock.SECTIONS.length;
        int studs = kind.studs().length;
        contacts = new SwitchedWire[sections][studs];
        builder.setTerminalCount(kind.terminalCount());
        int made = kind.madeStud(position);
        for (int section = 0; section < sections; section++) {
            int base = section * kind.contactsPerSection();
            IElectricNode common = builder.terminalNode(base + kind.commonIndex());
            for (int stud = 0; stud < studs; stud++)
                contacts[section][stud] = builder.connectSwitch(CONTACT_RESISTANCE, common,
                        builder.terminalNode(base + kind.studIndex(stud)), stud == made);
        }
    }

    /**
     * What kind of switch this is. Read off the block, which is what the
     * items place; falls back to the medium two-position switch for the same reason
     * {@link #createRenderBoundingBox} guards, that this is asked while the circuit
     * is built and a block entity can outlive its block.
     */
    public RotarySwitchKind kind() {
        return getBlockState().getBlock() instanceof RotarySwitchBlock block
                ? block.getKind()
                : RotarySwitchKind.TWO_POSITION;
    }

    public RotarySwitchAppearance appearance() {
        return appearance;
    }

    /** Server-side: re-dress the switch. Nothing electrical or geometric changes. */
    public void setAppearance(RotarySwitchAppearance appearance) {
        this.appearance = appearance.forKind(kind());
        notifyUpdate();
    }

    public int getPosition() {
        return position;
    }

    /**
     * Server-side: push one side of the front and turn the handle that way.
     *
     * Stops at the ends rather than wrapping. A rotary switch has stops in it —
     * there is no way to get from the last detent to the first except back through
     * the middle — and with a direction to the gesture, wrapping would mean a push
     * to the right sometimes throwing the switch hard left.
     *
     * @return whether the handle actually moved, so a caller can keep quiet about a
     *         push against a stop
     */
    public boolean turn(RotarySwitchSide side) {
        int target = Mth.clamp(position + side.detentStep(), 0, kind().positions() - 1);
        if (target == position)
            return false;
        setPosition(target);
        playTurnSound(level, worldPosition);
        return true;
    }

    public void setPosition(int position) {
        this.position = Math.floorMod(position, kind().positions());
        applyContacts();
        notifyUpdate();
    }

    /**
     * The handle dropping into a detent, heard at the switch.
     *
     * Server-side only: this broadcasts to everyone in earshot, so letting the
     * client reach it as well would play every turn twice for whoever turned it.
     * Pitch is left at one -- see {@link ModSounds}.
     *
     * Shared with {@link RotarySwitchControl} rather than written out twice,
     * because a switch screwed into a panel makes the same noise as one placed as a
     * block of its own. It hangs off {@link #turn} rather than
     * {@link #setPosition}, so a push that only ran into a stop stays silent.
     */
    public static void playTurnSound(Level level, BlockPos pos) {
        if (level == null || level.isClientSide)
            return;
        level.playSound(null, pos, ModSounds.ROTARY_SWITCH_TURN.get(), SoundSource.BLOCKS, TURN_VOLUME, 1f);
    }

    /**
     * Point every section's common at whichever stud this position makes.
     *
     * Walked by the built stack's own width rather than by the kind's, because the
     * circuit may have been built while {@link #kind()} was still falling back —
     * see {@link #buildCircuit}. A stack narrower than the kind expects simply
     * leaves its last stud alone instead of running off the end of the array.
     */
    private void applyContacts() {
        if (contacts == null)
            return;
        int made = kind().madeStud(position);
        for (SwitchedWire[] section : contacts)
            for (int stud = 0; stud < section.length; stud++)
                section[stud].setState(stud == made);
    }

    @Override
    public void tick() {
        super.tick();
        prevHandleAngle = handleAngle;
        float target = kind().handleAngle(position);
        handleAngle += (target - handleAngle) * HANDLE_EASING;
        if (Math.abs(target - handleAngle) < HANDLE_SNAP_DEGREES)
            handleAngle = target;
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putInt("Position", position);
        appearance.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        position = Math.floorMod(tag.getInt("Position"), kind().positions());
        appearance = RotarySwitchAppearance.read(tag, kind());
        // The circuit may have been built before this ran, from the position this
        // block entity started at rather than the one it was saved in
        applyContacts();
    }
}
