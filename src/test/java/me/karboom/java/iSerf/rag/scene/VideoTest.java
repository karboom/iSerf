package me.karboom.java.iSerf.rag.scene;

import io.lettuce.core.RedisClient;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.store.RedisStructStore;
import me.karboom.java.iSerf.util.HttpUtil;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import lombok.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Video 测试类
 */
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
            var testVideoPath = java.nio.file.Paths.get("src/test/resources/rag/test-video.mp4");
            
            System.out.println("下载测试视频文件...");
            Files.createDirectories(testVideoPath.getParent());
            var request = new Request.Builder()
                    .url("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/c000002xh60.hnFM10002.mp4")
                    .build();
            try (var response = HttpUtil.getClient().newCall(request).execute()) {
                var body = response.body();
                if (body != null) {
                    Files.copy(body.byteStream(), testVideoPath, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("测试视频文件下载完成：" + testVideoPath);
                }
            }

            video.parseVideo(testVideoPath, true, FrameDesc.class);

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