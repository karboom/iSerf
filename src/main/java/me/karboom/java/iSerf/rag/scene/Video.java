package me.karboom.java.iSerf.rag.scene;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.rag.store.IStructStore;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.ErrorUtil;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
public class Video {

    private final IStructStore<VideoInfo> videoStore;
    private final IStructStore<FrameInfo> frameStore;
    private final IStructStore<AudioInfo> audioStore;
    private final IText llm;
    private final IText audioLlm;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class VideoInfo {
        public String id;

        public LocalDateTime startTime;

        public Integer fps;

    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class FrameInfo {
        public String id;
        public String videoId;
        public List<String> audioIds;

        public String startMs;

        public String fileName;

        public Object llm;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class AudioInfo {
        public String id;
        public String type;
        public String text;
        public Integer startMs;
        public Integer endMs;
        public List<String> frameIds;
    }

    /**
     * 解析视频文件为结构化数据，并且通过 IStore 保存
     * 1. ffmpeg 场景变化阈值 0.3 + 每 1 秒强制 1 帧，文件命名为 %d.jpg,  从 log 中提取时间戳，找出 jpg 对应的毫秒
     * 2. ffmpeg 提取音轨
     * 3. 所有图片通过 IText.query 理解，并且输出为 frameDesc
     * 4. 音频通过 IText.query 理解，转换为多个 AudioInfo
     *
     * Todo 背景音乐、音效、人声的分离提取
     */
    @SneakyThrows
    public void parseVideo(Path videoFile, Boolean needAudio, Class<?> frameDesc) {
        var videoId = DataUtil.getFlakeId();
        var outputDir = videoFile.getParent().resolve("video_parse_" + videoId);

        Files.createDirectories(outputDir);
        var frameDir = outputDir.resolve("frames");
        var audioDir = outputDir.resolve("audios");
        Files.createDirectories(frameDir);
        Files.createDirectories(audioDir);

        // 1. 获取视频信息
        var ffprobe = new FFprobe();
        var probeResult = ffprobe.probe(videoFile.toString());
        var videoStream = probeResult.getStreams().stream()
                .filter(s -> "video".equals(s.codec_type))
                .findFirst()
                .orElse(null);
        var fps = videoStream != null ? videoStream.r_frame_rate.intValue() : 30;
        var duration = probeResult.getFormat().duration;

        var videoInfo = VideoInfo.builder()
                .id(videoId)
                .startTime(LocalDateTime.now())
                .fps(fps)
                .build();

        videoStore.create(videoInfo);
        log.debug("parseVideo videoId: {}, fps: {}, duration: {}", videoId, fps, duration);

        // 2. 提取场景变化帧 + 每秒强制 1 帧
        var ffmpeg = new FFmpeg();
        var frameOutputPattern = frameDir.resolve("%d.jpg").toString();

        var ffmpegBuilder = new FFmpegBuilder()
                .setInput(videoFile.toString())
                .addOutput(frameOutputPattern)
                .addExtraArgs("-vf", "select='gt(scene,0.3)',fps=1")
                .addExtraArgs("-vsync", "vfr")
                .addExtraArgs("-q:v", "2")
                .done();

        ffmpeg.run(ffmpegBuilder);
        log.debug("parseVideo extracted frames to: {}", frameDir);

        // 3. 从 ffmpeg 日志中提取时间戳映射
        var frameTimestamps = extractFrameTimestamps(videoFile.toString(), frameDir);
        log.debug("parseVideo extracted {} frame timestamps", frameTimestamps.size());

        // 4. 分析每一帧
        var frameInfos = new ArrayList<FrameInfo>();
        var frameFiles = Files.list(frameDir)
                .filter(Files::isRegularFile)
                .sorted()
                .toList();

        for (var frameFile : frameFiles) {
            var frameId = DataUtil.getFlakeId();
            var fileName = frameFile.getFileName().toString();
            var frameNumber = Integer.parseInt(fileName.replace(".jpg", ""));
            var startMs = frameTimestamps.getOrDefault(frameNumber, 0L);

            // 使用 IText 分析图片
            var imageBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(frameFile));
            var imageUrl = "data:image/jpeg;base64,%s".formatted(imageBase64);

            var message = Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.IMAGE)
                    .images(List.of(imageUrl))
                    .text("请描述这张图片的内容，提取关键信息")
                    .build();

            var output = llm.query(List.of(message), frameDesc);

            var frameInfo = FrameInfo.builder()
                    .id(frameId)
                    .videoId(videoId)
                    .audioIds(new ArrayList<>())
                    .startMs(startMs.toString())
                    .fileName(fileName)
                    .llm(output)
                    .build();

            frameInfos.add(frameInfo);
            log.debug("parseVideo analyzed frame: {}", fileName);
        }

        frameStore.create(frameInfos);
        log.debug("parseVideo analyzed {} frames", frameInfos.size());

        // 5. 提取音频（如果需要）
        var audioInfos = new ArrayList<AudioInfo>();
        if (needAudio) {
            var audioOutput = audioDir.resolve("audio.wav").toString();
            var audioFfmpeg = new FFmpegBuilder()
                    .setInput(videoFile.toString())
                    .addOutput(audioOutput)
                    .addExtraArgs("-vn")
                    .addExtraArgs("-acodec", "pcm_s16le")
                    .addExtraArgs("-ar", "16000")
                    .addExtraArgs("-ac", "1")
                    .done();

            ffmpeg.run(audioFfmpeg);
            log.debug("parseVideo extracted audio to: {}", audioOutput);

            // 音频分割和转录
            audioInfos = splitAndTranscribeAudio(audioDir.resolve("audio.wav").toString(), videoId, frameInfos);
            audioStore.create(audioInfos);
        }

        log.debug("parseVideo completed: {} frames, {} audio segments", frameInfos.size(), audioInfos.size());
    }


