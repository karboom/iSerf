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

        return new OpenAI("qwen-plus", llmConfig, apiKey, url, 1);
    }

    @Test
    void testParseVideo() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            var testVideoPath = java.nio.file.Paths.get("src/test/resources/rag/scene/video/test-1.mp4");


            var imgCompare = new ImgCompare();
            video.parseVideo(testVideoPath, 2, imgCompare::hashCompare, true, FrameDesc.class);

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
}