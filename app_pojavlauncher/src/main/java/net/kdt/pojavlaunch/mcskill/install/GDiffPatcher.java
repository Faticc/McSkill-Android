package net.kdt.pojavlaunch.mcskill.install;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Minimal decoder for the GDIFF binary-diff format used by legacy Forge's binpatches.pack.lzma
 * (and, separately, by OptiFine's own runtime class patcher). See {@link ForgeBinPatchApplier} for
 * why this exists: applying Forge's official class patches requires unpacking a Pack200 archive,
 * and Pack200 was removed from the JDK in 14+, so those patches get pre-extracted (via a JDK 8
 * install's unpack200) into plain GDIFF diffs bundled as an asset instead - this only needs to
 * apply an already-extracted diff, never touching Pack200 itself.
 *
 * Command byte semantics verified against optifine.xdelta.GDiffPatcher's decompiled bytecode
 * (OptiFine_1.7.10_HD_U_E7.jar), not assumed from memory of the GDIFF spec. This is a duplicate of
 * MioLibPatcher's com.mio.libpatcher.util.GDiffPatcher (same algorithm, same verification) - kept
 * separate because this class runs in the launcher app's own JVM at install time, while
 * MioLibPatcher runs inside the game's JVM as a javaagent; they're different Gradle modules with no
 * shared dependency worth introducing for ~80 lines.
 */
final class GDiffPatcher {
    private static final int MAGIC_0 = 0xD1;
    private static final int MAGIC_1 = 0xFF;
    private static final int MAGIC_2 = 0xD1;
    private static final int MAGIC_3 = 0xFF;
    private static final int VERSION = 0x04;

    private GDiffPatcher() {
    }

    static byte[] apply(byte[] patch, byte[] source) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(patch));
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + patch.length);

        if (in.readUnsignedByte() != MAGIC_0 || in.readUnsignedByte() != MAGIC_1
                || in.readUnsignedByte() != MAGIC_2 || in.readUnsignedByte() != MAGIC_3
                || in.readUnsignedByte() != VERSION) {
            throw new IOException("GDIFF magic/version mismatch");
        }

        while (in.available() > 0) {
            int cmd = in.readUnsignedByte();
            if (cmd == 0) {
                break;
            } else if (cmd <= 246) {
                appendLiteral(in, out, cmd);
            } else if (cmd == 247) {
                appendLiteral(in, out, in.readUnsignedShort());
            } else if (cmd == 248) {
                appendLiteral(in, out, in.readInt());
            } else if (cmd == 249) {
                copy(source, out, in.readUnsignedShort(), in.readUnsignedByte());
            } else if (cmd == 250) {
                copy(source, out, in.readUnsignedShort(), in.readUnsignedShort());
            } else if (cmd == 251) {
                copy(source, out, in.readUnsignedShort(), in.readInt());
            } else if (cmd == 252) {
                copy(source, out, in.readInt(), in.readUnsignedByte());
            } else if (cmd == 253) {
                copy(source, out, in.readInt(), in.readUnsignedShort());
            } else if (cmd == 254) {
                copy(source, out, in.readInt(), in.readInt());
            } else {
                // 255: COPY_LONG_INT - offset as long, but our inputs are always small class files.
                long offset = in.readLong();
                copy(source, out, (int) offset, in.readInt());
            }
        }

        return out.toByteArray();
    }

    private static void appendLiteral(DataInputStream in, ByteArrayOutputStream out, int length) throws IOException {
        byte[] buf = new byte[length];
        in.readFully(buf);
        out.write(buf, 0, length);
    }

    private static void copy(byte[] source, ByteArrayOutputStream out, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > source.length) {
            throw new IOException("GDIFF copy out of bounds: offset=" + offset + " length=" + length + " sourceLen=" + source.length);
        }
        out.write(source, offset, length);
    }
}
