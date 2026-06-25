package me.karboom.java.iSerf.rag.scene;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.bo.video.AudioInfo;
import me.karboom.java.iSerf.rag.bo.video.FrameInfo;
import me.karboom.java.iSerf.rag.bo.video.VideoInfo;
import me.karboom.java.iSerf.rag.store.MilvusStore;
import me.karboom.java.iSerf.rag.util.ImgCompare;
import org.junit.jupiter.api.AfterEach;
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

    private MilvusClientV2 milvusClient;
    private MilvusStore<VideoInfo> videoStore;
    private MilvusStore<FrameInfo> frameStore;
    private MilvusStore<AudioInfo> audioStore;
    private Video video;
    private OpenAI llm;
    private OpenAI audioLlm;

    @BeforeEach
    void setUp() {
        var uri = System.getenv("MILVUS_URI");
        if (uri == null || uri.isEmpty()) {
            uri = "http://localhost:19530";
        }
        milvusClient = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
        videoStore = new MilvusStore<>(milvusClient, "test_video");
        frameStore = new MilvusStore<>(milvusClient, "test_frame");
        audioStore = new MilvusStore<>(milvusClient, "test_audio");
        llm = getLlm();
        audioLlm = getAudioLlm();
        video = new Video(videoStore, frameStore, audioStore, llm, audioLlm);
    }

    @AfterEach
    void tearDown() {
        if (milvusClient != null) {
            milvusClient.close();
        }
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

    // region detectSilence
    @Nested
    class DetectSilence {

        @Test
        @Timeout(value = 2 * 60)
        void testDetectSilence() {
            var testAudioPath = Path.of("src/test/resources/rag/scene/video/test-1.wav");

            var silences = video.detectSilence(testAudioPath);

            assertNotNull(silences, "静音片段列表不应为空");

            log.debug(" testDetectSilence 检测到静音片段数量: {}", silences.size());
            for (var i = 0; i < silences.size(); i++) {
                var segment = silences.get(i);
                log.debug(" testDetectSilence 片段 {}: 开始={}ms, 结束={}ms, 持续={}ms", i + 1, segment[0], segment[1], segment[1] - segment[0]);
            }

            if (!silences.isEmpty()) {
                var firstSilence = silences.get(0);
                assertTrue(firstSilence[0] >= 0, "开始时间应大于等于0");
                assertTrue(firstSilence[1] > firstSilence[0], "结束时间应大于开始时间");
            }
        }

        @Test
        @Timeout(value = 2 * 60)
        void testDetectSilence_nonExistentFile() {
            var testAudioPath = Path.of("src/test/resources/rag/scene/video/non-existent.wav");

            assertThrows(Exception.class, () -> video.detectSilence(testAudioPath));
        }
    }
    // endregion

    // region parseAudio
    @Nested
    class ParseAudio {

        @Test
        @Timeout(value = 10 * 60)
        @SneakyThrows
        void testParseAudio() {
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

            log.debug(" testParseAudio audioId: {}, type: {}, text: {}, startMs: {}, endMs: {}",
                    audioInfo.getId(), audioInfo.getType(), audioInfo.getText(), audioInfo.getStartMs(), audioInfo.getEndMs());
        }

        @Test
        @Timeout(value = 2 * 60)
        @SneakyThrows
        void testParseAudio_nonExistentFile() {
            var testVideoPath = Path.of("src/test/resources/rag/scene/video/non-existent.mp4");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_parse_audio_error");
            java.nio.file.Files.createDirectories(outputDir);

            assertThrows(Exception.class, () -> video.parseAudio(testVideoPath, outputDir));
        }
    }
    // endregion
}
