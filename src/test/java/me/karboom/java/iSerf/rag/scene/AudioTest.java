package me.karboom.java.iSerf.rag.scene;

import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.rag.bo.audio.AudioInfo;
import me.karboom.java.iSerf.rag.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import lombok.*;

import java.nio.file.Path;
import java.util.HashMap;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audio 测试类
 */
@Slf4j
public class AudioTest {

    private MemoryStore<AudioInfo> audioStore;
    private Audio audio;

    @BeforeEach
    void setUp() {
        audioStore = new MemoryStore<>();
        var audioLlm = getAudioLlm();
        audio = new Audio(audioStore, audioLlm);
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

        return new OpenAI("qwen3.5-omni-flash", llmConfig, apiKey, url, 1);
    }

    // region detectSilence
    @Nested
    class DetectSilence {

        @Test
        @Timeout(value = 2 * 60)
        void testDetectSilence() {
            var testAudioPath = Path.of("src/test/resources/rag/scene/video/test-1.wav");

            var silences = audio.detectSilence(testAudioPath);

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

            assertThrows(Exception.class, () -> audio.detectSilence(testAudioPath));
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
            var testAudioPath = Path.of("src/test/resources/rag/scene/video/test-1.wav");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_parse_audio_" + System.currentTimeMillis());
            java.nio.file.Files.createDirectories(outputDir);

            var audioInfos = audio.parseAudio(testAudioPath, outputDir);

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
            var testAudioPath = Path.of("src/test/resources/rag/scene/video/non-existent.wav");
            var outputDir = Path.of(System.getProperty("java.io.tmpdir"), "test_parse_audio_error");
            java.nio.file.Files.createDirectories(outputDir);

            assertThrows(Exception.class, () -> audio.parseAudio(testAudioPath, outputDir));
        }
    }
    // endregion
}
