package cc.ioctl.neoauth3bot.util;

import cc.ioctl.telebot.util.IoUtils;
import org.jetbrains.annotations.NotNull;

import java.io.*;

public class SdfUtils {

    private SdfUtils() {
        throw new IllegalStateException("no instance");
    }

    /**
     * Check whether the given file is a Blocked GNU Zip Format file.
     *
     * @param file the file to check
     * @return true if the file is a Blocked GNU Zip Format file
     * @throws IOException if the file could not be read, e.g. because it does not exist
     */
    public static boolean isBgzFile(@NotNull File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[4];
            IoUtils.readExact(is, buf, 0, 4);
            return buf[0] == (byte) 0x1F && buf[1] == (byte) 0x8B && buf[2] == (byte) 0x8 && buf[3] == (byte) 0x4;
        }
    }

}
