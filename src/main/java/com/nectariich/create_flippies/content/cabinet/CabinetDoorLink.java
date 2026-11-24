package com.nectariich.create_flippies.content.cabinet;

import java.util.UUID;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What ties a control host to a cabinet's door: which cabinet, and how a point
 * where the door stands open is carried to where it stands shut.
 *
 * Controls on a door are hosted, electrically and in every other way, where they
 * are when the door is open. That is where they are wired (Power Grid finds a
 * terminal by the block it belongs to, and re-picks the click on the server), so
 * that is where they have to stay. Shut, the same controls are drawn and clicked
 * on the shut door through this transform, and their host is empty space.
 *
 * Both poses of a door are the same slab turned about its hinge, and a cabinet only
 * ever faces one of the four horizontal directions, so the transform is a whole
 * number of quarter turns about the vertical plus a shift:
 * {@code shut(p) = shutOrigin + R(p - openOrigin)}, where the origins are the
 * same corner of the door in the two poses. {@code R} turns a quarter
 * {@code turns} times, each quarter the one {@code PoseStack.mulPose(Axis.YP.rotationDegrees(90))}
 * makes: {@code (x, z) -> (z, -x)}.
 */
public record CabinetDoorLink(UUID cabinet, int turns, Vec3 openOrigin, Vec3 shutOrigin) {

    public CabinetDoorLink {
        turns = Math.floorMod(turns, 4);
    }

    /** The link for a door of {@code cabinet}. */
    public static CabinetDoorLink of(ElectricalCabinetEntity cabinet) {
        return of(cabinet.getUUID(), cabinet.spec(), cabinet::toWorld);
    }

    /** The link for the door of a cabinet {@code id} of {@code spec}, its model px put in the world by {@code toWorld}. */
    public static CabinetDoorLink of(UUID id, CabinetSpec spec, java.util.function.UnaryOperator<Vec3> toWorld) {
        CabinetWall.Frame open = CabinetWall.DOOR.frame(spec, true), shut = CabinetWall.DOOR.frame(spec, false);
        Vec3 openOrigin = toWorld.apply(open.origin()), shutOrigin = toWorld.apply(shut.origin());
        Vec3 openU = toWorld.apply(open.origin().add(open.u())).subtract(openOrigin);
        Vec3 shutU = toWorld.apply(shut.origin().add(shut.u())).subtract(shutOrigin);
        int turns = 0;
        for (int k = 0; k < 4; k++)
            if (turn(openU, k).subtract(shutU).lengthSqr() < 1e-6 * shutU.lengthSqr()) {
                turns = k;
                break;
            }
        return new CabinetDoorLink(id, turns, openOrigin, shutOrigin);
    }

    /** {@code v} turned {@code k} quarter turns, each {@code (x, z) -> (z, -x)}. */
    public static Vec3 turn(Vec3 v, int k) {
        double x = v.x, z = v.z;
        for (int i = 0; i < Math.floorMod(k, 4); i++) {
            double t = x;
            x = z;
            z = -t;
        }
        return new Vec3(x, v.y, z);
    }

    /** A world point of the open door where it is with the door shut. */
    public Vec3 toShut(Vec3 open) {
        return shutOrigin.add(turn(open.subtract(openOrigin), turns));
    }

    /** A world point of the shut door where it is with the door open. */
    public Vec3 toOpen(Vec3 shut) {
        return openOrigin.add(turn(shut.subtract(shutOrigin), -turns));
    }

    /** A direction of the open door as it points with the door shut. */
    public Vec3 directionToShut(Vec3 direction) {
        return turn(direction, turns);
    }

    /** A direction of the shut door as it points with the door open. */
    public Vec3 directionToOpen(Vec3 direction) {
        return turn(direction, -turns);
    }

    public Direction directionToOpen(Direction direction) {
        Vec3 d = directionToOpen(Vec3.atLowerCornerOf(direction.getNormal()));
        return Direction.getNearest(d.x, d.y, d.z);
    }

    /** The box round {@code box} carried to the shut door. */
    public AABB toShut(AABB box) {
        Vec3 a = toShut(new Vec3(box.minX, box.minY, box.minZ)), b = toShut(new Vec3(box.maxX, box.maxY, box.maxZ));
        return new AABB(a, b);
    }

    /** The turn, degrees about +y, a pose stack makes to draw the open door's things shut. */
    public float degrees() {
        return 90f * turns;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Cabinet", cabinet);
        tag.putByte("Turns", (byte) turns);
        tag.putDouble("OX", openOrigin.x);
        tag.putDouble("OY", openOrigin.y);
        tag.putDouble("OZ", openOrigin.z);
        tag.putDouble("SX", shutOrigin.x);
        tag.putDouble("SY", shutOrigin.y);
        tag.putDouble("SZ", shutOrigin.z);
        return tag;
    }

    public static CabinetDoorLink load(CompoundTag tag) {
        if (!tag.hasUUID("Cabinet"))
            return null;
        return new CabinetDoorLink(tag.getUUID("Cabinet"), tag.getByte("Turns"),
                new Vec3(tag.getDouble("OX"), tag.getDouble("OY"), tag.getDouble("OZ")),
                new Vec3(tag.getDouble("SX"), tag.getDouble("SY"), tag.getDouble("SZ")));
    }
}
