package com.nectariich.create_flippies.content.rotary_switch;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.base.terminals.BlockStateTerminalCollection;

/**
 * A panel-mounted rotary switch: three independent sections, each breaking one
 * line, turned together by one handle.
 *
 * Every section has a common contact riding over the studs its
 * {@link RotarySwitchKind} gives it, and at any detent it is on exactly one of
 * them, so each of the three lines is made through exactly one stud. The
 * two-position switch transfers between a normally-closed and a normally-open
 * stud, nine terminals in all; the three-position switches select between an
 * anticlockwise, a neutral and a clockwise stud, twelve. They run down the back of
 * the rack, section A nearest the panel.
 *
 * The model is authored pointing at the ground, front plate flush with the floor
 * of its own cell and the contact rack hanging out below it: the convention
 * {@link SurfaceMount} describes, and the same trick the Square gauges pull. The
 * switch sits on the front of a panel and its contacts come through to the back,
 * so it is wired from behind. Geometry outside a block's own space is invisible
 * to vanilla ray tracing, so {@link IProtrudingHitbox} is what gives those
 * contacts a hitbox at all, and
 * {@link com.nectariich.create_flippies.foundation.ProtrudingTerminalInteraction}
 * is what lands a wire clicked onto one on the terminal rather than in mid-air.
 *
 * Body and handle are both drawn by
 * {@link com.nectariich.create_flippies.client.RotarySwitchRenderer} rather than
 * baked into the block state. A block state variant can only rotate a model about
 * X and then Y, which reaches 16 of the 24 orientations and misses exactly the
 * ones a wall-mounted switch would need to sit sideways; and the handle detents
 * are 45 degrees apart, which a variant cannot express at all. What is drawn is
 * the block entity's {@link RotarySwitchAppearance}, not anything the block knows:
 * one block covers every front, paint and handle its {@link RotarySwitchKind} can
 * wear.
 *
 * Wrenching (non-sneak) opens that appearance menu instead of turning the switch
 * in its panel, the way the procedural gauges' wrench opens their dial menu. The
 * quarter turn is not lost — it moves into the menu, where the choice is at least
 * visible while it is being made.
 */
