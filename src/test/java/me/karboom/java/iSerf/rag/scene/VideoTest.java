package me.karboom.java.iSerf.rag.scene;

import io.lettuce.core.RedisClient;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.store.RedisStructStore;
import me.karboom.java.iSerf.rag.util.ImgCompare;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import lombok.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Video 测试类
 */
@Slf4j
public class VideoTest {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrameDesc {
        public String description;
        public List<String> objects;
        public String scene;
    }

    private RedisClient redisClient;
    private RedisStructStore<Video.VideoInfo> videoStore;
    private RedisStructStore<Video.FrameInfo> frameStore;
    private RedisStructStore<Video.AudioInfo> audioStore;
    private Video video;
    private OpenAI llm;
    private OpenAI audioLlm;
    private static final String KEY_PREFIX = "test:video:%s";

    @BeforeEach
    void setUp() {
        redisClient = RedisClient.create("redis://localhost");
        videoStore = new RedisStructStore<Video.VideoInfo>(redisClient, KEY_PREFIX){};
        frameStore = new RedisStructStore<Video.FrameInfo>(redisClient, KEY_PREFIX){};
        audioStore = new RedisStructStore<Video.AudioInfo>(redisClient, KEY_PREFIX){};
        llm = getLlm();
        audioLlm = getAudioLlm();
        video = new Video(videoStore, frameStore, audioStore, llm, audioLlm);
    }

    @AfterEach
    void tearDown() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    public OpenAI getLlm() {
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

    public OpenAI getAudioLlm() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("top_p", 0.9);

        return new OpenAI("qwen3-omni-flas", llmConfig, apiKey, url, 1);
    }

    @Test
    void testParseVideo() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            var testVideoPath = java.nio.file.Paths.get("src/test/resources/rag/scene/video/test-1.mp4");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_video_parse");
            java.nio.file.Files.createDirectories(outputDir);

            var imgCompare = new ImgCompare();
            video.parseVideo(testVideoPath, outputDir, Integer.valueOf(2), imgCompare::hashCompare, Boolean.TRUE, FrameDesc.class);

            var videoInfos = videoStore.getByIds(List.of());
            assertNotNull(videoInfos);
            assertTrue(videoInfos.size() > 0);

            var frameInfos = frameStore.getByIds(List.of());
            assertNotNull(frameInfos);
            assertTrue(frameInfos.size() > 0);

            var audioInfos = audioStore.getByIds(List.of());
            assertNotNull(audioInfos);

            System.out.println("解析完成：视频数=" + videoInfos.size() + ", 帧数=" + frameInfos.size() + ", 音频数=" + audioInfos.size());
        });
    }

    @Test
    void testDetectSilence() throws Exception {
        var testAudioPath = Path.of("src/test/resources/rag/scene/video/test-1.wav");

        var silences = assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            return video.detectSilence(testAudioPath);
        });

        assertNotNull(silences, "静音片段列表不应为空");

        System.out.println("检测到静音片段数量: " + silences.size());
        for (var i = 0; i < silences.size(); i++) {
            var segment = silences.get(i);
            System.out.println("片段 " + (i + 1) + ": 开始=" + segment[0] + "ms, 结束=" + segment[1] + "ms, 持续=" + (segment[1] - segment[0]) + "ms");
        }

        if (!silences.isEmpty()) {
            var firstSilence = silences.get(0);
            assertTrue(firstSilence[0] >= 0, "开始时间应大于等于0");
            assertTrue(firstSilence[1] > firstSilence[0], "结束时间应大于开始时间");
        }
    }

    @Test
    void testParseAudio() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            var testVideoPath = Path.of("src/test/resources/rag/scene/video/test-1.mp4");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_parse_audio_" + System.currentTimeMillis());
            java.nio.file.Files.createDirectories(outputDir);

            var audioInfos = video.parseAudio(testVideoPath, outputDir);

            assertNotNull(audioInfos);
            assertTrue(audioInfos.size() > 0, "应该至少有一个音频片段");

            var audioInfo = audioInfos.get(0);
            assertNotNull(audioInfo.getId(), "音频 ID 不应为空");
            assertNotNull(audioInfo.getType(), "音频类型不应为空");
            assertNotNull(audioInfo.getText(), "音频文本不应为空");
            assertNotNull(audioInfo.getStartMs(), "开始时间不应为空");
            assertNotNull(audioInfo.getEndMs(), "结束时间不应为空");
            assertNotNull(audioInfo.getFrameIds(), "帧 ID 列表不应为空");

            log.debug("testSplitAndTranscribeAudio audioId: {}, type: {}, text: {}, startMs: {}, endMs: {}", 
                    audioInfo.getId(), audioInfo.getType(), audioInfo.getText(), audioInfo.getStartMs(), audioInfo.getEndMs());

            System.out.println("音频转录完成：音频数=" + audioInfos.size());
            System.out.println("第一个音频片段：id=" + audioInfo.getId() + ", type=" + audioInfo.getType() + ", text=" + audioInfo.getText());
        });
    }
}
