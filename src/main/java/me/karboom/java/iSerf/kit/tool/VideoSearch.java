package me.karboom.java.iSerf.kit.tool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.llm.text.Base;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.util.JSONUtil;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;

import java.util.HashMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class VideoSearch {
    private Base llm;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Description {

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
        public static class Subject{
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

    public VideoSearch() {
    }

    public VideoSearch(Base llm) {
        this.llm = llm;
    }

    /**
     * 混合结构化搜索和向量匹配的搜索
     * @return
     */
    public Tool mixedSearch() {
        return Tool.builder()
                .name("mixed_search")
                .description("当用户需要查询特定画面的时候，使用这个工具")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("sql", "string", "结构化查询", true),
                        new Tool.Parameter("textForVectorMatch", "string", "用于向量匹配的文本描述", true)
                ))
                .function((ctx, params) -> {
                    var query = params.get("sql").toString();
                    var text = params.get("textForVectorMatch");

                    log.debug("mixedSearch query: {}, text: {}", query, text);

                    var result = """
                            {
                                "query": "%s",
                                "text": "%s"
                            }
                            """.formatted(query, text);

                    return CallResult.builder()
                            .direct(JSONUtil.parse(result))
                            .build();
                })
                .build();
    }

    /**
     * 根据给定的视频文件，创建索引
     * @return
     */
    public Tool buildIndex() {
        return Tool.builder()
                .name("build_index")
                .description("当用户需要给指定视频构建索引，使用这个工具")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("url", "string", "视频文件地址", true),
                        new Tool.Parameter("tags", "array", "期望创建的索引标签", false)
                ))
                .function((ctx, params) -> {
                    try {
                        var url = params.get("url").toString();
                        var tags = params.get("tags");

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
                    } catch (Exception e) {
                        log.error("buildIndex error", e);
                        throw new RuntimeException(e);
                    }
                })
                .build();
    }

    private Description analyzeFrame(Path frameFile, String fileName) {
        log.debug("analyzeFrame frameFile: {}, fileName: {}", frameFile, fileName);

        var imageBase64 = FileUtil.readBytes(frameFile.toFile());
        var base64Str = java.util.Base64.getEncoder().encodeToString(imageBase64);
        var imageUrl = "data:image/jpeg;base64,%s".formatted(base64Str);

        var item = Message.builder()
                .role(Message.ROLE.USER)
                .type(Message.TYPE.IMAGE)
                .images(List.of(imageUrl))
                .text("请描述这张图片的内容，提取关键信息，必须是中文结果")
                .build();

        var description = Description.builder()
                .build();

        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = "https://dashscope.aliyuncs.com/compatible-mode/v1";


        var llmConfig = new HashMap<String, Object>();
//        llmConfig.put("temperature", 0.7);
//        llmConfig.put("top_p", 0.9);

        var llm = new OpenAI("qwen3-vl-plus", llmConfig, apiKey, url, 1);

        var output = llm.query(List.of(item), Description.class);
        if (output != null && output.choices != null && !output.choices.isEmpty()) {
            var content = output.choices.get(0).text;
            description = JSONUtil.parse(content, Description.class);
            log.debug("<description> ", content);
        }

        return description;
    }
}