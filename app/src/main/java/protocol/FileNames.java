package protocol;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 落盘：优先 系统下载/TextSend；写不进去则用应用专属 Download/TextSend。
 * 不弹保存框。图片在 Android 上也落地（不能贴系统剪贴板）。
 */
public final class FileNames {
    public static final String FOLDER = "TextSend";

    /** 不用 Set.of：Android 10 及以下没有这个 API，一加载本类就崩。 */
    private static final Set<String> IMAGE_EXT = new HashSet<>();
    static {
        IMAGE_EXT.add("png");
        IMAGE_EXT.add("jpg");
        IMAGE_EXT.add("jpeg");
        IMAGE_EXT.add("gif");
        IMAGE_EXT.add("bmp");
        IMAGE_EXT.add("webp");
        IMAGE_EXT.add("tif");
        IMAGE_EXT.add("tiff");
        IMAGE_EXT.add("heic");
        IMAGE_EXT.add("heif");
    }

    private static volatile Context app;

    private FileNames() {
    }

    public static void init(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public static Path inboxDir() throws IOException {
        File pub = publicInbox();
        if (canWrite(pub)) {
            return pub.toPath();
        }
        if (app == null) {
            throw new IOException("FileNames 未初始化");
        }
        File ext = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (ext == null) {
            ext = app.getFilesDir();
        }
        File fallback = new File(ext, FOLDER);
        if (!fallback.isDirectory() && !fallback.mkdirs()) {
            throw new IOException("无法创建接收目录");
        }
        return fallback.toPath();
    }

    public static File publicInbox() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(dl, FOLDER);
    }

    public static boolean isImageName(String name) {
        String ext = extOf(name);
        return ext != null && IMAGE_EXT.contains(ext);
    }

    public static String sanitize(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").trim();
        if (n.equals(".") || n.equals("..") || n.isEmpty()) {
            n = "file";
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.equals("con") || lower.equals("prn") || lower.equals("aux") || lower.equals("nul")
                || lower.matches("com[1-9]") || lower.matches("lpt[1-9]")) {
            n = "_" + n;
        }
        if (n.length() > 120) {
            String ext = "";
            int dot = n.lastIndexOf('.');
            if (dot > 0 && dot >= n.length() - 8) {
                ext = n.substring(dot);
                n = n.substring(0, dot);
            }
            n = n.substring(0, Math.max(1, 120 - ext.length())) + ext;
        }
        return n;
    }

    /**
     * 相对路径：去掉盘符、绝对路径和 {@code ..}。非法返回 null。
     */
    public static String sanitizeRelPath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String n = raw.replace('\\', '/').trim();
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : n.split("/")) {
            if (p.isEmpty() || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p)) {
                return null;
            }
            String s = sanitize(p);
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 把相对路径接到 root 下；越界则抛错。 */
    public static Path resolveUnder(Path root, String rel) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        Path p = r;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            p = p.resolve(seg);
        }
        p = p.normalize();
        if (!p.startsWith(r)) {
            throw new IOException("path");
        }
        return p;
    }

    /** 重名则 name、name (1)、name (2) 目录，并创建。 */
    public static Path uniqueDir(Path parent, String sanitized) throws IOException {
        Path cand = parent.resolve(sanitized);
        if (!Files.exists(cand)) {
            Files.createDirectories(cand);
            return cand;
        }
        for (int i = 1; i < 10_000; i++) {
            cand = parent.resolve(sanitized + " (" + i + ")");
            if (!Files.exists(cand)) {
                Files.createDirectories(cand);
                return cand;
            }
        }
        throw new IOException("too many duplicates: " + sanitized);
    }

    /** 重名则 name (1).ext、name (2).ext，不覆盖。 */
    public static Path unique(Path dir, String sanitized) throws IOException {
        Path cand = dir.resolve(sanitized);
        if (!Files.exists(cand)) {
            return cand;
        }
        String stem = sanitized;
        String ext = "";
        int dot = sanitized.lastIndexOf('.');
        if (dot > 0) {
            stem = sanitized.substring(0, dot);
            ext = sanitized.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            cand = dir.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(cand)) {
                return cand;
            }
        }
        throw new IOException("too many duplicates: " + sanitized);
    }

    private static boolean canWrite(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".textsend.write");
            if (probe.exists()) {
                return probe.delete() || dir.canWrite();
            }
            if (!probe.createNewFile()) {
                return false;
            }
            return probe.delete() || true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String extOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
