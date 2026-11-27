package com.gathin.tvhome;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Automated integrity guard across ALL 31+ Matcher locale overlays ({@code values* /strings_matcher.xml}):
 * verifies XML well-formedness, zero missing translatable keys, zero duplicate keys vs upstream
 * {@code strings.xml}, zero unescaped ASCII apostrophes, and 100% printf format-specifier parity
 * ({@code %1$s}, {@code %1$d}, etc.) against the default English overlay.
 */
public class LocaleIntegrityTest {

    private static final Pattern FORMAT_SPEC = Pattern.compile("%(?:(\\d+)\\$)?([-#+ 0,(]*)(\\d+)?(\\.\\d+)?([a-zA-Z%])");
    private static final Pattern UNESCAPED_APOSTROPHE = Pattern.compile("(?<!\\\\)'");

    private static File resDir() {
        File direct = new File("src/main/res");
        if (direct.isDirectory()) return direct;
        File fromLauncher = new File("app/src/main/res");
        if (fromLauncher.isDirectory()) return fromLauncher;
        return direct;
    }

    @Test
    public void allMatcherLocalesAreCompleteWellFormedAndFormatSafe() throws Exception {
        File res = resDir();
        File baseFile = new File(res, "values/strings_matcher.xml");
        assertTrue("Missing base values/strings_matcher.xml at " + baseFile.getAbsolutePath(), baseFile.isFile());

        ParsedStrings base = parseXml(baseFile);
        assertTrue("Expected at least 180 translatable Matcher strings", base.translatableStrings.size() >= 180);
        assertTrue("Expected recents_closed_all_toast plural", base.plurals.containsKey("recents_closed_all_toast"));

        // Verify base Matcher overlay has zero duplicate keys against upstream values/strings.xml
        File upstreamEn = new File(res, "values/strings.xml");
        if (upstreamEn.isFile()) {
            ParsedStrings up = parseXml(upstreamEn);
            for (String k : base.allStrings.keySet()) {
                assertFalse("Duplicate string key in values/strings_matcher.xml vs upstream strings.xml: " + k,
                        up.allStrings.containsKey(k));
            }
            for (String k : base.plurals.keySet()) {
                assertFalse("Duplicate plural key in values/strings_matcher.xml vs upstream strings.xml: " + k,
                        up.plurals.containsKey(k));
            }
        }

        File[] dirs = res.listFiles(f -> f.isDirectory() && f.getName().startsWith("values-"));
        assertTrue("Expected locale resource directories under " + res.getAbsolutePath(), dirs != null && dirs.length >= 30);

        int checked = 0;
        List<String> failures = new ArrayList<>();
        for (File dir : dirs) {
            File overlay = new File(dir, "strings_matcher.xml");
            if (!overlay.isFile()) {
                failures.add(dir.getName() + ": missing strings_matcher.xml");
                continue;
            }
            checked++;
            ParsedStrings loc = parseXml(overlay);
            String locName = dir.getName();

            // 1. Check all translatable strings present, non-empty, escaped, and format-specifier identical
            for (Map.Entry<String, String> e : base.translatableStrings.entrySet()) {
                String key = e.getKey();
                String enVal = e.getValue();
                String locVal = loc.allStrings.get(key);
                if (locVal == null || locVal.trim().isEmpty()) {
                    failures.add(locName + ": missing or empty key '" + key + "'");
                    continue;
                }
                if (hasUnescapedApostrophe(locVal)) {
                    failures.add(locName + ": unescaped apostrophe in '" + key + "': " + locVal);
                }
                List<String> enFmt = formatSpecs(enVal);
                List<String> locFmt = formatSpecs(locVal);
                if (!enFmt.equals(locFmt)) {
                    failures.add(locName + ": format specifier mismatch in '" + key + "' (expected "
                            + enFmt + ", got " + locFmt + ")");
                }
            }

            // 2. Check plurals present, non-empty, escaped, and format-specifier safe
            for (Map.Entry<String, Map<String, String>> pe : base.plurals.entrySet()) {
                String pKey = pe.getKey();
                Map<String, String> locItems = loc.plurals.get(pKey);
                if (locItems == null || !locItems.containsKey("other")) {
                    failures.add(locName + ": missing plural '" + pKey + "' (needs at least quantity='other')");
                    continue;
                }
                List<String> expectedFmt = formatSpecs(pe.getValue().get("other"));
                for (Map.Entry<String, String> item : locItems.entrySet()) {
                    if (item.getValue().trim().isEmpty()) {
                        failures.add(locName + ": empty plural item '" + pKey + "/" + item.getKey() + "'");
                    }
                    if (hasUnescapedApostrophe(item.getValue())) {
                        failures.add(locName + ": unescaped apostrophe in plural '" + pKey + "/" + item.getKey() + "'");
                    }
                    List<String> gotFmt = formatSpecs(item.getValue());
                    if (!expectedFmt.equals(gotFmt)) {
                        failures.add(locName + ": plural format mismatch in '" + pKey + "/" + item.getKey()
                                + "' (expected " + expectedFmt + ", got " + gotFmt + ")");
                    }
                }
            }

            // 3. Zero duplicates against upstream strings.xml in same locale folder
            File upLoc = new File(dir, "strings.xml");
            if (upLoc.isFile()) {
                ParsedStrings up = parseXml(upLoc);
                for (String k : loc.allStrings.keySet()) {
                    if (up.allStrings.containsKey(k)) {
                        failures.add(locName + ": duplicate key vs upstream strings.xml: '" + k + "'");
                    }
                }
            }
        }

        assertTrue("Expected at least 31 localized strings_matcher.xml overlays, found " + checked, checked >= 31);
        assertEquals("Locale integrity issues:\n" + String.join("\n", failures),
                Collections.emptyList(), failures);
    }

