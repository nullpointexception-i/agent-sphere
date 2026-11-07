package com.buukle.agent.bootstrap.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard: the two correct ways to write "{@code <}" in MyBatis annotation SQL must not be mixed.
 *
 * <p>Background: {@code @Select("...")} goes through XML parsing only when the SQL is wrapped in {@code <script>},
 * and only then is {@code &lt;} decoded to {@code <}. Without the wrapper the string reaches JDBC <b>verbatim</b>, so
 * {@code created_at &lt; ?} becomes {@code created_at lt ?} in PG →
 * {@code ERROR: column "lt" does not exist} (really hit: three SessionCleanupMapper queries missed the script wrapper).
 * Conversely, wrapping in {@code <script>} but leaving a bare {@code <} blows up at parse time.
 *
 * <p>No existing unit test can catch this — mappers are fully mocked and CI has no database. A static scan therefore covers it,
 * following the same idea as {@code DefaultResourceTemplateMigrationTest} (scan files to prevent recurrence).
 */
class MapperSqlEscapingTest {

    /** Annotation reading: cover both text blocks and single-line strings. */
    private static final Pattern TEXT_BLOCK = Pattern.compile(
            "@(Select|Insert|Update|Delete)\\(\\s*\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern SINGLE_LINE = Pattern.compile(
            "@(Select|Insert|Update|Delete)\\(\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\)");

    /**
     * A complete open/close tag (must consume the closing {@code >}). Without this {@code [^>]*},
     * the {@code >} of {@code </script>} is treated as a "leftover bare angle bracket" and the whole file is a false positive.
     */
    private static final Pattern MYBATIS_TAG = Pattern.compile(
            "</?(script|foreach|if|choose|when|otherwise|trim|where|set|bind|property)\\b[^>]*>",
            Pattern.DOTALL);

    private static final Pattern XML_ENTITY = Pattern.compile("&(lt|gt|amp);");

    @Test
    @DisplayName("注解 SQL 的尖括号写法必须自洽：无 script 不得转义，有 script 必须转义")
    void mapperSqlAngleBracketUsageMustBeConsistent() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path javaFile : sourceFiles()) {
            String source = readString(javaFile);
            collect(source, TEXT_BLOCK, javaFile, violations);
            collect(source, SINGLE_LINE, javaFile, violations);
        }

        assertTrue(violations.isEmpty(),
                () -> "注解 SQL 的 < 写法不一致（详见每条说明）：\n  " + String.join("\n  ", violations));
    }

    private void collect(String source, Pattern pattern, Path file, List<String> violations) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String sql = matcher.group(2);
            int line = (int) source.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
            if (sql.contains("<script")) {
                // Direction 2: bare angle brackets remain after stripping all MyBatis tags → XML parsing will blow up
                String rest = MYBATIS_TAG.matcher(sql).replaceAll("");
                if (rest.contains("<") || rest.contains(">")) {
                    violations.add(file + ":" + line + " 包了 <script> 但内部有未转义的尖括号："
                            + snippet(rest));
                }
            } else if (XML_ENTITY.matcher(sql).find()) {
                // Direction 1: escaped but not wrapped in script → the entity reaches PG verbatim and is taken as a column name
                violations.add(file + ":" + line + " 用了 &lt;/&gt; 却没有 <script> 包裹，"
                        + "实体不会被解码，PG 会把 lt 当列名");
            }
        }
    }

    /**
     * Locate the aggregate root (containing the main sources of every agent-sphere-* module).
     *
     * <p>Cannot simply use {@code user.dir/../..}: surefire's user.dir is the module directory,
     * but when run from the IDE or the aggregate root user.dir is one level up, so the depth varies. Instead, walk up to an anchor —
     * the first directory containing {@code agent-sphere-common} is the root.
     */
    private List<Path> sourceFiles() throws IOException {
        Path repoRoot = findRepoRoot();
        assertTrue(repoRoot != null,
                "定位不到 agent-sphere 源码根（user.dir=" + Paths.get("").toAbsolutePath() + "）。"
                        + "守卫测试必须真的扫到源码，不能静默跳过。");

        // Collect every src/main/java depth-agnostically: sub-modules are nested
        //（agent-sphere/agent-sphere-instance/agent-sphere-instance-repository/src/main/java），
        // Assuming "modules are direct children of the root" scans only a few flat modules, missing most mappers without any error.
        List<Path> javaRoots = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(repoRoot)) {
            tree.filter(Files::isDirectory)
                    .filter(p -> p.toString().replace('\\', '/').endsWith("src/main/java"))
                    .forEach(javaRoots::add);
        }
        assertTrue(!javaRoots.isEmpty(), "在 " + repoRoot + " 下没找到任何 src/main/java，守卫失效");

        List<Path> files = new ArrayList<>();
        for (Path javaRoot : javaRoots) {
            collectJava(javaRoot, files);
        }
        // Lower bound on count: the real tree has about 600 files. Scanning fewer means the path assumption is wrong again,
        // and a guard that "only scans a few files" is exactly how such a bug masquerades as passing.
        assertTrue(files.size() >= 200,
                "只扫到 " + files.size() + " 个源文件（预期 200+），路径假设可能有误，守卫形同虚设");
        return files;
    }

    /** Walk up from the current directory to the level containing {@code agent-sphere-common}. */
    private Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agent-sphere-common"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void collectJava(Path dir, List<Path> out) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .forEach(out::add);
        }
    }

    private String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String snippet(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > 80 ? flat.substring(0, 80) + "..." : flat;
    }
}