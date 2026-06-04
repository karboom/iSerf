package me.karboom.java.iSerf.kit.tool.filesystem;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.util.ErrorUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 列出目录内容
 */
@Slf4j
public class ListDirectory implements FunctionWrapper<ListDirectory.Parameter> {

    public static String description = "列出目录内容";

    public static class Parameter {
        @JsonPropertyDescription("要列出的目录路径")
        @JsonProperty(required = false)
        public String path;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        var rootPath = ctx.agent.workDir;
        if (rootPath == null) {
            throw ErrorUtil.make("Agent workDir is null");
        }

        var dirPath = params.path;
        if (dirPath == null || dirPath.isEmpty()) {
            dirPath = ".";
        }

        var dir = Path.of(dirPath);
        var fullPath = rootPath.resolve(dir).normalize();

        if (!fullPath.startsWith(rootPath)) {
            throw ErrorUtil.make("访问被拒绝：路径在根目录之外");
        }

        log.debug("listDirectory: path=%s".formatted(fullPath));

        var files = Files.list(fullPath)
                .map(path -> path.getFileName().toString())
                .toArray(String[]::new);

        return CallResult.builder()
                .llm(Arrays.toString(files))
                .build();
    }
}