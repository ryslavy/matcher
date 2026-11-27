package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure JVM tests for the Android 17 Expressive frosted-glass color math and adaptive folder layouts. */
public class ExpressiveGlassTest {

    @Test
    public void aospBackdropSigmaMatchesOneOverSqrt3Conversion() {
        // 34dp at density=2.0 -> 68 * 0.57735 = 39.26 -> clamped cleanly to 38px for Mali GPU budget.
        assertEquals(38f, ExpressiveGlass.backdropSigmaPx(2.0f, ExpressiveGlass.SHADE_BLUR_RADIUS_DP), 0.01f);
        // 28dp nested Material 3 Expressive blur at density=2.0 -> 56 * 0.57735 = 32.33px.
        assertEquals(32.331f, ExpressiveGlass.backdropSigmaPx(2.0f, ExpressiveGlass.NESTED_BLUR_RADIUS_DP), 0.05f);
        // 23dp at density=2.0 -> 46 * 0.57735 = 26.56px.
        assertEquals(26.558f, ExpressiveGlass.backdropSigmaPx(2.0f, ExpressiveGlass.DIALOG_BLUR_RADIUS_DP), 0.05f);
        // Very small density clamps at 12px minimum.
        assertEquals(12f, ExpressiveGlass.backdropSigmaPx(0.5f, 10f), 0.01f);
    }

    @Test
    public void extractSeedRgbFallsBackToAospSurfaceEffect0ForNeutralImages() {
        int[] grey = new int[400];
        for (int i = 0; i < grey.length; i++) grey[i] = 0xFF333333;
        assertEquals(ExpressiveGlass.DEFAULT_SEED_RGB, ExpressiveGlass.extractSeedRgb(grey));
        assertEquals(ExpressiveGlass.DEFAULT_SEED_RGB, ExpressiveGlass.extractSeedRgb(null));
    }

    @Test
    public void extractSeedRgbTracksDominantWallpaperHue() {
        int[] sunset = new int[500];
        for (int i = 0; i < sunset.length; i++) sunset[i] = 0xFFD97228;
        int seed = ExpressiveGlass.extractSeedRgb(sunset);
        int r = (seed >> 16) & 0xFF, g = (seed >> 8) & 0xFF, b = seed & 0xFF;
        assertTrue("Warm sunset seed should be red-dominant", r > g && g > b);

        int tonal = ExpressiveGlass.tonalAccent800(seed);
        float[] hsv = new float[3];
        ExpressiveGlass.rgbToHsv((tonal >> 16) & 0xFF, (tonal >> 8) & 0xFF, tonal & 0xFF, hsv);
        assertTrue("Material 3 accent1_800 must stay deep dark tone", hsv[2] >= 0.20f && hsv[2] <= 0.33f);
        assertTrue("Material 3 accent1_800 must stay refined saturation", hsv[1] >= 0.18f && hsv[1] <= 0.46f);
    }

    @Test
    public void refineFocusAccentTamesNeonColorsIntoOpticalGlass() {
        // KICK neon green (#53FC18): saturation 90%+ must be tamed into refined optical crystal.
        int refined = ExpressiveGlass.refineFocusAccent(0xFF53FC18);
        float[] hsv = new float[3];
        ExpressiveGlass.rgbToHsv((refined >> 16) & 0xFF, (refined >> 8) & 0xFF, refined & 0xFF, hsv);
        assertTrue("Refined highlight should remain in green hue family", hsv[0] >= 80f && hsv[0] <= 155f);
        assertTrue("Refined highlight must not stay garish neon saturation: " + hsv[1], hsv[1] <= 0.56f);
        assertTrue("Refined highlight must stay luminous for Fresnel catchlight", hsv[2] >= 0.72f);
    }

    @Test
    public void panelAndDockGlassColorsPreserveExpectedTranslucency() {
        int[] overHome = ExpressiveGlass.panelGlassColors(0x2E7D6A, false);
        int[] frostedOverSharp = ExpressiveGlass.panelGlassColors(0x2E7D6A, false, true);
        int[] smoked = ExpressiveGlass.panelGlassColors(0x2E7D6A, true);
        int[] dock = ExpressiveGlass.dockGlassColors(0x2E7D6A);
        assertEquals(3, overHome.length);
        assertEquals(3, frostedOverSharp.length);
        assertEquals(3, smoked.length);
        assertEquals(3, dock.length);
        assertTrue("Frosted-over-sharp panel must be richer than blurred-backdrop Home panel",
                ((frostedOverSharp[0] >>> 24) & 0xFF) > ((overHome[0] >>> 24) & 0xFF));
        assertTrue("Smoked panel over external apps must remain denser than frosted-over-sharp panel",
                ((smoked[0] >>> 24) & 0xFF) > ((frostedOverSharp[0] >>> 24) & 0xFF));
        assertTrue("Dock glass must remain more translucent than modal panels",
                ((dock[1] >>> 24) & 0xFF) < ((overHome[1] >>> 24) & 0xFF));
    }

