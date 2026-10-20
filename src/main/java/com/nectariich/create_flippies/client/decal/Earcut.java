package com.nectariich.create_flippies.client.decal;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a polygon with holes into triangles: a port of Mapbox's earcut.
 *
 * Ear clipping rather than anything cleverer because what arrives here is the
 * outline of a flat picture, already cleaned of overlaps by {@code Area}, and the
 * only properties wanted are that every triangle lies inside the shape and that
 * together they cover it. Earcut gives both on the inputs real pictures produce,
 * including the near-degenerate ones a flattened curve is full of, and it falls
 * back to progressively less fussy passes rather than giving up on a polygon that
 * touches itself. The z-order index is what keeps a long outline from going
 * quadratic, and it is only built for outlines long enough to need it.
 *
 * The structure and names follow the original closely on purpose, so that it can
 * be checked against it line for line.
 */
public final class Earcut {

    private Earcut() {}

    /**
     * Triangles covering the polygon, as triples of vertex indices.
     *
     * @param data  x and y of every vertex, the outer ring first and then each hole
     * @param holes the vertex index each hole starts at, in order; empty for none
     */
    public static List<Integer> triangulate(double[] data, int[] holes) {
        List<Integer> triangles = new ArrayList<>();
        boolean hasHoles = holes != null && holes.length > 0;
        int outerLength = hasHoles ? holes[0] * 2 : data.length;
        Node outer = linkedList(data, 0, outerLength, true);
        if (outer == null || outer.next == outer.prev)
            return triangles;
        if (hasHoles)
            outer = eliminateHoles(data, holes, outer);

        double minX = 0;
        double minY = 0;
        double invSize = 0;
        // Hashing only pays for itself on outlines of more than eighty points
        if (data.length > 80 * 2) {
            minX = Double.MAX_VALUE;
            minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i < outerLength; i += 2) {
                minX = Math.min(minX, data[i]);
                minY = Math.min(minY, data[i + 1]);
                maxX = Math.max(maxX, data[i]);
                maxY = Math.max(maxY, data[i + 1]);
            }
            invSize = Math.max(maxX - minX, maxY - minY);
            invSize = invSize != 0 ? 32767 / invSize : 0;
        }
        earcutLinked(outer, triangles, minX, minY, invSize, 0);
        return triangles;
    }

    private static final class Node {
        final int i;
        final double x;
        final double y;
        Node prev;
        Node next;
        int z;
        Node prevZ;
        Node nextZ;
        boolean steiner;

        Node(int i, double x, double y) {
            this.i = i;
            this.x = x;
            this.y = y;
        }
    }

    /** A circular list of the ring's points, wound the way that is asked for. */
    private static Node linkedList(double[] data, int start, int end, boolean clockwise) {
        Node last = null;
        if (clockwise == (signedArea(data, start, end) > 0)) {
            for (int i = start; i < end; i += 2)
                last = insertNode(i, data[i], data[i + 1], last);
        } else {
            for (int i = end - 2; i >= start; i -= 2)
                last = insertNode(i, data[i], data[i + 1], last);
        }
        if (last != null && equals(last, last.next)) {
            removeNode(last);
            last = last.next;
        }
        return last;
    }

    /** Takes out repeated and collinear points, which an ear test cannot cope with. */
    private static Node filterPoints(Node start, Node end) {
        if (start == null)
            return null;
        if (end == null)
            end = start;
        Node p = start;
        boolean again;
        do {
            again = false;
            if (!p.steiner && (equals(p, p.next) || area(p.prev, p, p.next) == 0)) {
                removeNode(p);
                p = end = p.prev;
                if (p == p.next)
                    break;
                again = true;
            } else {
                p = p.next;
            }
        } while (again || p != end);
        return end;
    }

    private static void earcutLinked(Node ear, List<Integer> triangles, double minX, double minY, double invSize,
            int pass) {
        if (ear == null)
            return;
        if (pass == 0 && invSize != 0)
            indexCurve(ear, minX, minY, invSize);

        Node stop = ear;
        while (ear.prev != ear.next) {
            Node prev = ear.prev;
            Node next = ear.next;
            if (invSize != 0 ? isEarHashed(ear, minX, minY, invSize) : isEar(ear)) {
                triangles.add(prev.i / 2);
                triangles.add(ear.i / 2);
                triangles.add(next.i / 2);
                removeNode(ear);
                ear = next.next;
                stop = next.next;
                continue;
            }
            ear = next;
            if (ear == stop) {
                // Out of ears: clean up and try again, then cure small self
                // intersections, then split the polygon in two as a last resort
                if (pass == 0) {
                    earcutLinked(filterPoints(ear, null), triangles, minX, minY, invSize, 1);
                } else if (pass == 1) {
                    ear = cureLocalIntersections(filterPoints(ear, null), triangles);
                    earcutLinked(ear, triangles, minX, minY, invSize, 2);
                } else if (pass == 2) {
                    splitEarcut(ear, triangles, minX, minY, invSize);
                }
                break;
            }
        }
    }

    private static boolean isEar(Node ear) {
        Node a = ear.prev;
        Node b = ear;
        Node c = ear.next;
        if (area(a, b, c) >= 0)
            return false;
        double x0 = Math.min(a.x, Math.min(b.x, c.x));
        double y0 = Math.min(a.y, Math.min(b.y, c.y));
        double x1 = Math.max(a.x, Math.max(b.x, c.x));
        double y1 = Math.max(a.y, Math.max(b.y, c.y));
        Node p = c.next;
        while (p != a) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1
                    && pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && area(p.prev, p, p.next) >= 0)
                return false;
            p = p.next;
        }
        return true;
    }

    private static boolean isEarHashed(Node ear, double minX, double minY, double invSize) {
        Node a = ear.prev;
        Node b = ear;
        Node c = ear.next;
        if (area(a, b, c) >= 0)
            return false;
        double x0 = Math.min(a.x, Math.min(b.x, c.x));
        double y0 = Math.min(a.y, Math.min(b.y, c.y));
        double x1 = Math.max(a.x, Math.max(b.x, c.x));
        double y1 = Math.max(a.y, Math.max(b.y, c.y));
        int minZ = zOrder(x0, y0, minX, minY, invSize);
        int maxZ = zOrder(x1, y1, minX, minY, invSize);

        Node p = ear.prevZ;
        Node n = ear.nextZ;
        while (p != null && p.z >= minZ && n != null && n.z <= maxZ) {
            if (blocks(p, a, b, c, x0, y0, x1, y1))
                return false;
            p = p.prevZ;
            if (blocks(n, a, b, c, x0, y0, x1, y1))
                return false;
            n = n.nextZ;
        }
        while (p != null && p.z >= minZ) {
            if (blocks(p, a, b, c, x0, y0, x1, y1))
                return false;
            p = p.prevZ;
        }
        while (n != null && n.z <= maxZ) {
            if (blocks(n, a, b, c, x0, y0, x1, y1))
                return false;
            n = n.nextZ;
        }
        return true;
    }

    /** Whether a point is a reflex vertex inside the candidate ear, which stops it being one. */
    private static boolean blocks(Node p, Node a, Node b, Node c, double x0, double y0, double x1, double y1) {
        return p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && p != a && p != c
                && pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && area(p.prev, p, p.next) >= 0;
    }

    private static Node cureLocalIntersections(Node start, List<Integer> triangles) {
        Node p = start;
        do {
            Node a = p.prev;
            Node b = p.next.next;
            if (!equals(a, b) && intersects(a, p, p.next, b) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(a.i / 2);
                triangles.add(p.i / 2);
                triangles.add(b.i / 2);
                removeNode(p);
                removeNode(p.next);
                p = start = b;
            }
            p = p.next;
        } while (p != start);
        return filterPoints(p, null);
    }

    private static void splitEarcut(Node start, List<Integer> triangles, double minX, double minY, double invSize) {
        Node a = start;
        do {
            Node b = a.next.next;
            while (b != a.prev) {
                if (a.i != b.i && isValidDiagonal(a, b)) {
                    Node c = splitPolygon(a, b);
                    a = filterPoints(a, a.next);
                    c = filterPoints(c, c.next);
                    earcutLinked(a, triangles, minX, minY, invSize, 0);
                    earcutLinked(c, triangles, minX, minY, invSize, 0);
                    return;
                }
                b = b.next;
            }
            a = a.next;
        } while (a != start);
    }

    private static Node eliminateHoles(double[] data, int[] holes, Node outer) {
        List<Node> queue = new ArrayList<>();
        for (int h = 0; h < holes.length; h++) {
            int start = holes[h] * 2;
            int end = h < holes.length - 1 ? holes[h + 1] * 2 : data.length;
            Node list = linkedList(data, start, end, false);
            if (list == null)
                continue;
            if (list == list.next)
                list.steiner = true;
            queue.add(leftmost(list));
        }
        queue.sort((a, b) -> Double.compare(a.x, b.x));
        for (Node hole : queue)
            outer = eliminateHole(hole, outer);
        return outer;
    }

    private static Node eliminateHole(Node hole, Node outer) {
        Node bridge = findHoleBridge(hole, outer);
        if (bridge == null)
            return outer;
        Node bridgeReverse = splitPolygon(bridge, hole);
        filterPoints(bridgeReverse, bridgeReverse.next);
        return filterPoints(bridge, bridge.next);
    }

    /** The point of the outer ring a hole is joined to: one it can see, to its left. */
    private static Node findHoleBridge(Node hole, Node outer) {
        Node p = outer;
        double hx = hole.x;
        double hy = hole.y;
        double qx = Double.NEGATIVE_INFINITY;
        Node m = null;
        do {
            if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                double x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y);
                if (x <= hx && x > qx) {
                    qx = x;
                    m = p.x < p.next.x ? p : p.next;
                    if (x == hx)
                        return m;
                }
            }
            p = p.next;
        } while (p != outer);
        if (m == null)
            return null;

        Node stop = m;
        double mx = m.x;
        double my = m.y;
        double tanMin = Double.POSITIVE_INFINITY;
        p = m;
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x
                    && pointInTriangle(hy < my ? hx : qx, hy, mx, my, hy < my ? qx : hx, hy, p.x, p.y)) {
                double tan = Math.abs(hy - p.y) / (hx - p.x);
                if (locallyInside(p, hole) && (tan < tanMin
                        || (tan == tanMin && (p.x > m.x || (p.x == m.x && sectorContainsSector(m, p)))))) {
                    m = p;
                    tanMin = tan;
                }
            }
            p = p.next;
        } while (p != stop);
        return m;
    }

    private static boolean sectorContainsSector(Node m, Node p) {
        return area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0;
    }

    private static void indexCurve(Node start, double minX, double minY, double invSize) {
        Node p = start;
        do {
            if (p.z == 0)
                p.z = zOrder(p.x, p.y, minX, minY, invSize);
            p.prevZ = p.prev;
            p.nextZ = p.next;
            p = p.next;
        } while (p != start);
        p.prevZ.nextZ = null;
        p.prevZ = null;
        sortLinked(p);
    }

    /** Merge sort of the z-order links, in place. */
    private static Node sortLinked(Node list) {
        int inSize = 1;
        int merges;
        do {
            Node p = list;
            list = null;
            Node tail = null;
            merges = 0;
            while (p != null) {
                merges++;
                Node q = p;
                int pSize = 0;
                for (int i = 0; i < inSize; i++) {
                    pSize++;
                    q = q.nextZ;
                    if (q == null)
                        break;
                }
                int qSize = inSize;
                while (pSize > 0 || (qSize > 0 && q != null)) {
                    Node e;
                    if (pSize != 0 && (qSize == 0 || q == null || p.z <= q.z)) {
                        e = p;
                        p = p.nextZ;
                        pSize--;
                    } else {
                        e = q;
                        q = q.nextZ;
                        qSize--;
                    }
                    if (tail != null)
                        tail.nextZ = e;
                    else
                        list = e;
                    e.prevZ = tail;
                    tail = e;
                }
                p = q;
            }
            if (tail != null)
                tail.nextZ = null;
            inSize *= 2;
        } while (merges > 1);
        return list;
    }

    /** Interleaves the bits of a point's coordinates, scaled to fifteen bits, into a Morton code. */
    private static int zOrder(double px, double py, double minX, double minY, double invSize) {
        int x = (int) ((px - minX) * invSize);
        int y = (int) ((py - minY) * invSize);
        x = (x | (x << 8)) & 0x00FF00FF;
        x = (x | (x << 4)) & 0x0F0F0F0F;
        x = (x | (x << 2)) & 0x33333333;
        x = (x | (x << 1)) & 0x55555555;
        y = (y | (y << 8)) & 0x00FF00FF;
        y = (y | (y << 4)) & 0x0F0F0F0F;
        y = (y | (y << 2)) & 0x33333333;
        y = (y | (y << 1)) & 0x55555555;
        return x | (y << 1);
    }

    private static Node leftmost(Node start) {
        Node p = start;
        Node leftmost = start;
        do {
            if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y))
                leftmost = p;
            p = p.next;
        } while (p != start);
        return leftmost;
    }

    private static boolean pointInTriangle(double ax, double ay, double bx, double by, double cx, double cy,
            double px, double py) {
        return (cx - px) * (ay - py) >= (ax - px) * (cy - py) && (ax - px) * (by - py) >= (bx - px) * (ay - py)
                && (bx - px) * (cy - py) >= (cx - px) * (by - py);
    }

    private static boolean isValidDiagonal(Node a, Node b) {
        return a.next.i != b.i && a.prev.i != b.i && !intersectsPolygon(a, b)
                && (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b)
                        && (area(a.prev, a, b.prev) != 0 || area(a, b.prev, b) != 0)
                        || equals(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0);
    }

    /** Twice the signed area of a triangle; negative when it turns the way the ears are cut. */
    private static double area(Node p, Node q, Node r) {
        return (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y);
    }

    private static boolean equals(Node a, Node b) {
        return a.x == b.x && a.y == b.y;
    }

    private static boolean intersects(Node p1, Node q1, Node p2, Node q2) {
        int o1 = sign(area(p1, q1, p2));
        int o2 = sign(area(p1, q1, q2));
        int o3 = sign(area(p2, q2, p1));
        int o4 = sign(area(p2, q2, q1));
        if (o1 != o2 && o3 != o4)
            return true;
        if (o1 == 0 && onSegment(p1, p2, q1))
            return true;
        if (o2 == 0 && onSegment(p1, q2, q1))
            return true;
        if (o3 == 0 && onSegment(p2, p1, q2))
            return true;
        return o4 == 0 && onSegment(p2, q1, q2);
    }

    private static boolean onSegment(Node p, Node q, Node r) {
        return q.x <= Math.max(p.x, r.x) && q.x >= Math.min(p.x, r.x) && q.y <= Math.max(p.y, r.y)
                && q.y >= Math.min(p.y, r.y);
    }

    private static int sign(double value) {
        return value > 0 ? 1 : value < 0 ? -1 : 0;
    }

    private static boolean intersectsPolygon(Node a, Node b) {
        Node p = a;
        do {
            if (p.i != a.i && p.next.i != a.i && p.i != b.i && p.next.i != b.i && intersects(p, p.next, a, b))
                return true;
            p = p.next;
        } while (p != a);
        return false;
    }

    private static boolean locallyInside(Node a, Node b) {
        return area(a.prev, a, a.next) < 0 ? area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0
                : area(a, b, a.prev) < 0 || area(a, a.next, b) < 0;
    }

    private static boolean middleInside(Node a, Node b) {
        Node p = a;
        boolean inside = false;
        double px = (a.x + b.x) / 2;
        double py = (a.y + b.y) / 2;
        do {
            if (((p.y > py) != (p.next.y > py)) && p.next.y != p.y
                    && (px < (p.next.x - p.x) * (py - p.y) / (p.next.y - p.y) + p.x))
                inside = !inside;
            p = p.next;
        } while (p != a);
        return inside;
    }

    /** Joins two points of a ring with a doubled edge, splitting it in two; returns the new copy of b. */
    private static Node splitPolygon(Node a, Node b) {
        Node a2 = new Node(a.i, a.x, a.y);
        Node b2 = new Node(b.i, b.x, b.y);
        Node an = a.next;
        Node bp = b.prev;
        a.next = b;
        b.prev = a;
        a2.next = an;
        an.prev = a2;
        b2.next = a2;
        a2.prev = b2;
        bp.next = b2;
        b2.prev = bp;
        return b2;
    }

    private static Node insertNode(int i, double x, double y, Node last) {
        Node p = new Node(i, x, y);
        if (last == null) {
            p.prev = p;
            p.next = p;
        } else {
            p.next = last.next;
            p.prev = last;
            last.next.prev = p;
            last.next = p;
        }
        return p;
    }

    private static void removeNode(Node p) {
        p.next.prev = p.prev;
        p.prev.next = p.next;
        if (p.prevZ != null)
            p.prevZ.nextZ = p.nextZ;
        if (p.nextZ != null)
            p.nextZ.prevZ = p.prevZ;
    }

    private static double signedArea(double[] data, int start, int end) {
        double sum = 0;
        for (int i = start, j = end - 2; i < end; i += 2) {
            sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1]);
            j = i;
        }
        return sum;
    }
}
