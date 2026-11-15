package ru.alexv.anyailive.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Удаление записанных реплик одного чата из его каталога {@code filesDir/audio/<chatId>}.
 *
 * <p>Путь берётся из строки БД, а она может оказаться чем угодно, поэтому решение
 * об удалении принимает не вызывающий код. Правила:
 * <ul>
 *   <li>путь нормализуется <b>лексически</b> ({@code .}/{@code ..} без перехода по
 *       ссылкам): {@code File.getCanonicalPath()} на разных JDK по-разному раскрывает
 *       символические ссылки, полагаться на него нельзя;</li>
 *   <li>удаляется только прямой ребёнок каталога чата — запись кладёт wav именно туда;</li>
 *   <li>каталог чата, его родитель и сам файл проверяются на символическую ссылку через
 *       {@link Links} (на Android — {@code Os.lstat}); ссылка означает «не наше»;</li>
 *   <li>удаляется лексический путь, а не «разрешённый»: переход по ссылке невозможен.</li>
 * </ul>
 *
 * <p>Любая неопределённость (относительный путь, выход за корень, UNC) означает
 * «не удалять». Класс без зависимостей от Android: проверка ссылок приходит снаружи.
 */
public final class ChatAudioFiles {

    /** Проверка «путь является символической ссылкой». */
    public interface Links {
        boolean isLink(File file);
    }

    private ChatAudioFiles() {
    }

    /**
     * Удаляет перечисленные файлы, принадлежащие каталогу этого чата.
     *
     * @param chatDir каталог реплик чата, {@code filesDir/audio/<chatId>}
     * @param paths   пути из БД и из приватной памяти; null, пустые и повторы пропускаются
     * @param links   проверка символических ссылок; null — проверки нет, ничего не удаляем
     * @return сколько файлов реально удалено
     */
    public static int delete(File chatDir, Collection<String> paths, Links links) {
        if (chatDir == null || paths == null || paths.isEmpty() || links == null) return 0;
        File root = normalize(chatDir);
        if (root == null) return 0;
        // Сам каталог чата и его родитель (filesDir/audio) обязаны быть обычными каталогами.
        File parentDir = root.getParentFile();
        if (links.isLink(root) || (parentDir != null && links.isLink(parentDir))) return 0;
        List<String> seen = new ArrayList<String>();
        int removed = 0;
        for (String path : paths) {
            if (path == null) continue;
            String raw = path.trim();
            if (raw.length() == 0 || seen.contains(raw)) continue;
            seen.add(raw);
            File file = normalize(new File(raw));
            if (file == null) continue;
            File parent = file.getParentFile();
            if (parent == null || !parent.equals(root)) continue; // только прямые дети каталога чата
            if (links.isLink(file)) continue;
            if (!file.isFile()) continue;
            if (file.delete()) removed++;
        }
        return removed;
    }

    /**
     * Абсолютный путь без ссылок и без {@code .}/{@code ..}.
     *
     * @return null для относительных путей, UNC, выхода выше корня и пустых строк
     */
    static File normalize(File file) {
        if (file == null) return null;
        // Относительный путь сначала стал бы абсолютным от текущего каталога процесса, и при
        // совпадении cwd с каталогом чата такой путь прошёл бы проверку вложенности.
        if (!file.isAbsolute()) return null;
        String path = file.getAbsolutePath();
        if (path.length() == 0) return null;
        if (path.startsWith("\\\\") || path.startsWith("//")) return null; // UNC не разбираем
        char sep = File.separatorChar;
        String[] raw = path.split("[\\\\/]+");
        String prefix;
        int from;
        if (raw.length > 0 && raw[0].length() == 2 && raw[0].charAt(1) == ':'
                && Character.isLetter(raw[0].charAt(0))) {
            prefix = raw[0] + sep; // "C:\"
            from = 1;
        } else if (path.charAt(0) == sep || path.charAt(0) == '/' || path.charAt(0) == '\\') {
            prefix = String.valueOf(sep); // "/"
            from = (raw.length > 0 && raw[0].length() == 0) ? 1 : 0;
        } else {
            return null; // без корня разбирать нечего (после проверки isAbsolute сюда не попадаем)
        }
        List<String> parts = new ArrayList<String>();
        for (int i = from; i < raw.length; i++) {
            String part = raw[i];
            if (part.length() == 0 || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (parts.isEmpty()) return null; // выше корня — отказываем
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        StringBuilder out = new StringBuilder(prefix);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(sep);
            out.append(parts.get(i));
        }
        return new File(out.toString());
    }
}
