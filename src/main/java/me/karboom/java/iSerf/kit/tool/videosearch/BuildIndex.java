package me.karboom.java.iSerf.kit.tool.videosearch;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.util.JSONUtil;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 根据给定的视频文件，创建索引
 */
@Slf4j
public class BuildIndex implements FunctionWrapper<BuildIndex.Parameter> {

    public static String description = "当用户需要给指定视频构建索引，使用这个工具";

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Text {
        public String content;
        public List<Integer> boundingBox;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Subject {
        public String name;
        public List<Integer> boundingBox;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Relation {
        public String relation;
        public String sourceSubjectName;
        public String targetSubjectName;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Description {
        /**
         * 画面中的物体
         */
        public List<Subject> subjects;
        /**
         * 物体间关系
         */
        public List<Relation> subjectRelation;
        /**
         * 文本内容
         */
        public List<Text> texts;
    }

    public static class Parameter {
        @JsonPropertyDescription("视频文件地址")
        @JsonProperty(required = true)
        public String url;

        @JsonPropertyDescription("期望创建的索引标签")
        @JsonProperty(required = true)
        public String[] tags;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        var url = params.url;
        var tags = params.tags;

        log.debug("buildIndex url: {}, tags: {}", url, tags);

        var taskId = IdUtil.simpleUUID();
        var outputDir = Paths.get("build", "srag_output", taskId).toAbsolutePath();
        Files.createDirectories(outputDir);

        log.debug("buildIndex outputDir: {}", outputDir);

        var ffprobe = new FFprobe();
        var probeResult = ffprobe.probe(url);
        var format = probeResult.getFormat();
        var videoDuration = (int) Math.ceil(format.duration);
        log.debug("buildIndex videoDuration: {}s", videoDuration);

        var ffmpeg = new FFmpeg();
        var outputPath = outputDir.resolve("frame_%04d.jpg").toString();
        ffmpeg.run(new FFmpegBuilder()
                .setInput(url)
                .addOutput(outputPath)
                .addExtraArgs("-vf", "fps=1/30")
                .done());
        log.debug("buildIndex extracted all frames");

        var descriptions = new ArrayList<Description>();

        Files.list(outputDir)
                .filter(Files::isRegularFile)
                .parallel()
                .forEach(frameFile -> {
                    try {
                        var fileName = frameFile.getFileName().toString();
                        var description = analyzeFrame(frameFile, fileName);
                        synchronized (descriptions) {
                            descriptions.add(description);
                        }
                        log.debug("buildIndex analyzed frame: {}", fileName);
                    } catch (Exception e) {
                        log.error("buildIndex error at file: {}", frameFile, e);
                    }
                });

        var resultFile = outputDir.resolve("result.json").toFile();
        var resultContent = JSONUtil.stringify(descriptions);
        FileUtil.writeUtf8String(resultContent, resultFile);

        log.debug("buildIndex saved result: {}", resultFile);

        var resultJson = """
                {
                    "taskId": "%s",
                    "outputDir": "%s",
                    "frameCount": %d,
                    "resultFile": "%s"
                }
                """.formatted(taskId, outputDir, descriptions.size(), resultFile);

        return CallResult.builder()
                .direct(JSONUtil.parse(resultJson))
                .build();
    }

    private Description analyzeFrame(Path frameFile, String fileName) {
        log.debug("analyzeFrame frameFile: {}, fileName: {}", frameFile, fileName);

        var imageBase64 = FileUtil.readBytes(frameFile.toFile());
        var base64Str = Base64.getEncoder().encodeToString(imageBase64);
        var imageUrl = "data:image/jpeg;base64,%s".formatted(base64Str);

        var item = AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.IMAGE)
                .files(List.of(imageUrl))
                .text("请描述这张图片的内容，提取关键信息，必须是中文结果")
                .build();

        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        var llmConfig = new HashMap<String, Object>();

        var llm = new OpenAI("qwen3-vl-plus", llmConfig, apiKey, url, 1);

        var description = Description.builder().build();

        var output = llm.query(List.of(item), Description.class);
        if (output != null && output.choices != null && !output.choices.isEmpty()) {
            var content = output.choices.get(0).text;
            description = JSONUtil.parse(content, Description.class);
            log.debug("<description> ", content);
        }

        return description;
    }
}