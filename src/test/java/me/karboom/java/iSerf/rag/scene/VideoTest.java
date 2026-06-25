package me.karboom.java.iSerf.rag.scene;

import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.bo.audio.AudioInfo;
import me.karboom.java.iSerf.rag.bo.video.FrameInfo;
import me.karboom.java.iSerf.rag.bo.video.VideoInfo;
import me.karboom.java.iSerf.rag.store.MemoryStore;
import me.karboom.java.iSerf.rag.util.ImgCompare;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import lombok.*;

import java.nio.file.Path;
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

    private MemoryStore<VideoInfo> videoStore;
    private MemoryStore<FrameInfo> frameStore;
    private MemoryStore<AudioInfo> audioStore;
    private Video video;
    private OpenAI llm;

    @BeforeEach
    void setUp() {
        videoStore = new MemoryStore<>();
        frameStore = new MemoryStore<>();
        audioStore = new MemoryStore<>();
        llm = getLlm();
        var audioLlm = getAudioLlm();
        var audio = new Audio(audioStore, audioLlm);
        video = new Video(videoStore, frameStore, llm, audio);
    }

    private OpenAI getLlm() {
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

    private OpenAI getAudioLlm() {
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

    // region parseVideo
    @Nested
    class ParseVideo {

        @Test
        @Timeout(value = 10 * 60)
        @SneakyThrows
        void testParseVideo() {
            var testVideoPath = Path.of("src/test/resources/rag/scene/video/test-1.mp4");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_video_parse");
            java.nio.file.Files.createDirectories(outputDir);

            var imgCompare = new ImgCompare();
            video.parseVideo(testVideoPath, outputDir, 2, imgCompare::hashCompare, Boolean.TRUE, FrameDesc.class);

            var videoInfos = videoStore.getByIds(List.of());
            assertNotNull(videoInfos);
            assertTrue(videoInfos.size() > 0);

            var frameInfos = frameStore.getByIds(List.of());
            assertNotNull(frameInfos);
            assertTrue(frameInfos.size() > 0);

            var audioInfos = audioStore.getByIds(List.of());
            assertNotNull(audioInfos);

            log.debug(" testParseVideo 解析完成：视频数={}, 帧数={}, 音频数={}", videoInfos.size(), frameInfos.size(), audioInfos.size());
        }

        @Test
        @Timeout(value = 2 * 60)
        @SneakyThrows
        void testParseVideo_nonExistentFile() {
            var testVideoPath = Path.of("src/test/resources/rag/scene/video/non-existent.mp4");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_video_parse_error");
            java.nio.file.Files.createDirectories(outputDir);

            var imgCompare = new ImgCompare();
            assertThrows(Exception.class, () ->
                    video.parseVideo(testVideoPath, outputDir, 2, imgCompare::hashCompare, Boolean.TRUE, FrameDesc.class)
            );
        }
    }
    // endregion

}
