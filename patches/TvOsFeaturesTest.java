package com.gathin.tvhome;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TvOsFeaturesTest {

    @Test
    public void parsesApplePhobosAndBzamayoAerialManifests() {
        String phobos = "[{\"assets\":[{\"url\":\"http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_GL.mov\",\"accessibilityLabel\":\"Greenland\",\"timeOfDay\":\"day\"},"
                + "{\"url\":\"http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/comp_DB.mov\",\"accessibilityLabel\":\"Dubai\",\"timeOfDay\":\"night\"}]}]";
        List<AerialCatalog.Clip> ph = AerialCatalog.parsePhobosJson(phobos);
        assertEquals(2, ph.size());
        assertEquals("Greenland", ph.get(0).label);
        assertFalse(ph.get(0).night);
        assertEquals("Dubai", ph.get(1).label);
        assertTrue(ph.get(1).night);

        String bzamayo = "{\"videoURL\":\"https:\\/\\/sylvan.apple.com\\/itunes-assets\\/comp_C003_SDR_2K_AVC.mov\",\"name\":\"Iceland Night Aurora\"}";
        List<AerialCatalog.Clip> bz = AerialCatalog.parseBzamayoJson(bzamayo);
        assertEquals(1, bz.size());
        assertEquals("http://sylvan.apple.com/itunes-assets/comp_C003_SDR_2K_AVC.mov", bz.get(0).url);
        assertEquals("Iceland Night Aurora", bz.get(0).label);
        assertTrue(bz.get(0).night);
    }

    @Test
    public void localizesAerialPlaceNamesAndControlCenterAction() {
        assertEquals("Grónsko", AerialCatalog.localizePlace("Greenland", true));
        assertEquals("Dubaj", AerialCatalog.localizePlace("Dubai", true));
        assertEquals("Havaj", AerialCatalog.localizePlace("Hawaii", true));
        assertEquals("Island", AerialCatalog.localizePlace("Iceland", true));
        assertEquals("Greenland", AerialCatalog.localizePlace("Greenland", false));

        assertTrue(RemoteKeys.isAction("control_center"));
    }
}