public class RotarySwitchBlock extends ElectricBlock
        implements IBE<RotarySwitchBlockEntity>, IProtrudingHitbox, ConfigurableBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    /** Sections in the order they sit on the rack, A nearest the panel. */
    public static final String[] SECTIONS = RotarySwitchKind.SECTIONS;

    // These mirror the authored model and have to be updated with it. The front is
    // the one part of it that differs between switches, and belongs to the size.

    /** The neck that carries the rack clear of the panel. */
    public static final double[] NECK = { 6.5, -1.5, 6.5, 9.5, 0, 9.5 };
    /** The rack the contacts stand on, held off the panel by the neck. */
    public static final double[] RACK = { 6.5, -7.5, 6.5, 9.5, -1.5, 9.5 };

    /** Authored y of each section's studs, in rack order. */
    private static final double[] SECTION_Y = { -5.05, -6.15, -7.25 };
    private static final double STUD_HEIGHT = 0.5;
    /** Half a stud across the rack's face, either side of the z its contact names. */
    private static final double STUD_HALF = 0.25;

    /**
     * The contact boxes are grown from the studs the model draws. The authored
     * studs are a quarter pixel proud of the rack and half a pixel square, and
     * Power Grid resolves a terminal from the exact spot clicked, so a target that
     * small is one nobody can hit. Each box reaches half a pixel further out from
     * the rack and a little wider, which still leaves a fifth of a pixel of
     * daylight between the three sections.
     */
    private static final double REACH = 0.5;
    private static final double PAD_Y = 0.2;
    private static final double PAD_Z = 0.25;

    /**
     * The authored contact geometry, shared with
     * {@link RotarySwitchControl} so that a switch mounted inside a host block has
     * contacts in the same places as one placed as a block of its own. Read only;
     * nothing rotates these in place.
     *
     * One entry per kind, because how many studs a section carries is the kind's to
     * say: a two-position switch transfers between two of them and a three-position
     * one selects between three. Where a stud stands is not — every switch draws its
     * rack from the same boxes — so the tables differ only in how many columns they
     * have.
     */
    private static final Map<RotarySwitchKind, double[][]> CONTACT_BOXES =
            perKind(RotarySwitchBlock::buildContactBoxes);
    private static final Map<RotarySwitchKind, double[][]> CONTACT_ORIGINS =
            perKind(RotarySwitchBlock::buildContactOrigins);
    private static final Map<RotarySwitchKind, Component[]> CONTACT_NAMES =
            perKind(RotarySwitchBlock::buildContactNames);
    private static final Map<RotarySwitchKind, int[]> CONTACT_COLORS =
            perKind(RotarySwitchBlock::buildContactColors);

    /** Everything the crosshair may pick: plate, handle, neck, rack, every contact. */
    private static final Map<RotarySwitchKind, double[][]> OUTLINE_BOXES =
            perKind(RotarySwitchBlock::buildOutlineBoxes);

    /**
     * Only the plate and handle collide. The rack and contacts stand in the
     * neighbouring block, where collision would block building out there. Rotation
     * cannot change this shape — the plate is square about the mounting axis — so
     * one entry per facing and size is the whole table.
     */
    private static final Map<RotarySwitchSize, Map<Direction, VoxelShape>> COLLISION = collisionShapes();

    private final RotarySwitchKind kind;

    public RotarySwitchBlock(RotarySwitchKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN).setValue(ROTATION, 0));
        setTerminalCollection(BlockStateTerminalCollection.builder(this)
                .forAllStates(RotarySwitchBlock::terminalsFor)
                .withShapeMapper(RotarySwitchBlock::outlineFor)
                .build());
    }

    public RotarySwitchKind getKind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROTATION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getOpposite();
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(ROTATION, SurfaceMount.rotationForPlacement(facing, context.getHorizontalDirection()));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION.get(kind.size()).get(state.getValue(FACING));
    }

    /**
     * A turn spins the switch in its panel; re-aiming it means placing it again.
     * Asked for by the appearance menu and by Create's own wrench, both of which come
     * through here because this is the block's own definition of what turning it
     * means.
     */
    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.cycle(ROTATION);
    }

    /**
     * Spins the switch a quarter turn, server-side. This is what Create's own
     * wrench rotation reduces to for a block that is not kinetic: set the state and
     * play the sound. The block entity survives it, because only the state changes,
     * so the switch keeps its detent and its appearance across a turn.
     */
    public static void turn(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RotarySwitchBlock block))
            return;
        level.setBlock(pos, block.getRotatedBlockState(state, state.getValue(FACING)), Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
    }

    /**
     * The appearance menu, opened by a sneaking right click with a screwdriver like
     * every other control's; see {@link ControlConfig}. The wrench is left to Create,
     * which turns the switch and, sneaking, dismantles it into the player's
     * inventory — and the plain click stays what it has to be, which is the push that
     * turns the handle.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof RotarySwitchBlockEntity switchEntity))
            return false;
        if (level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.nectariich.create_flippies.client.RotarySwitchScreen.open(switchEntity));
        return true;
    }

    /**
     * A push on one half of the front turns the switch that way; see
     * {@link RotarySwitchSide}. Nothing here toggles, not even the two-position
     * switch — the gesture has a direction because the switch does.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        // A click on a contact is a wire connection, not a turn of the handle
        if (terminalAt(state, local) != null)
            return InteractionResult.PASS;
        // Leave sneaking and the wrench to the menu and to dismantling
        if (player.isShiftKeyDown() || AllItems.WRENCH.isIn(player.getItemInHand(hand)))
            return InteractionResult.PASS;

        RotarySwitchSide side = RotarySwitchAim.sideAt(local, state.getValue(FACING), state.getValue(ROTATION));
        // The back of the panel: the rack is wired from there, not operated
        if (side == null)
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        // turn() is what makes the noise, so a push into a stop is silent
        withBlockEntityDo(level, pos, rotary -> rotary.turn(side));
        return InteractionResult.CONSUME;
    }

    @Override
    public Class<RotarySwitchBlockEntity> getBlockEntityClass() {
        return RotarySwitchBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RotarySwitchBlockEntity> getBlockEntityType() {
        return ModBlockEntities.ROTARY_SWITCH.get();
    }

    /**
     * The contacts of the switch this state is, read off its own block: the state
     * carries the facing and the quarter turn, and the block it belongs to carries
     * the kind whose table those are applied to.
     */
    private static TerminalBoundingBox[] terminalsFor(BlockState state) {
        RotarySwitchKind kind = kindOf(state);
        Direction facing = state.getValue(FACING);
        int rotation = state.getValue(ROTATION);
        double[][] boxes = contactBoxes(kind);
        double[][] origins = contactOrigins(kind);
        Component[] names = contactNames(kind);
        int[] colors = contactColors(kind);
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[boxes.length];
        for (int i = 0; i < terminals.length; i++) {
            double[] b = SurfaceMount.box(boxes[i], facing, rotation);
            double[] o = SurfaceMount.point(origins[i][0], origins[i][1], origins[i][2], facing, rotation);
            terminals[i] = new TerminalBoundingBox(names[i], b[0], b[1], b[2], b[3], b[4], b[5])
                    .withColor(colors[i])
                    .withOrigin(o[0], o[1], o[2]);
        }
        return terminals;
    }

    private static VoxelShape outlineFor(BlockState state) {
        return SurfaceMount.shape(OUTLINE_BOXES.get(kindOf(state)), state.getValue(FACING), state.getValue(ROTATION));
    }

    /**
     * The kind of a state that is one of these blocks. Only a block's own terminal
     * collection and its own shape mapper ask, so it always is one.
     */
    private static RotarySwitchKind kindOf(BlockState state) {
        return state.getBlock() instanceof RotarySwitchBlock block ? block.kind : RotarySwitchKind.TWO_POSITION;
    }

    /** The contact boxes of one kind of switch, section-major, common first. */
    public static double[][] contactBoxes(RotarySwitchKind kind) {
        return CONTACT_BOXES.get(kind);
    }

    /** Where a wire lands on each of them, in the same order. */
    public static double[][] contactOrigins(RotarySwitchKind kind) {
        return CONTACT_ORIGINS.get(kind);
    }

    public static Component[] contactNames(RotarySwitchKind kind) {
        return CONTACT_NAMES.get(kind);
    }

    public static int[] contactColors(RotarySwitchKind kind) {
        return CONTACT_COLORS.get(kind);
    }

    /** One table per kind, worked out once and read by both forms of the switch. */
    private static <T> Map<RotarySwitchKind, T> perKind(Function<RotarySwitchKind, T> table) {
        Map<RotarySwitchKind, T> tables = new EnumMap<>(RotarySwitchKind.class);
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            tables.put(kind, table.apply(kind));
        return Collections.unmodifiableMap(tables);
    }

    /**
     * One box per contact, section-major and common first within a section. The
     * commons stand alone on the rack's -x face; the studs share the +x face,
     * spread across it in the order {@link RotarySwitchKind#studs} lists them,
     * which is the order the model draws them in.
     *
     * The authored studs are half a pixel square about the z each contact names,
     * hence the quarter pixel either side of it before the padding.
     */
    private static double[][] buildContactBoxes(RotarySwitchKind kind) {
        double[][] boxes = new double[kind.terminalCount()][];
        double commonZ = RotarySwitchContact.COMMON.z();
        for (int section = 0; section < SECTIONS.length; section++) {
            double minY = SECTION_Y[section] - PAD_Y;
            double maxY = SECTION_Y[section] + STUD_HEIGHT + PAD_Y;
            int base = section * kind.contactsPerSection();
            boxes[base + kind.commonIndex()] = new double[] { 6.25 - REACH, minY, commonZ - STUD_HALF - PAD_Z, 6.5,
                    maxY, commonZ + STUD_HALF + PAD_Z };
            for (int stud = 0; stud < kind.studs().length; stud++) {
                double z = kind.studs()[stud].z();
                boxes[base + kind.studIndex(stud)] = new double[] { 9.5, minY, z - STUD_HALF - PAD_Z, 9.75 + REACH,
                        maxY, z + STUD_HALF + PAD_Z };
            }
        }
        return boxes;
    }

    /**
     * Where a wire should visually land on each contact: the centre of the stud's
     * outward face, as the model draws it.
     *
     * This has to be stated rather than left to Power Grid, because a
     * TerminalBoundingBox derives its origin from the midpoint of the box it was
     * given — and the boxes above are grown outwards on one side only, which drags
     * that midpoint half of REACH off the stud. With REACH at half a pixel the
     * wire would attach an eighth of a pixel beyond the stud's tip, in open air,
     * which is exactly the "wire does not reach the contact" look.
     *
     * Wire positions are re-derived from the terminal on every load rather than
     * stored, so correcting the origin also moves wires that are already placed.
     */
    private static double[][] buildContactOrigins(RotarySwitchKind kind) {
        double[][] origins = new double[kind.terminalCount()][];
        for (int section = 0; section < SECTIONS.length; section++) {
            double midY = SECTION_Y[section] + STUD_HEIGHT / 2;
            int base = section * kind.contactsPerSection();
            // Commons stand on the rack's -x face, so their tips point at -x
            origins[base + kind.commonIndex()] = new double[] { 6.25, midY, RotarySwitchContact.COMMON.z() };
            // The studs share the +x face, spread across it in rack order
            for (int stud = 0; stud < kind.studs().length; stud++)
                origins[base + kind.studIndex(stud)] = new double[] { 9.75, midY, kind.studs()[stud].z() };
        }
        return origins;
    }

    private static Component[] buildContactNames(RotarySwitchKind kind) {
        Component[] names = new Component[kind.terminalCount()];
        for (int section = 0; section < SECTIONS.length; section++) {
            String letter = SECTIONS[section];
            int base = section * kind.contactsPerSection();
            names[base + kind.commonIndex()] = RotarySwitchContact.COMMON.name(letter);
            for (int stud = 0; stud < kind.studs().length; stud++)
                names[base + kind.studIndex(stud)] = kind.studs()[stud].name(letter);
        }
        return names;
    }

    private static int[] buildContactColors(RotarySwitchKind kind) {
        int[] colors = new int[kind.terminalCount()];
        for (int section = 0; section < SECTIONS.length; section++) {
            int base = section * kind.contactsPerSection();
            colors[base + kind.commonIndex()] = RotarySwitchContact.COMMON.color();
            for (int stud = 0; stud < kind.studs().length; stud++)
                colors[base + kind.studIndex(stud)] = kind.studs()[stud].color();
        }
        return colors;
    }

    private static double[][] buildOutlineBoxes(RotarySwitchKind kind) {
        double[][] contacts = buildContactBoxes(kind);
        double[][] boxes = new double[contacts.length + 3][];
        boxes[0] = kind.size().front();
        boxes[1] = NECK;
        boxes[2] = RACK;
        System.arraycopy(contacts, 0, boxes, 3, contacts.length);
        return boxes;
    }

    private static Map<RotarySwitchSize, Map<Direction, VoxelShape>> collisionShapes() {
        Map<RotarySwitchSize, Map<Direction, VoxelShape>> sizes = new EnumMap<>(RotarySwitchSize.class);
        for (RotarySwitchSize size : RotarySwitchSize.values()) {
            Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
            for (Direction facing : Direction.values())
                shapes.put(facing, SurfaceMount.shape(new double[][] { size.front() }, facing, 0));
            sizes.put(size, Collections.unmodifiableMap(shapes));
        }
        return Collections.unmodifiableMap(sizes);
    }
}
