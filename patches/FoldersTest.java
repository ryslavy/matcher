package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FoldersTest {

    @Test
    public void roundTripPreservesOrderAndUnicodeNames() {
        List<Folders.Folder> input = Arrays.asList(
                new Folders.Folder("f1", "Streamování", Arrays.asList("com.netflix.ninja", "com.disney.disneyplus")),
                new Folders.Folder("f2", "Nástroje", Collections.singletonList("io.github.maxhayim.brightness"))
        );
        List<Folders.Folder> decoded = Folders.decode(Folders.encode(input));
        assertEquals(2, decoded.size());
        assertEquals("f1", decoded.get(0).id);
        assertEquals("Streamování", decoded.get(0).name);
        assertEquals(Arrays.asList("com.netflix.ninja", "com.disney.disneyplus"), decoded.get(0).packages);
        assertEquals("f2", decoded.get(1).id);
        assertEquals("Nástroje", decoded.get(1).name);
    }

    @Test
    public void decodeDeduplicatesPackagesAcrossFoldersAndDropsBadLines() {
        String raw = "tvhome-folders 1\n"
                + "f1|Video|com.netflix.ninja,com.disney.disneyplus\n"
                + "f2|Dupe|com.netflix.ninja,com.wbd.stream\n"
                + "bad line without pipes\n"
                + "f3|Empty|\n";
        List<Folders.Folder> decoded = Folders.decode(raw);
        assertEquals(2, decoded.size());
        assertEquals(Collections.singletonList("com.wbd.stream"), decoded.get(1).packages);
    }

    @Test
    public void decodeIgnoresWrongHeader() {
        assertTrue(Folders.decode("wrong-header\nf1|A|com.example.app\n").isEmpty());
    }

    @Test
    public void cleanNameStripsDelimitersAndCapsLength() {
        assertEquals("A/B C", Folders.cleanName("  A|B\nC  "));
        assertEquals(Folders.MAX_NAME_LENGTH, Folders.cleanName("12345678901234567890123456789012345").length());
    }

    @Test
    public void nextIdFindsFirstUnusedSlot() {
        List<Folders.Folder> existing = Arrays.asList(
                new Folders.Folder("f1", "A", Collections.singletonList("com.a.one")),
                new Folders.Folder("f3", "B", Collections.singletonList("com.b.two"))
        );
        assertEquals("f2", Folders.nextId(existing));
    }

    @Test
    public void reorderedAppliesNewOrderAndPreservesMissingOrDeduplicates() {
        List<Folders.Folder> existing = Arrays.asList(
                new Folders.Folder("f1", "Video", Arrays.asList("com.a.one", "com.b.two", "com.c.three")),
                new Folders.Folder("f2", "Music", Collections.singletonList("com.d.four"))
        );
        List<Folders.Folder> updated = Folders.reordered(existing, "f1",
                Arrays.asList("com.c.three", "com.unknown.pkg", "com.a.one", "com.c.three"));
        assertEquals(2, updated.size());
        assertEquals(Arrays.asList("com.c.three", "com.a.one", "com.b.two"), updated.get(0).packages);
        assertEquals(Collections.singletonList("com.d.four"), updated.get(1).packages);
    }
}
