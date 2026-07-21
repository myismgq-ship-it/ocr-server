package com.gsafety.ocrtool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitecturePackageTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void legacyPackagesAreNotUsed() throws Exception {
        List<String> sources = javaSources()
                .map(this::read)
                .toList();

        assertThat(sources)
                .noneMatch(source -> source.contains("package com.gsafety.ocrtool.api"))
                .noneMatch(source -> source.contains("package com.gsafety.ocrtool.ocr"))
                .noneMatch(source -> source.contains("package com.gsafety.ocrtool.properties"));
    }

    @Test
    void webPackageOnlyContainsEntrypoints() throws Exception {
        List<Path> webSources = javaSources()
                .filter(path -> path.toString().contains("\\web\\") || path.toString().contains("/web/"))
                .toList();

        assertThat(webSources)
                .extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder(
                        "OcrController.java",
                        "PlanDigitizeController.java",
                        "GlobalExceptionHandler.java");
        assertThat(webSources)
                .allSatisfy(path -> assertThat(read(path)).doesNotContain("public record "));
    }

    @Test
    void dtoAndRecognitionDoNotDependOnWeb() throws Exception {
        List<Path> restrictedSources = javaSources()
                .filter(path -> path.toString().contains("\\dto\\")
                        || path.toString().contains("/dto/")
                        || path.toString().contains("\\recognition\\")
                        || path.toString().contains("/recognition/"))
                .toList();

        assertThat(restrictedSources)
                .allSatisfy(path -> assertThat(read(path)).doesNotContain("com.gsafety.ocrtool.web"));
    }

    private Stream<Path> javaSources() throws Exception {
        return Files.walk(MAIN_JAVA)
                .filter(path -> path.toString().endsWith(".java"));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception ex) {
            throw new IllegalStateException("读取源码失败：" + path, ex);
        }
    }
}
