package me.karboom.java.iSerf.rag.util;

import lombok.extern.slf4j.Slf4j;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.util.ErrorUtil;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class ImgCompare {

    private static final double DEFAULT_THRESHOLD = 0.2;

    @SneakyThrows
    public Boolean hashCompare(Path path1, Path path2) {
        return hashCompare(path1, path2, DEFAULT_THRESHOLD);
    }

    @SneakyThrows
    public Boolean hashCompare(Path path1, Path path2, double threshold) {
        log.debug("hashCompare Comparing images: {} and {} with threshold {}", path1, path2, threshold);

        var tempDiffPath = Files.createTempFile("img_diff_", ".png");

        try {
            var compareCmd = "compare -metric AE %s %s %s 2>&1".formatted(
                path1.toAbsolutePath(),
                path2.toAbsolutePath(),
                tempDiffPath.toAbsolutePath()
            );
            log.debug("hashCompare Executing compare command: {}", compareCmd);

            var process = new ProcessBuilder("bash", "-c", compareCmd)
                .redirectErrorStream(true)
                .start();

            var output = new String(process.getInputStream().readAllBytes()).trim();
            var exitCode = process.waitFor();

            log.debug("hashCompare Compare output: {}, exitCode: {}", output, exitCode);

            var differentPixels = parseCompareOutput(output);
            var maxPixels = getImagePixelCount(path1);
            var similarityScore = (double) differentPixels / maxPixels;

            log.debug("hashCompare Different pixels: {}, Total pixels: {}, Similarity score: {}", 
                differentPixels, maxPixels, similarityScore);

            return similarityScore < threshold;

        } finally {
            Files.deleteIfExists(tempDiffPath);
        }
    }

    @SneakyThrows
    private Long parseCompareOutput(String output) {
        if (output == null || output.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(output);
        } catch (NumberFormatException e) {
            log.debug("parseCompareOutput Failed to parse output: {}", output);
            return 0L;
        }
    }

    @SneakyThrows
    private Long getImagePixelCount(Path imagePath) {
        var identifyCmd = "identify -format '%%w %%h' %s".formatted(imagePath.toAbsolutePath());
        var process = new ProcessBuilder("bash", "-c", identifyCmd)
            .redirectErrorStream(true)
            .start();

        var output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();

        var parts = output.split("\\s+");
        if (parts.length >= 2) {
            var width = Long.parseLong(parts[0]);
            var height = Long.parseLong(parts[1]);
            return width * height;
        }

        log.debug("getImagePixelCount Failed to parse dimensions: {}", output);
        throw ErrorUtil.make("Failed to get image dimensions: " + imagePath);
    }

}