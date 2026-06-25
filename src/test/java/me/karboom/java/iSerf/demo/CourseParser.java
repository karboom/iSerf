package me.karboom.java.iSerf.demo;


import lombok.*;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.bo.audio.AudioInfo;
import me.karboom.java.iSerf.rag.bo.video.FrameInfo;
import me.karboom.java.iSerf.rag.bo.video.VideoInfo;
import me.karboom.java.iSerf.rag.scene.Audio;
import me.karboom.java.iSerf.rag.scene.Video;
import me.karboom.java.iSerf.rag.store.MemoryStore;
import me.karboom.java.iSerf.util.JSONUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 视频课程分析 Demo
 * 功能：提取音频并通过 LLM 分析课程难点、知识点，生成结构化数据，
 * 便于后续制作前端的课程提醒、随堂测试等
 */
@Slf4j
public class CourseParser {

    // region 数据模型

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseAnalysis {
        public String topic;
        public String summary;
        public List<CourseSection> sections;
        public List<KnowledgePoint> knowledgePoints;
        public List<DifficultPoint> difficultPoints;
        public List<QuizQuestion> quizQuestions;
        public List<CourseReminder> reminders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseSection {
        public String title;
        public Integer startMs;
        public Integer endMs;
        public String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgePoint {
        public String id;
        public String title;
        public String description;
        public Integer startMs;
        public Integer endMs;
        public String difficulty; // EASY, MEDIUM, HARD
        public String category;
        public List<String> relatedConcepts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DifficultPoint {
        public String id;
        public String title;
        public String description;
        public Integer timestampMs;
        public String explanation;
        public List<String> commonMistakes;
        public List<String> learningTips;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuizQuestion {
        public String id;
        public Integer timestampMs;
        public String type; // SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE
        public String question;
        public List<String> options;
        public Integer answerIndex;
        public String explanation;
        public String relatedKnowledgePointId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseReminder {
        public Integer timestampMs;
        public String type; // KEY_POINT, DIFFICULTY, REVIEW, PREVIEW
        public String content;
        public String priority; // HIGH, MEDIUM, LOW
    }

    // endregion

    // region 成员变量

    private MemoryStore<VideoInfo> videoStore;
    private MemoryStore<FrameInfo> frameStore;
    private MemoryStore<AudioInfo> audioStore;
    private Video video;
    private Audio audio;
    private OpenAI llm;

    // endregion

    public static void main(String[] args) {
        var demo = new CourseParser();
        demo.run();
    }

    @SneakyThrows
    public void run() {
        try {
            // 初始化
            init();

            // 1. 提取音频并转录
            log.info("========== 步骤1: 提取音频并转录 ==========");
            var testVideoPath = Path.of("src/test/resources/rag/scene/video/test-1.mp4");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "course_analysis_demo_" + System.currentTimeMillis());
            Files.createDirectories(outputDir);

            var audioFile = outputDir.resolve("audio.wav");
            video.extractAudio(testVideoPath, audioFile);
            var audioInfos = audio.parseAudio(audioFile, outputDir);
            log.info("音频提取完成，共 {} 个片段", audioInfos.size());

            // 2. 构建分析上下文
            log.info("========== 步骤2: 构建分析上下文 ==========");
            var context = buildAnalysisContext(audioInfos);
            log.debug("run context length: {}", context.length());

            // 3. LLM 分析课程内容
            log.info("========== 步骤3: LLM 分析课程内容 ==========");
            var courseAnalysis = analyzeCourse(context);
            log.info("课程分析完成: 主题={}, 章节数={}, 知识点数={}, 难点数={}, 测试题数={}, 提醒数={}",
                    courseAnalysis.getTopic(),
                    courseAnalysis.getSections().size(),
                    courseAnalysis.getKnowledgePoints().size(),
                    courseAnalysis.getDifficultPoints().size(),
                    courseAnalysis.getQuizQuestions().size(),
                    courseAnalysis.getReminders().size());

            // 4. 输出结果
            log.info("========== 步骤4: 输出结果 ==========");
            var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            var resultDir = outputDir.resolve("results");
            Files.createDirectories(resultDir);

            var analysisFile = resultDir.resolve("course_analysis_%s.json".formatted(timestamp));
            Files.writeString(analysisFile, JSONUtil.stringify(courseAnalysis));
            log.info("课程分析结果: {}", analysisFile);

            // 5. 打印摘要
            printSummary(courseAnalysis);

        } finally {
            cleanup();
        }
    }

    // region 初始化与清理

    private void init() {
        videoStore = new MemoryStore<>();
        frameStore = new MemoryStore<>();
        audioStore = new MemoryStore<>();
        llm = createLlm();
        var audioLlm = createAudioLlm();
        audio = new Audio(audioStore, audioLlm);
        video = new Video(videoStore, frameStore, llm, audio);
    }

    private OpenAI createLlm() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("top_p", 0.9);

        return new OpenAI("qwen-plus", llmConfig, apiKey, url, 1);
    }

    private OpenAI createAudioLlm() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("top_p", 0.9);

        return new OpenAI("qwen3-omni-flash", llmConfig, apiKey, url, 1);
    }

    private void cleanup() {
        // MemoryStore 无需清理
    }

    // endregion

    // region 课程分析

    /**
     * 构建分析上下文
     * 将音频转录按时间顺序整合为文本
     */
    private String buildAnalysisContext(List<AudioInfo> audioInfos) {
        var sb = new StringBuilder();
        sb.append("请分析以下视频课程的语音转录内容，提取课程主题、章节结构、知识点、难点，并生成随堂测试题和课程提醒。\n\n");

        // 按时间排序
        var sortedAudios = audioInfos.stream()
                .filter(a -> a.getStartMs() != null && a.getText() != null && !a.getText().isBlank())
                .sorted(Comparator.comparingInt(AudioInfo::getStartMs))
                .toList();

        sb.append("=== 语音转录时间线 ===\n\n");

        for (var audio : sortedAudios) {
            var timeStr = formatTime(audio.getStartMs());
            sb.append("[%s] %s\n".formatted(timeStr, audio.getText()));
        }

        sb.append("\n=== 输出要求 ===\n");
        sb.append("""
                请以 JSON 格式输出课程分析结果，包含以下字段:
                - topic: 课程主题（字符串）
                - summary: 课程整体概述（字符串）
                - sections: 章节列表，每个章节包含:
                  - title: 章节标题
                  - startMs: 开始时间（毫秒）
                  - endMs: 结束时间（毫秒）
                  - summary: 章节摘要
                - knowledgePoints: 知识点列表，每个知识点包含:
                  - id: 知识点ID（如 kp_1, kp_2）
                  - title: 知识点标题
                  - description: 知识点描述
                  - startMs: 开始时间（毫秒）
                  - endMs: 结束时间（毫秒）
                  - difficulty: 难度（EASY/MEDIUM/HARD）
                  - category: 分类
                  - relatedConcepts: 相关概念列表
                - difficultPoints: 难点列表，每个难点包含:
                  - id: 难点ID（如 dp_1, dp_2）
                  - title: 难点标题
                  - description: 难点描述
                  - timestampMs: 出现时间（毫秒）
                  - explanation: 详细解释
                  - commonMistakes: 常见错误列表
                  - learningTips: 学习建议列表
                - quizQuestions: 随堂测试题列表，每题包含:
                  - id: 题目ID（如 qq_1, qq_2）
                  - timestampMs: 建议出现时间（毫秒）
                  - type: 题目类型（SINGLE_CHOICE/MULTIPLE_CHOICE/TRUE_FALSE）
                  - question: 题目内容
                  - options: 选项列表（如 ["A. xxx", "B. xxx", "C. xxx", "D. xxx"]）
                  - answerIndex: 正确答案索引（0开始）
                  - explanation: 答案解析
                  - relatedKnowledgePointId: 关联知识点ID
                - reminders: 课程提醒列表，每条包含:
                  - timestampMs: 提醒时间（毫秒）
                  - type: 提醒类型（KEY_POINT/DIFFICULTY/REVIEW/PREVIEW）
                  - content: 提醒内容
                  - priority: 优先级（HIGH/MEDIUM/LOW）
                """);

        return sb.toString();
    }

    /**
     * 调用 LLM 分析课程内容
     */
    private CourseAnalysis analyzeCourse(String context) {
        var message = AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.TEXT)
                .text(context)
                .build();

        var output = llm.query(List.of(message), CourseAnalysis.class);
        var result = JSONUtil.convert(output.getChoices().getFirst().getText(), CourseAnalysis.class);

        log.debug("analyzeCourse result: {}", result);
        return result;
    }

    // endregion

    // region 辅助方法

    private String formatTime(Integer ms) {
        if (ms == null) return "00:00";
        var totalSeconds = ms / 1000;
        var minutes = totalSeconds / 60;
        var seconds = totalSeconds % 60;
        return "%02d:%02d".formatted(minutes, seconds);
    }

    private void printSummary(CourseAnalysis analysis) {
        log.info("\n========================================");
        log.info("课程分析摘要");
        log.info("========================================");
        log.info("主题: {}", analysis.getTopic());
        log.info("概述: {}", analysis.getSummary());

        log.info("\n--- 章节结构 ---");
        analysis.getSections().forEach(s ->
                log.info("  [{}-{}] {}", formatTime(s.getStartMs()), formatTime(s.getEndMs()), s.getTitle()));

        log.info("\n--- 知识点 ---");
        analysis.getKnowledgePoints().forEach(kp ->
                log.info("  [{}] {} (难度: {})", formatTime(kp.getStartMs()), kp.getTitle(), kp.getDifficulty()));

        log.info("\n--- 难点 ---");
        analysis.getDifficultPoints().forEach(dp ->
                log.info("  ⚠️ {} ({})", dp.getTitle(), formatTime(dp.getTimestampMs())));

        log.info("\n--- 随堂测试题 ---");
        analysis.getQuizQuestions().forEach(qq ->
                log.info("  📝 [{}] {} ({})", formatTime(qq.getTimestampMs()), qq.getQuestion().substring(0, Math.min(50, qq.getQuestion().length())), qq.getType()));

        log.info("\n--- 课程提醒 ---");
        analysis.getReminders().forEach(r ->
                log.info("  🔔 [{}] [{}] {}", formatTime(r.getTimestampMs()), r.getType(), r.getContent().substring(0, Math.min(50, r.getContent().length()))));

        log.info("========================================\n");
    }

    // endregion
}

