package net.kdt.pojavlaunch.mcskill.install;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Restores the class patches {@link McSkillClientInstaller#stripLegacyBinPatches} removes from
 * forge.jar. Legacy 1.7.10 Forge ships ~1060 official binary patches to vanilla classes
 * (forge.jar's {@code binpatches.pack.lzma}, normally applied by
 * {@code cpw.mods.fml.common.patcher.ClassPatchManager} via {@code java.util.jar.Pack200} before
 * any mod-level code runs) - things like adding hook methods to {@code GuiScreen} and
 * {@code Entity} that mods (gtnhlib's Mixins, LuxinfineHelper's ASM hook framework, etc.) expect to
 * already exist. Stripping that entry avoided a hard {@code NoClassDefFoundError} crash from
 * Pack200's removal in JDK 14+, but it also silently dropped every patch the entry carried -
 * confirmed by two unrelated mods failing on two different classes for the identical reason
 * (missing method that's supposed to come from one of these patches), and by hand-extracting and
 * applying the affected patches against a vanilla class to verify they're exactly what's missing.
 *
 * A JVM-agent-level fix (applying patches to a class's bytecode from inside the running game) is
 * fundamentally too late: by the time any {@code java.lang.instrument.ClassFileTransformer} sees a
 * class's bytes, {@code LaunchClassLoader}'s own transformer chain (Mixin, coremods, other mods'
 * ASM) has already run against it - so the patched method wouldn't exist yet when those other
 * transformers look for it, and (worse) other mods' legitimate changes to the same class would
 * already be baked in, making Forge's patch (built against pure vanilla bytecode) unsafe to apply
 * on top. Applying patches here, at install time, before the game's JVM ever starts, avoids both
 * problems: this operates directly on minecraft.jar's stored bytes, so every mod sees the fully
 * Forge-patched vanilla class from the very first time anything touches it - exactly like the PC
 * reference client.
 *
 * Patches are pre-extracted (unpacked from forge.jar's binpatches.pack.lzma via a JDK 8 install's
 * {@code unpack200}, since Pack200 isn't available on the JDK this app or the game ship) and
 * bundled as the {@code ForgeBinPatches1710} component (~527 client-side {@code .binpatch}
 * entries, GDIFF diffs - see {@link GDiffPatcher}). Each entry carries an Adler32 checksum of the
 * exact vanilla bytecode it was built against; that's checked before applying so a
 * previously-patched or unexpectedly-different class is skipped rather than corrupted - this also
 * makes re-running this method on an already-patched minecraft.jar a safe no-op, since the checksum
 * won't match vanilla anymore.
 */
class ForgeBinPatchApplier {
    private static final String TAG = "ForgeBinPatchApplier";
    private static final String MINECRAFT_JAR_NAME = "minecraft.jar";
    private static final String PATCHES_COMPONENT_JAR = "ForgeBinPatches1710/ForgeBinPatches1710.jar";

    static void apply(File clientLibDir) {
        File minecraftJar = new File(clientLibDir, MINECRAFT_JAR_NAME);
        if (!minecraftJar.isFile()) return;

        File patchesJar = new File(Tools.DIR_DATA, PATCHES_COMPONENT_JAR);
        if (!patchesJar.isFile()) {
            Log.w(TAG, "ForgeBinPatches1710 component not found at " + patchesJar + ", skipping binpatch restoration");
            return;
        }

        Map<String, BinPatch> patchesBySourceClass = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(patchesJar)))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".binpatch")) continue;
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                int read;
                while ((read = zin.read(buffer)) != -1) raw.write(buffer, 0, read);
                BinPatch patch = BinPatch.parse(raw.toByteArray());
                if (patch != null && patch.exists) {
                    patchesBySourceClass.put(patch.sourceClassName + ".class", patch);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + PATCHES_COMPONENT_JAR, e);
            return;
        }
        if (patchesBySourceClass.isEmpty()) return;

        File tmp = new File(clientLibDir, MINECRAFT_JAR_NAME + ".tmp");
        int patchedCount = 0;
        try (ZipFile sourceZip = new ZipFile(minecraftJar);
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            byte[] buffer = new byte[65536];
            java.util.Enumeration<? extends ZipEntry> entries = sourceZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] entryBytes = readAll(sourceZip.getInputStream(entry), buffer);

                BinPatch patch = patchesBySourceClass.get(entry.getName());
                if (patch != null) {
                    Adler32 adler = new Adler32();
                    adler.update(entryBytes);
                    if (adler.getValue() == patch.sourceChecksum) {
                        try {
                            entryBytes = GDiffPatcher.apply(patch.diff, entryBytes);
                            patchedCount++;
                        } catch (IOException e) {
                            Log.w(TAG, "Failed to apply binpatch for " + patch.humanClassName + ", leaving it vanilla", e);
                        }
                    }
                    // Checksum mismatch (already patched from a prior run, or an unexpected minecraft.jar
                    // build) - leave entryBytes as read, no error, this is the expected no-op path on re-runs.
                }

                zout.putNextEntry(new ZipEntry(entry.getName()));
                zout.write(entryBytes);
                zout.closeEntry();
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not apply Forge binpatches to " + MINECRAFT_JAR_NAME, e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }

        if (patchedCount == 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (tmp.renameTo(minecraftJar)) {
            Log.i(TAG, "Restored " + patchedCount + " Forge binpatch(es) to " + MINECRAFT_JAR_NAME);
        } else {
            Log.w(TAG, "Could not replace " + MINECRAFT_JAR_NAME + " after applying Forge binpatches");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static byte[] readAll(InputStream in, byte[] buffer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** One decoded {@code binpatch/client/<name>.binpatch} entry - see McSkillClientInstaller's GDIFF/binpatch investigation for the format. */
    private static final class BinPatch {
        final String sourceClassName;
        final String humanClassName;
        final boolean exists;
        final long sourceChecksum;
        final byte[] diff;

        private BinPatch(String sourceClassName, String humanClassName, boolean exists, long sourceChecksum, byte[] diff) {
            this.sourceClassName = sourceClassName;
            this.humanClassName = humanClassName;
            this.exists = exists;
            this.sourceChecksum = sourceChecksum;
            this.diff = diff;
        }

        static BinPatch parse(byte[] data) throws IOException {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
            String sourceClassName = in.readUTF();
            in.readUTF(); // target class name (== sourceClassName for client patches) - unused
            String humanClassName = in.readUTF();
            boolean exists = in.readBoolean();
            if (!exists) return new BinPatch(sourceClassName, humanClassName, false, 0, null);
            long checksum = in.readInt() & 0xffffffffL;
            int patchLength = in.readInt();
            byte[] diff = new byte[patchLength];
            in.readFully(diff);
            return new BinPatch(sourceClassName, humanClassName, true, checksum, diff);
        }
    }
}
