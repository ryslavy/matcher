package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User-created app folders on Home, persisted atomically in the launcher's private files (folders.txt).
 *
 * Each folder has a stable id (used as a synthetic ComponentName so Order, dock placement, focus restoration
 * and RecyclerView stable ids work identically to app tiles), a display name, and an ordered list of package
 * names. Each package belongs to at most one folder; empty folders are dropped automatically.
 */
final class Folders {

    private static final String TAG = "TvHome.Folders";
    static final String FILE_NAME = "folders.txt";
    private static final String MAGIC = "tvhome-folders 1";
    static final String CLASS_PREFIX = "com.gathin.tvhome.Folder.";
    static final int MAX_FOLDERS = 64;
    static final int MAX_NAME_LENGTH = 28;

    static final class Folder {
        final String id;
        final String name;
        final List<String> packages;

        Folder(String id, String name, List<String> packages) {
            this.id = id;
            this.name = name;
            this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
        }
    }

    private Folders() { }

    static ComponentName componentFor(Context ctx, String folderId) {
        return new ComponentName(ctx.getPackageName(), CLASS_PREFIX + folderId);
    }

    static String keyFor(Context ctx, String folderId) {
        return componentFor(ctx, folderId).flattenToShortString();
    }

    static String cleanName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ').replace('|', '/').trim();
        if (cleaned.length() > MAX_NAME_LENGTH) cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        return cleaned;
    }

    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 48) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    static String encode(List<Folder> folders) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        for (Folder f : folders) {
            if (f == null || !validId(f.id) || f.packages.isEmpty()) continue;
            String name = cleanName(f.name);
            if (name.isEmpty()) continue;
            sb.append(f.id).append('|').append(name).append('|')
                    .append(String.join(",", f.packages)).append('\n');
        }
        return sb.toString();
    }

    static List<Folder> decode(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        String[] lines = text.split("\n", -1);
        if (lines.length < 1 || !MAGIC.equals(lines[0].trim())) return Collections.emptyList();
        List<Folder> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenPackages = new HashSet<>();
        for (int i = 1; i < lines.length && result.size() < MAX_FOLDERS; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int p1 = line.indexOf('|');
            if (p1 <= 0) continue;
            int p2 = line.indexOf('|', p1 + 1);
            if (p2 <= p1 + 1) continue;
            String id = line.substring(0, p1);
            String name = cleanName(line.substring(p1 + 1, p2));
            String pkgPart = line.substring(p2 + 1);
            if (!validId(id) || name.isEmpty() || !seenIds.add(id)) continue;
            List<String> pkgs = new ArrayList<>();
            for (String rawPkg : pkgPart.split(",")) {
                String pkg = rawPkg.trim();
                if (!pkg.isEmpty() && LibraryApps.validPackage(pkg) && seenPackages.add(pkg)) {
                    pkgs.add(pkg);
                }
            }
            if (!pkgs.isEmpty()) result.add(new Folder(id, name, pkgs));
        }
        return result;
    }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static List<Folder> load(Context ctx) {
        File f = file(ctx);
        if (!f.exists() && !new File(f.getPath() + ".bak").exists()) return Collections.emptyList();
        try {
            return decode(new String(new AtomicFile(f).readFully(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot read " + FILE_NAME + ": " + e);
            return Collections.emptyList();
        }
    }

    static boolean save(Context ctx, List<Folder> folders) {
        AtomicFile af = new AtomicFile(file(ctx));
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(folders).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot write " + FILE_NAME + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }

    static Folder findById(List<Folder> folders, String folderId) {
        if (folderId == null) return null;
        for (Folder f : folders) if (folderId.equals(f.id)) return f;
        return null;
    }

    static String nextId(List<Folder> existing) {
        Set<String> used = new HashSet<>();
        for (Folder f : existing) used.add(f.id);
        int n = 1;
        while (used.contains("f" + n)) n++;
        return "f" + n;
    }

    /** Creates a new folder containing {@code initialPkg} (removing it from any previous folder). */
    static Folder create(Context ctx, String rawName, String initialPkg) {
        String name = cleanName(rawName);
        if (name.isEmpty() || !LibraryApps.validPackage(initialPkg)) return null;
        List<Folder> current = load(ctx);
        List<Folder> next = stripPackage(current, initialPkg);
        if (next.size() >= MAX_FOLDERS) return null;
        String id = nextId(next);
        Folder created = new Folder(id, name, Collections.singletonList(initialPkg));
        next.add(created);
        return save(ctx, next) ? created : null;
    }

    /** Adds or moves {@code pkg} into {@code folderId}. */
    static boolean addPackage(Context ctx, String folderId, String pkg) {
        if (folderId == null || !LibraryApps.validPackage(pkg)) return false;
        List<Folder> current = load(ctx);
        if (findById(current, folderId) == null) return false;
        List<Folder> next = new ArrayList<>();
        boolean added = false;
        for (Folder f : current) {
            List<String> pkgs = new ArrayList<>();
            for (String p : f.packages) {
                if (!p.equals(pkg)) pkgs.add(p);
            }
            if (folderId.equals(f.id)) {
                pkgs.add(pkg);
                added = true;
            }
            if (!pkgs.isEmpty()) next.add(new Folder(f.id, f.name, pkgs));
        }
        return added && save(ctx, next);
    }

    /** Removes {@code pkg} from {@code folderId}; deletes the folder if it becomes empty. */
    static boolean removePackage(Context ctx, String folderId, String pkg) {
        if (folderId == null || pkg == null) return false;
        List<Folder> current = load(ctx);
        List<Folder> next = new ArrayList<>();
        boolean changed = false;
        for (Folder f : current) {
            if (!folderId.equals(f.id)) {
                next.add(f);
                continue;
            }
            List<String> pkgs = new ArrayList<>();
            for (String p : f.packages) {
                if (p.equals(pkg)) changed = true;
                else pkgs.add(p);
            }
            if (!pkgs.isEmpty()) next.add(new Folder(f.id, f.name, pkgs));
        }
        return changed && save(ctx, next);
    }

    /** Removes {@code pkg} from whichever folder holds it (if any); drops empty folders. */
    static void removePackageEverywhere(Context ctx, String pkg) {
        if (pkg == null) return;
        List<Folder> current = load(ctx);
        if (current.isEmpty()) return;
        List<Folder> next = stripPackage(current, pkg);
        if (!sameFolders(current, next)) save(ctx, next);
    }

    static boolean rename(Context ctx, String folderId, String rawName) {
        String name = cleanName(rawName);
        if (folderId == null || name.isEmpty()) return false;
        List<Folder> current = load(ctx);
        List<Folder> next = new ArrayList<>(current.size());
        boolean changed = false;
        for (Folder f : current) {
            if (folderId.equals(f.id)) {
                next.add(new Folder(f.id, name, f.packages));
                changed = true;
            } else {
                next.add(f);
            }
        }
        return changed && save(ctx, next);
    }

    /** Returns a copy of {@code current} with {@code folderId}'s packages ordered by {@code orderedPackages}. */
    static List<Folder> reordered(List<Folder> current, String folderId, List<String> orderedPackages) {
        if (current == null || folderId == null || orderedPackages == null || orderedPackages.isEmpty()) {
            return current != null ? current : Collections.emptyList();
        }
        List<Folder> next = new ArrayList<>(current.size());
        for (Folder f : current) {
            if (!folderId.equals(f.id)) {
                next.add(f);
                continue;
            }
            Set<String> allowed = new HashSet<>(f.packages);
            Set<String> added = new HashSet<>();
            List<String> reorderedPkgs = new ArrayList<>(f.packages.size());
            for (String p : orderedPackages) {
                if (p != null && allowed.contains(p) && added.add(p)) {
                    reorderedPkgs.add(p);
                }
            }
            // Preserve any existing member that wasn't listed (e.g. a temporarily hidden/unloaded package).
            for (String p : f.packages) {
                if (added.add(p)) reorderedPkgs.add(p);
            }
            next.add(new Folder(f.id, f.name, reorderedPkgs));
        }
        return next;
    }

    /** Persists a new member order for {@code folderId}; returns true if saved or unchanged. */
    static boolean reorderPackages(Context ctx, String folderId, List<String> orderedPackages) {
        if (folderId == null || orderedPackages == null || orderedPackages.isEmpty()) return false;
        List<Folder> current = load(ctx);
        if (findById(current, folderId) == null) return false;
        List<Folder> next = reordered(current, folderId, orderedPackages);
        if (sameFolders(current, next)) return true;
        return save(ctx, next);
    }

    /** Removes the folder so all its member apps return to the Home screen. */
    static boolean dissolve(Context ctx, String folderId) {
        if (folderId == null) return false;
        List<Folder> current = load(ctx);
        List<Folder> next = new ArrayList<>(current.size());
        boolean removed = false;
        for (Folder f : current) {
            if (folderId.equals(f.id)) removed = true;
            else next.add(f);
        }
        return removed && save(ctx, next);
    }

    private static List<Folder> stripPackage(List<Folder> folders, String pkg) {
        List<Folder> next = new ArrayList<>(folders.size());
        for (Folder f : folders) {
            List<String> pkgs = new ArrayList<>(f.packages.size());
            for (String p : f.packages) {
                if (!p.equals(pkg)) pkgs.add(p);
            }
            if (!pkgs.isEmpty()) next.add(new Folder(f.id, f.name, pkgs));
        }
        return next;
    }

    private static boolean sameFolders(List<Folder> a, List<Folder> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Folder fa = a.get(i), fb = b.get(i);
            if (!fa.id.equals(fb.id) || !fa.name.equals(fb.name) || !fa.packages.equals(fb.packages)) return false;
        }
        return true;
    }
}
