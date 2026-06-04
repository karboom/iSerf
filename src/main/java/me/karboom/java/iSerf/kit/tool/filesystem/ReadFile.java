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

/**
 * 从文件中读取内容
 */
@Slf4j
public class ReadFile implements FunctionWrapper<ReadFile.Parameter> {

    public static String description = "从文件中读取内容";

    public static class Parameter {
        @JsonPropertyDescription("要读取的文件路径")
        @JsonProperty(required = true)
        public String path;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        var rootPath = ctx.agent.workDir;
        if (rootPath == null) {
            throw ErrorUtil.make("Agent workDir is null");
        }

        var filePath = Path.of(params.path);
        var fullPath = rootPath.resolve(filePath).normalize();

        if (!fullPath.startsWith(rootPath)) {
            throw ErrorUtil.make("访问被拒绝：路径在根目录之外");
        }

        log.debug("readFile: path=%s".formatted(fullPath));

        var content = Files.readString(fullPath);

        return CallResult.builder()
                .llm(content)
                .build();
    }
}