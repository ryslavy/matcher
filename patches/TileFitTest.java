package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** AppAdapter.tileWidthPx with rows_below_dock: the dock, its title and all requested rows fit on 1080p. */
public class TileFitTest {

    private static final float D = 2f;              // 1080p TV: 2 px per dp
    private static final int INNER = 1696;          // grid width minus padding at 1920 px
    private static final int GRID_H = 846;          // grid height under the top bar

    private static int height(int tileW, int rows, boolean title) {
        int tileH = AppAdapter.tileHeightPx(tileW);
        int itemH = AppAdapter.itemHeightPx(tileH, true, rows, D);
        int dockRowH = rows >= 2
                ? tileH + Math.round((8f + AppAdapter.COMPACT_DOCK_EXTRA_DP) * D)
                : itemH + Math.round(AppAdapter.DOCK_EXTRA_DP * D);
        float bottom = AppAdapter.bottomRoomDp(true, rows);
        float titleRoom = title ? AppAdapter.titleRoomDp(rows) : 0f;
        return dockRowH + itemH * rows + Math.round((bottom + titleRoom) * D);
    }

    @Test
    public void noRowsBelowDockKeepsTheWidthOnlySize() {
        int widthOnly = AppAdapter.tileWidthPx(INNER, 4, D);
        assertEquals(widthOnly, AppAdapter.tileWidthPx(INNER, GRID_H, 4, 0, true, true, D));
        assertEquals(widthOnly, AppAdapter.tileWidthPx(INNER, 0, 4, 3, true, true, D));   // not laid out yet
    }

    @Test
    public void allSixteenColumnAndRowCombinationsFitOn1080p() {
        int minTileW = Math.round(80 * D);
        for (int cols = 4; cols <= 7; cols++) {
            for (int rows = 1; rows <= 3; rows++) {
                for (boolean title : new boolean[]{true, false}) {
                    int w = AppAdapter.tileWidthPx(INNER, GRID_H, cols, rows, title, true, D);
                    assertTrue("cols=" + cols + " rows=" + rows + " below 80dp floor", w >= minTileW);
                    assertTrue("cols=" + cols + " rows=" + rows + " title=" + title
                                    + " height=" + height(w, rows, title) + " > " + GRID_H,
                            height(w, rows, title) <= GRID_H + 14);
                }
            }
        }
    }

    @Test
    public void oneRowUnderATitledDockFits() {
        int w = AppAdapter.tileWidthPx(INNER, GRID_H, 4, 1, true, true, D);
        assertTrue(w < AppAdapter.tileWidthPx(INNER, 4, D));
        assertTrue(height(w, 1, true) <= GRID_H + 2);
    }

    @Test
    public void plainGridFitsFourRowsAtSixColumns() {
        // dock_row false, rows_below_dock 3: the first row and 3 more under a 12dp top margin.
        int w = AppAdapter.tileWidthPx(INNER, GRID_H, 6, 3, true, false, D);
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(w), false, D);
        int needed = itemH * 4 + Math.round((AppAdapter.TOP_ROOM_DP + AppAdapter.COMPACT_BOTTOM_ROOM_DP) * D);
        assertTrue(needed + " > " + GRID_H, needed <= GRID_H + 2);
        // With the compact spacing the width limit decides, not the height: no extra whitespace between tiles.
        assertEquals(AppAdapter.tileWidthPx(INNER, 6, D), w);
    }

    @Test
    public void droppingTheTitleGivesBiggerTiles() {
        assertTrue(AppAdapter.tileWidthPx(INNER, GRID_H, 4, 1, false, true, D)
                > AppAdapter.tileWidthPx(INNER, GRID_H, 4, 1, true, true, D));
    }
}
