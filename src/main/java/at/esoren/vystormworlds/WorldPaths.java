package at.esoren.vystormworlds;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where world folders live. Minecraft 26.x keeps every dimension inside the main level folder
 * ({@code <level>/dimensions/minecraft/<name>}); 1.21.x keeps each world in its own folder in the world container.
 * All destructive operations check that the folder is strictly inside the expected root, is not a server world and
 * is not a Vystorm Core plugin world.
 */
final class WorldPaths {
    static final String CORE_MARKER = "vystorm-world.yml";
    static final Set<String> SKIP_ON_COPY = Set.of("session.lock", "uid.dat");
    static final List<String> CHUNK_DATA = List.of("region", "entities", "poi");

    private final Path level;
    private final Path container;

    WorldPaths(Path level, Path container) {
        this.level = level == null ? null : level.toAbsolutePath().normalize();
        this.container = container.toAbsolutePath().normalize();
    }

    /** Detects the layout of the running server ({@code Server#getLevelDirectory} exists on 26.x). */
    static WorldPaths detect(org.bukkit.Server server) {
        Path level = null;
        try {
            Method method = org.bukkit.Server.class.getMethod("getLevelDirectory");
            level = (Path) method.invoke(server);
        } catch (ReflectiveOperationException | RuntimeException legacy) { /* 1.21.x layout */ }
        return new WorldPaths(level, server.getWorldContainer().toPath());
    }

    boolean modern() { return level != null; }

    Path container() { return container; }

    /** Root under which managed world folders may be created, moved or deleted. */
    Path root() { return modern() ? level.resolve("dimensions") : container; }

    Path level() { return level; }

    boolean customHeights() {
        if (modern()) return true;
        // CraftBukkit 26.x loads every registered LevelStem at startup but retains named world folders.
        try { Class.forName("net.minecraft.world.level.storage.LevelDataAndDimensions"); return true; }
        catch (ClassNotFoundException | LinkageError missing) { return false; }
    }

    Path datapackLevel() {
        if (level != null) return level;
        if (!customHeights() || org.bukkit.Bukkit.getWorlds().isEmpty()) return null;
        return org.bukkit.Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath().normalize();
    }

    Path folder(String name) { return folder("minecraft", name); }

    /** Folder of a world in a namespace (26.x: data pack worlds live under their own namespace). */
    Path folder(String namespace, String name) {
        if (modern()) return root().resolve(namespace).resolve(name.toLowerCase(Locale.ROOT)).normalize();
        if (!namespace.equals("minecraft") && customHeights()) {
            Path main = datapackLevel();
            if (main == null) throw new IllegalStateException("Main level directory is unavailable");
            return container.resolve(main.getFileName() + "_" + namespace + "_" + name.toLowerCase(Locale.ROOT)).normalize();
        }
        return container.resolve(name).normalize();
    }

    static boolean strictlyInside(Path root, Path child) {
        Path r = root.toAbsolutePath().normalize(), c = child.toAbsolutePath().normalize();
        return c.startsWith(r) && !c.equals(r);
    }

    static boolean coreWorld(Path folder) { return Files.exists(folder.resolve(CORE_MARKER), LinkOption.NOFOLLOW_LINKS); }

    /** A folder with chunk data or a level.dat. */
    static boolean hasWorldData(Path folder) {
        if (Files.isRegularFile(folder.resolve("level.dat"))) return true;
        for (String sub : CHUNK_DATA) if (Files.isDirectory(folder.resolve(sub))) return true;
        return false;
    }

    /**
     * Throws if {@code folder} must not be deleted or replaced: outside the root, the root itself, a symbolic link, a
     * protected (server or plugin) world folder or a Vystorm Core plugin world.
     */
    void checkDestructible(Path folder, Collection<Path> protectedFolders) throws IOException {
        if (!strictlyInside(root(), folder)) throw new IOException("refusing to touch " + folder + ": not inside " + root());
        if (root().toAbsolutePath().normalize().relativize(folder.toAbsolutePath().normalize()).getNameCount() != (modern() ? 2 : 1))
            throw new IOException("refusing to touch " + folder + ": not a world folder");
        if (Files.isSymbolicLink(folder)) throw new IOException("refusing to touch " + folder + ": symbolic link");
        Path normalized = folder.toAbsolutePath().normalize();
        for (Path p : protectedFolders) if (p != null && normalized.equals(p.toAbsolutePath().normalize()))
            throw new IOException("refusing to touch " + folder + ": server or protected world");
        if (modern() && normalized.equals(level)) throw new IOException("refusing to touch the main level folder");
        if (coreWorld(folder)) throw new IOException("refusing to touch " + folder + ": Vystorm Core plugin world");
    }

    /** A folder that can be imported: either already in the right place, or an old 1.21.x world folder to copy over. */
    record Candidate(String name, Path folder, boolean legacy, WorldKind kind) {}

    /** Unloaded folders that look like worlds and are neither loaded, known, nor Core plugin worlds. */
    List<Candidate> candidates(Set<String> loadedOrKnown) {
        List<Candidate> list = new ArrayList<>();
        if (modern()) scan(root().resolve("minecraft"), loadedOrKnown, false, list);
        scan(container, loadedOrKnown, modern(), list);
        return list;
    }

    private void scan(Path dir, Set<String> skip, boolean legacy, List<Candidate> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || skip.contains(name.toLowerCase(Locale.ROOT)) || !WorldSettings.validName(name)) continue;
                if (modern() && child.toAbsolutePath().normalize().equals(level)) continue;
                if (coreWorld(child)) continue;
                if (legacy && !Files.isRegularFile(child.resolve("level.dat"))) continue;
                if (legacy && modern() && Files.exists(folder(name))) continue;
                if (!hasWorldData(child) && legacyKind(child) == null) continue;
                WorldKind kind = legacy || !modern() ? legacyKind(child) : null;
                out.add(new Candidate(modern() ? name.toLowerCase(Locale.ROOT) : name, child, legacy, kind == null ? WorldKind.NORMAL : kind));
            }
        } catch (IOException unreadable) { /* nothing to offer */ }
    }

    /** Dimension of an old-layout world folder: chunk data at the top, in DIM-1 (nether) or DIM1 (end). */
    static WorldKind legacyKind(Path folder) {
        if (Files.isDirectory(folder.resolve("region"))) return WorldKind.NORMAL;
        if (Files.isDirectory(folder.resolve("DIM-1").resolve("region"))) return WorldKind.NETHER;
        if (Files.isDirectory(folder.resolve("DIM1").resolve("region"))) return WorldKind.END;
        return null;
    }

    /** Lock files and the world identity (uid.dat, Paper's data/paper/metadata.dat): a copy must get a new one. */
    static boolean skipOnCopy(Path file) {
        String name = file.getFileName().toString();
        if (SKIP_ON_COPY.contains(name)) return true;
        Path parent = file.getParent();
        return name.equals("metadata.dat") && parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("paper");
    }

    static void copyTree(Path from, Path to) throws IOException { copyTree(from, to, true); }

    /** Copies a folder; {@code skipIdentity} leaves out lock and identity files (for copies that become another world). */
    static void copyTree(Path from, Path to, boolean skipIdentity) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || skipIdentity && skipOnCopy(file)) return FileVisitResult.CONTINUE;
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Moves a folder (rename if possible, else copy then delete). The target must not exist. */
    static void moveTree(Path from, Path to) throws IOException {
        if (Files.exists(to)) throw new IOException("target exists: " + to);
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException otherDrive) {
            copyTree(from, to, false);
            deleteTree(from);
        }
    }

    static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
