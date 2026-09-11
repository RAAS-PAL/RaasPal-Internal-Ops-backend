package com.raaspal.robotrecommendation.casereport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metro-province list has to reach the application in Thai, not only be written in it.
 *
 * <p>Spring Boot reads {@code .properties} files as ISO-8859-1. Thai typed straight into
 * one is UTF-8 on disk, so every name loaded as a run of Latin-1 characters that no board
 * value could equal, and a ticket in กรุงเทพมหานคร would have been given the 5-day
 * upcountry threshold without a word. The names are unicode-escaped instead, and these
 * fail if raw non-ASCII comes back into a value.
 *
 * <p>Both files, because the test file shadows the main one rather than merging with it.
 * Read from source rather than the classpath for the same reason: on the test classpath
 * {@code application.properties} is always the test copy.
 */
class MetroProvincesPropertyTest {

    private static final String KEY = "app.casereport.metro-provinces";

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/resources/application.properties",
            "src/test/resources/application.properties"})
    void theThaiNamesSurviveSpringsPropertiesLoader(String path) throws IOException {
        String value = new PropertiesPropertySourceLoader()
                .load(path, new FileSystemResource(path))
                .stream()
                .map(source -> source.getProperty(KEY))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .findFirst()
                .orElseThrow(() -> new AssertionError(KEY + " is missing from " + path));

        assertThat(value).contains("Bangkok", "กรุงเทพมหานคร", "กทม", "นครปฐม");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/resources/application.properties",
            "src/test/resources/application.properties"})
    void noValueCarriesRawNonAsciiText(String path) throws IOException {
        List<String> lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
        List<String> offending = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).stripLeading();
            // Comments are discarded by the loader, so Thai in them is harmless.
            if (line.startsWith("#") || line.startsWith("!")) continue;
            if (line.chars().anyMatch(c -> c > 0x7F)) {
                offending.add(path + ":" + (i + 1));
            }
        }

        assertThat(offending)
                .as("Spring reads .properties as ISO-8859-1, so non-ASCII in a value has to "
                        + "be written as a unicode escape")
                .isEmpty();
    }
}