    /**
     * 整体提取并转录音频
     * @param audioFile 音频文件路径
     * @param videoId 视频 ID
     * @param frameInfos 帧信息列表，用于关联音频和帧
     * @return 音频信息列表
     */
    @SneakyThrows
    private ArrayList<AudioInfo> splitAndTranscribeAudio(String audioFile, String videoId, ArrayList<FrameInfo> frameInfos) {
        var audioInfos = new ArrayList<AudioInfo>();

        // 1. 获取音频时长
        var ffprobe = new FFprobe();
        var probeResult = ffprobe.probe(audioFile);
        var duration = probeResult.getFormat().duration;
        log.debug("splitAndTranscribeAudio duration: {}", duration);

        // 2. 直接转录整个音频文件
        var audioId = DataUtil.getFlakeId();
        var startTimeMs = 0;
        var endTimeMs = (int) (duration * 1000);

        // 读取音频文件并转换为 base64
        var audioBytes = Files.readAllBytes(Path.of(audioFile));
        var audioBase64 = Base64.getEncoder().encodeToString(audioBytes);

        // 使用 IText 转录音频
        var message = Message.builder()
                .role(Message.ROLE.USER)
                .type(Message.TYPE.AUDIO)
                .audio("data:audio/wav;base64," + audioBase64)
                .text("请转录这段音频的内容，提取所有对话和重要声音信息")
                .build();

        var output = audioLlm.query(List.of(message), String.class);

        var audioInfo = AudioInfo.builder()
                .id(audioId)
                .type("speech")
                .text(output != null && output.getChoices() != null && !output.getChoices().isEmpty() ? output.getChoices().get(0).getText() : "")
                .startMs(startTimeMs)
                .endMs(endTimeMs)
                .frameIds(new ArrayList<>())
                .build();

        // 关联音频和帧
        for (var frameInfo : frameInfos) {
            var frameStartMs = Long.parseLong(frameInfo.getStartMs());
            if (frameStartMs >= startTimeMs && frameStartMs < endTimeMs) {
                audioInfo.getFrameIds().add(frameInfo.getId());
                frameInfo.getAudioIds().add(audioInfo.getId());
            }
        }

        audioInfos.add(audioInfo);
        log.debug("splitAndTranscribeAudio transcribed audio: {}, text: {}", audioId, audioInfo.getText());

        return audioInfos;
    }

    /**
     * 从 ffmpeg 日志中提取帧时间戳映射
     * @param videoFile 视频文件路径
     * @param frameDir 帧输出目录
     * @return 帧编号到毫秒的映射
     */
    private Map<Integer, Long> extractFrameTimestamps(String videoFile, Path frameDir) {
        var timestamps = new HashMap<Integer, Long>();
        var frameCount = 0;

        try {
            // 使用 ffprobe 获取场景变化信息
            var process = new ProcessBuilder(
                    "ffprobe",
                    "-f", "lavfi",
                    "-i", "movie=%s,select=gt(scene\\,0.3)".formatted(videoFile.replace("'", "'\\''")),
                    "-show_entries", "frame=pkt_pts_time",
                    "-of", "csv=p=0"
            ).redirectErrorStream(true).start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        var time = Double.parseDouble(line.trim());
                        timestamps.put(frameCount++, (long) (time * 1000));
                    } catch (NumberFormatException e) {
                        // 跳过无效行
                    }
                }
            }

            process.waitFor();

        } catch (Exception e) {
            log.debug("extractFrameTimestamps error, using fallback", e);
            
            // fallback: 使用文件修改时间
            try {
                var frameFiles = Files.list(frameDir)
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();

                var baseTime = Files.getLastModifiedTime(frameFiles.get(0)).toMillis();
                
                for (var i = 0; i < frameFiles.size(); i++) {
                    var frameFile = frameFiles.get(i);
                    var fileTime = Files.getLastModifiedTime(frameFile).toMillis();
                    timestamps.put(i, fileTime - baseTime);
                }
            } catch (IOException ex) {
                log.error("extractFrameTimestamps fallback error", ex);
            }
        }

        return timestamps;
    }
}