    @Test
    public void adaptiveFolderCellBoundsCenterCleanlyForEveryCount() {
        final int w = 360, h = 202;
        for (int n = 1; n <= 6; n++) {
            float[][] cells = AppAdapter.folderCellBounds(n, w, h);
            assertEquals(n, cells.length);
            for (float[] c : cells) {
                assertTrue("Left in bounds for n=" + n, c[0] > 0f && c[0] + c[2] < w);
                assertTrue("Top in bounds for n=" + n, c[1] > 0f && c[1] + c[3] < h);
            }
        }
        // 2-app folder (e.g. KICK + Twitch) must be vertically centered side-by-side with larger previews.
        float[][] two = AppAdapter.folderCellBounds(2, w, h);
        assertEquals("Two apps share the exact same vertical center", two[0][1], two[1][1], 0.01f);
        float centerY = two[0][1] + two[0][3] / 2f;
        assertEquals("Two apps are vertically centered in the folder", h / 2f, centerY, 1.0f);
        float leftMargin = two[0][0];
        float rightMargin = w - (two[1][0] + two[1][2]);
        assertEquals("Two apps are horizontally symmetric", leftMargin, rightMargin, 1.0f);
        assertTrue("Two-app tiles are substantially larger than 3x2 mini-cells", two[0][2] >= 135f);
    }

    @Test
    public void liquidGlass2ShaderContainsRippleUniformsAndPremulSafety() {
        String agsl = ExpressiveGlass.LIQUID_GLASS_PANEL_AGSL;
        assertTrue("Shader declares uWobble", agsl.contains("uniform float uWobble;"));
        assertTrue("Shader declares uPhase", agsl.contains("uniform float uPhase;"));
        assertTrue("Shader includes non-linear vibrancy", agsl.contains("applyVibrancyAndAdapt"));
        assertTrue("Shader clamps premul RGB <= outA", agsl.contains("half3(outA))"));
    }

    @Test
    public void computeScreenOriginNeverDoubleCountsFloatingWindowOffset() {
        float[] out = new float[2];
        // Case 1: Android 14 floating Dialog where ViewRootImpl.transformMatrixToGlobal ALREADY adds winOff (482, 186)
        // and panel sits at local (48, 48) inside the Dialog window -> hostTrans = (530, 234), rootTrans = (482, 186).
        ExpressiveGlass.computeScreenOrigin(530f, 234f, 482f, 186f, 482, 186, out);
        assertEquals("Must not double-count winOffX", 530f, out[0], 0.01f);
        assertEquals("Must not double-count winOffY", 234f, out[1], 0.01f);

        // Case 2: Frame 0 before WindowManager reports window offset -> rootTrans = (0, 0), hostTrans = (48, 48),
        // gravity fallback winOff = (482, 186).
        ExpressiveGlass.computeScreenOrigin(48f, 48f, 0f, 0f, 482, 186, out);
        assertEquals("Frame 0 gravity fallback matches resting origin X", 530f, out[0], 0.01f);
        assertEquals("Frame 0 gravity fallback matches resting origin Y", 234f, out[1], 0.01f);
    }

    @Test
    public void premulRgbaBoxBlurPreservesCleanWhiteWithoutDarkFringe() {
        int w = 16, h = 16;
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        // Pure white opaque square in the center over transparent (0x00000000) background
        for (int y = 5; y < 11; y++) {
            for (int x = 5; x < 11; x++) {
                px[y * w + x] = 0xFFFFFFFF;
            }
        }
        ExpressiveGlass.blurPremulRgba(px, tmp, w, h, 2, 255);
        // Semi-transparent blurred edge pixel next to the white square must remain pure white RGB (~255), NOT gray!
        int edge = px[4 * w + 8];
        int a = (edge >>> 24) & 0xFF;
        int r = (edge >> 16) & 0xFF;
        int g = (edge >> 8) & 0xFF;
        int b = edge & 0xFF;
        assertTrue("Blurred edge must have partial alpha: " + a, a > 10 && a < 245);
        assertTrue("Premul blur must keep edge RGB bright white (no dark halo): R=" + r + " G=" + g + " B=" + b,
                r >= 245 && g >= 245 && b >= 245);
    }
}
