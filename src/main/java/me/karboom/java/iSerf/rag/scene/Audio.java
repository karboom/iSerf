package me.karboom.java.iSerf.rag.scene;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.rag.bo.audio.AudioAnalysisResult;
import me.karboom.java.iSerf.rag.bo.audio.AudioInfo;
import me.karboom.java.iSerf.rag.bo.audio.AudioSegment;
import me.karboom.java.iSerf.rag.store.IStore;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
public class Audio {

    private final IStore<AudioInfo> audioStore;
    private final IText audioLlm;

    /**
     * 解析音频文件并转录
     * - 通过 FFmpeg + silencedetect 将音频切分到split_audio文件夹，文件名格式为[start]_[end].wav
     * - 遍历split_audio，使用IText分析音频内容
     * @param audioFile 音频文件路径
     * @param outputDir 输出目录
     * @return 音频信息列表
     */
    @SneakyThrows
    public ArrayList<AudioInfo> parseAudio(Path audioFile, Path outputDir) {
        var splitAudioDir = outputDir.resolve("split_audio");

        Files.createDirectories(outputDir);
        Files.createDirectories(splitAudioDir);

        log.debug("<parseAudio> processing audio | path,{}", audioFile);

        var silenceSegments = detectSilence(audioFile);
        log.debug("<parseAudio> detected silence segments | count,{}", silenceSegments.size());

        var audioSegments = extractAudioSegments(audioFile, silenceSegments, splitAudioDir);
        log.debug("<parseAudio> split into audio segments | count,{}", audioSegments.size());

        var audioInfos = new ArrayList<AudioInfo>();
        for (var segment : audioSegments) {
            var audioBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(segment.path));
            var audioUrl = "data:audio/wav;base64,%s".formatted(audioBase64);

            var message = AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.AUDIO)
                    .audio((audioUrl))
                    .text("请分析这段音频内容，识别语音类型（如对话、音乐、噪音等）和文字内容")
                    .build();

            AudioAnalysisResult result = null;
            for (var retry = 0; retry < 3; retry++) {
                try {
                    var analysisResult = audioLlm.query(List.of(message), AudioAnalysisResult.class);
                    result = JSONUtil.parse(analysisResult.getChoices().getFirst().getText(), AudioAnalysisResult.class);
                    break;
                } catch (Exception e) {
                    log.debug("<parseAudio> JSON parse failed, retrying | segment,{},retry,{}", segment.path.getFileName(), retry + 1);
                }
            }
            if (result == null || result.getElements() == null || result.getElements().isEmpty()) {
                result = AudioAnalysisResult.builder()
                        .elements(List.of(AudioAnalysisResult.Segment.builder().type("error").text("").build()))
                        .build();
            }
            log.debug("<parseAudio> analyzed segment | file,{},count,{}", segment.path.getFileName(), result.getElements().size());

            for (var item : result.getElements()) {
                var audioInfo = AudioInfo.builder()
                        .id(DataUtil.getFlakeId())
                        .type(item.getType())
                        .text(item.getText())
                        .startMs(segment.startMs)
                        .endMs(segment.endMs)
                        .frameIds(new ArrayList<>())
                        .build();

                audioInfos.add(audioInfo);
            }
        }

        log.debug("<parseAudio> completed | segments,{}", audioInfos.size());
        audioStore.create(audioInfos);
        return audioInfos;
    }

    @SneakyThrows
    public ArrayList<Long[]> detectSilence(Path audioFile) {
        var silences = new ArrayList<Long[]>();

        var process = new ProcessBuilder(
                "ffmpeg",
                "-i", audioFile.toString(),
                "-af", "silencedetect=noise=-30dB:d=0.5",
                "-f", "null",
                "-"
        ).redirectErrorStream(false).start();

        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            Long silenceStart = null;

            while ((line = reader.readLine()) != null) {
                if (line.contains("silence_start:")) {
                    var value = line.split("silence_start:")[1].trim();
                    if (!value.equals("-inf")) {
                        silenceStart = (long) (Double.parseDouble(value) * 1000);
                    }
                } else if (line.contains("silence_end:")) {
                    var value = line.split("silence_end:")[1].split(" ")[1].trim();
                    var silenceEnd = (long) (Double.parseDouble(value) * 1000);
                    if (silenceStart != null) {
                        silences.add(new Long[]{silenceStart, silenceEnd});
                    }
                    silenceStart = null;
                }
            }
        }

        process.waitFor(60, TimeUnit.SECONDS);
        return silences;
    }

    private ArrayList<AudioSegment> extractAudioSegments(Path audioFile, ArrayList<Long[]> silences, Path outputDir) throws Exception {
        var segments = new ArrayList<AudioSegment>();

        var ffprobe = new FFprobe();
        var probeResult = ffprobe.probe(audioFile.toString());
        var durationMs = (long) (probeResult.getFormat().duration * 1000);

        var audioStarts = new ArrayList<Long>();
        var audioEnds = new ArrayList<Long>();

        if (silences.isEmpty()) {
            audioStarts.add(0L);
            audioEnds.add(durationMs);
        } else {
            var firstSilence = silences.get(0);
            if (firstSilence[0] > 0) {
                audioStarts.add(0L);
                audioEnds.add(firstSilence[0]);
            }

            for (var i = 0; i < silences.size() - 1; i++) {
                var currentEnd = silences.get(i)[1];
                var nextStart = silences.get(i + 1)[0];
                if (nextStart > currentEnd) {
                    audioStarts.add(currentEnd);
                    audioEnds.add(nextStart);
                }
            }

            var lastSilence = silences.get(silences.size() - 1);
            if (durationMs > lastSilence[1]) {
                audioStarts.add(lastSilence[1]);
                audioEnds.add(durationMs);
            }
        }

        var ffmpeg = new FFmpeg();
        for (var i = 0; i < audioStarts.size(); i++) {
            var startMs = audioStarts.get(i);
            var endMs = audioEnds.get(i);
            var durationSec = (endMs - startMs) / 1000.0;

            if (durationSec < 0.5) {
                log.debug("<extractAudioSegments> skipping segment (too short) | startMs,{},endMs,{}", startMs, endMs);
                continue;
            }

            var outputFile = outputDir.resolve("%s_%s.wav".formatted(startMs, endMs));
            var ffmpegBuilder = new FFmpegBuilder()
                    .setInput(audioFile.toString())
                    .setStartOffset(startMs, TimeUnit.MILLISECONDS)
                    .addOutput(outputFile.toString())
                    .addExtraArgs("-t", String.valueOf(durationSec))
                    .done();

            ffmpeg.run(ffmpegBuilder);
            log.debug("<extractAudioSegments> extracted segment | startMs,{},endMs,{}", startMs, endMs);

            segments.add(AudioSegment.builder()
                    .path(outputFile)
                    .startMs(startMs.intValue())
                    .endMs(endMs.intValue())
                    .build());
        }

        return segments;
    }
}
