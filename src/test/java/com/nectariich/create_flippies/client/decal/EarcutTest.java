package com.nectariich.create_flippies.client.decal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class EarcutTest {

    private static double area(double[] data, List<Integer> triangles) {
        double sum = 0;
        for (int i = 0; i < triangles.size(); i += 3) {
            int a = triangles.get(i) * 2;
            int b = triangles.get(i + 1) * 2;
            int c = triangles.get(i + 2) * 2;
            sum += Math.abs((data[b] - data[a]) * (data[c + 1] - data[a + 1])
                    - (data[c] - data[a]) * (data[b + 1] - data[a + 1])) / 2;
        }
        return sum;
    }

    @Test
    void aSquareIsTwoTriangles() {
        double[] square = { 0, 0, 1, 0, 1, 1, 0, 1 };
        List<Integer> triangles = Earcut.triangulate(square, new int[0]);
        assertEquals(6, triangles.size());
        assertEquals(1, area(square, triangles), 1e-12);
    }

    @Test
    void aHoleIsLeftOut() {
        double[] framed = { 0, 0, 4, 0, 4, 4, 0, 4, 1, 1, 3, 1, 3, 3, 1, 3 };
        List<Integer> triangles = Earcut.triangulate(framed, new int[] { 4 });
        assertEquals(16 - 4, area(framed, triangles), 1e-12);
    }

    @Test
    void aLongConcaveOutlineIsCoveredExactly() {
        // A comb: long enough to take the hashed path, concave everywhere
        int teeth = 60;
        double[] comb = new double[(teeth * 4 + 2) * 2];
        int at = 0;
        for (int i = 0; i < teeth; i++) {
            comb[at++] = i * 2;
            comb[at++] = 0;
            comb[at++] = i * 2 + 1;
            comb[at++] = 0;
            comb[at++] = i * 2 + 1;
            comb[at++] = -5;
            comb[at++] = i * 2 + 2;
            comb[at++] = -5;
        }
        comb[at++] = teeth * 2;
        comb[at++] = 1;
        comb[at++] = 0;
        comb[at] = 1;
        // Teeth one wide and five deep, every other one, plus the spine
        double expected = teeth * 5 + teeth * 2;
        assertEquals(expected, area(comb, Earcut.triangulate(comb, new int[0])), 1e-9);
    }
}