    private static boolean hasUnescapedApostrophe(String text) {
        if (text == null || text.indexOf('\'') < 0) return false;
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) return false;
        return UNESCAPED_APOSTROPHE.matcher(text).find();
    }

    private static List<String> formatSpecs(String text) {
        List<String> specs = new ArrayList<>();
        if (text == null) return specs;
        Matcher m = FORMAT_SPEC.matcher(text);
        while (m.find()) {
            String full = m.group(0);
            if ("%%".equals(full)) continue;
            specs.add(full);
        }
        Collections.sort(specs);
        return specs;
    }

    private static final class ParsedStrings {
        final Map<String, String> allStrings = new HashMap<>();
        final Map<String, String> translatableStrings = new HashMap<>();
        final Map<String, Map<String, String>> plurals = new HashMap<>();
    }

    private static ParsedStrings parseXml(File file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);
        ParsedStrings out = new ParsedStrings();
        Set<String> seen = new HashSet<>();

        NodeList strings = doc.getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element el = (Element) strings.item(i);
            String name = el.getAttribute("name");
            String val = el.getTextContent();
            assertTrue("Duplicate <string name=\"" + name + "\"> inside " + file.getPath(), seen.add("s:" + name));
            out.allStrings.put(name, val);
            if (!"false".equalsIgnoreCase(el.getAttribute("translatable"))) {
                out.translatableStrings.put(name, val);
            }
        }
        NodeList pls = doc.getElementsByTagName("plurals");
        for (int i = 0; i < pls.getLength(); i++) {
            Element el = (Element) pls.item(i);
            String name = el.getAttribute("name");
            Map<String, String> items = new HashMap<>();
            NodeList children = el.getElementsByTagName("item");
            for (int j = 0; j < children.getLength(); j++) {
                Element it = (Element) children.item(j);
                items.put(it.getAttribute("quantity"), it.getTextContent());
            }
            out.plurals.put(name, items);
        }
        return out;
    }
}
