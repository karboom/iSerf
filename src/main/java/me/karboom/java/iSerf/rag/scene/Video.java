package me.karboom.java.iSerf.rag.scene;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.rag.bo.audio.AudioInfo;
import me.karboom.java.iSerf.rag.bo.video.FrameInfo;
import me.karboom.java.iSerf.rag.bo.video.VideoInfo;
import me.karboom.java.iSerf.rag.store.IStore;
import me.karboom.java.iSerf.util.DataUtil;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiFunction;

@Slf4j
@AllArgsConstructor
public class Video {

    private final IStore<VideoInfo> videoStore;
    private final IStore<FrameInfo> frameStore;
    private final IText llm;
    private final Audio audio;

    /**
     * 解析视频文件为结构化数据
     * - ffprobe解析视频基本信息，保存到videoStore
     * - 创建临时目录video_parse_[videoId]，根据指定的fps将视频切分为图片，保存到frames子目录，需要输出进度
     * - 遍历frames子目录，根据自定义函数去除重复图片，去重的图片放到filter_frames目录下，命名为[开始序号]_[结束序号].jpg
     * - 根据参数决定，ffmpeg 提取音轨，保存到audio子目录
     * - 所有filter_frames图片通过 IText.query 理解，并且输出为 frameDesc
     * - 音频通过 Audio.parseAudio 理解，转换为多个 AudioInfo
     *
     */
    @SneakyThrows
    public void parseVideo(Path videoFile, Path outputDir, Integer fps, BiFunction<Path, Path, Boolean> func, Boolean needAudio, Class<?> frameDesc) {
        var videoId = DataUtil.getFlakeId();
        Files.createDirectories(outputDir);

        var frameDir = outputDir.resolve("frames");
        var audioDir = outputDir.resolve("audios");
        Files.createDirectories(frameDir);
        Files.createDirectories(audioDir);

        // 1. ffprobe 解析视频基本信息
        var ffprobe = new FFprobe();
        var probeResult = ffprobe.probe(videoFile.toString());
        var duration = probeResult.getFormat().duration;

        var videoInfo = VideoInfo.builder()
                .id(videoId)
                .startTime(LocalDateTime.now())
                .fps(fps)
                .build();
        videoStore.create(videoInfo);
        log.debug("<parseVideo> video info | videoId,{},fps,{},duration,{}", videoId, fps, duration);

        // 2. 根据指定的 fps 将视频切分为图片
        var ffmpeg = new FFmpeg();
        var frameOutputPattern = frameDir.resolve("%d.jpg").toString();

        var ffmpegBuilder = new FFmpegBuilder()
                .setInput(videoFile.toString())
                .addOutput(frameOutputPattern)
                .addExtraArgs("-vf", "fps=%d".formatted(fps))
                .addExtraArgs("-q:v", "2")
                .done();

        log.debug("<parseVideo> extracting frames | fps,{}", fps);
        ffmpeg.run(ffmpegBuilder);
        log.debug("<parseVideo> extracted frames | dir,{}", frameDir);

        // 3. 遍历 frames 子目录，根据自定义函数去除重复图片
        var frameFiles = Files.list(frameDir)
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparingInt(p -> {
                    var name = p.getFileName().toString().replace(".jpg", "");
                    return Integer.parseInt(name);
                }))
                .toList();

        var filterFramesDir = outputDir.resolve("filter_frames");
        Files.createDirectories(filterFramesDir);

        var totalFrames = frameFiles.size();
        var processedFrames = 0;
        Path baseFrameFile = null;

        for (var i = 0; i < frameFiles.size(); i++) {
            var frameFile = frameFiles.get(i);
            var fileName = frameFile.getFileName().toString();
            var frameNumber = Integer.parseInt(fileName.replace(".jpg", ""));

            if (baseFrameFile == null) {
                baseFrameFile = frameFile;
                continue;
            }

            var baseFrameNumber = Integer.parseInt(baseFrameFile.getFileName().toString().replace(".jpg", ""));
            var isDuplicate = func.apply(baseFrameFile, frameFile);

            if (isDuplicate) {
                processedFrames++;
                if (processedFrames % 10 == 0 || processedFrames == totalFrames) {
                    log.debug("<parseVideo> deduplication progress | processed,{},total,{}", processedFrames, totalFrames);
                }
                continue;
            }

            if (baseFrameNumber < frameNumber - 1) {
                var filterFrameName = "%d_%d.jpg".formatted(baseFrameNumber, frameNumber - 1);
                Files.copy(baseFrameFile, filterFramesDir.resolve(filterFrameName));
                log.debug("<parseVideo> duplicate frames | start,{},end,{}", baseFrameNumber, frameNumber - 1);
            }

            baseFrameFile = frameFile;

            processedFrames++;
            if (processedFrames % 10 == 0 || processedFrames == totalFrames) {
                log.debug("<parseVideo> deduplication progress | processed,{},total,{}", processedFrames, totalFrames);
            }
        }

        // 复制最后一个基准文件
        if (baseFrameFile != null) {
            var baseFrameNumber = Integer.parseInt(baseFrameFile.getFileName().toString().replace(".jpg", ""));
            var filterFrameName = "%d_%d.jpg".formatted(baseFrameNumber, baseFrameNumber);
            Files.copy(baseFrameFile, filterFramesDir.resolve(filterFrameName));
            log.debug("<parseVideo> copied last base frame | name,{}", filterFrameName);
        }

        // 从 filter_frames 目录读取所有去重后的图片，统一通过 IText.query 理解
        var filterFrameFiles = Files.list(filterFramesDir)
                .filter(Files::isRegularFile)
                .sorted()
                .toList();

        var frameInfos = new ArrayList<FrameInfo>();
        for (var frameFile : filterFrameFiles) {
            var fileName = frameFile.getFileName().toString();
            var frameId = DataUtil.getFlakeId();
            var startMs = Integer.parseInt(fileName.split("_")[0]) * 1000 / fps;

            var imageBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(frameFile));
            var imageUrl = "data:image/jpeg;base64,%s".formatted(imageBase64);

            var message = AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.IMAGE)
                    .files(List.of(imageUrl))
                    .text("请描述这张图片的内容，提取关键信息")
                    .build();

            var output = llm.query(List.of(message), frameDesc);

            var frameInfo = FrameInfo.builder()
                    .id(frameId)
                    .videoId(videoId)
                    .audioIds(new ArrayList<>())
                    .startMs(String.valueOf(startMs))
                    .fileName(fileName)
                    .llm(output)
                    .build();

            frameInfos.add(frameInfo);
            log.debug("<parseVideo> analyzed frame | file,{}", fileName);
        }

        frameStore.create(frameInfos);
        log.debug("<parseVideo> analyzed frames | count,{}", frameInfos.size());

        // 5. 根据参数决定，ffmpeg 提取音轨并转录
        var audioInfos = new ArrayList<AudioInfo>();
        if (needAudio) {
            var audioFile = audioDir.resolve("audio.wav");
            extractAudio(videoFile, audioFile);
            audioInfos = audio.parseAudio(audioFile, audioDir);
        }

        log.debug("<parseVideo> completed | frames,{},audios,{}", frameInfos.size(), audioInfos.size());
    }

    /**
     * 从视频文件中提取音轨
     * @param videoFile 视频文件路径
     * @param audioFile 输出音频文件路径
     */
    @SneakyThrows
    public void extractAudio(Path videoFile, Path audioFile) {
        var ffmpeg = new FFmpeg();
        var ffmpegBuilder = new FFmpegBuilder()
                .setInput(videoFile.toString())
                .addOutput(audioFile.toString())
                .addExtraArgs("-vn")
                .addExtraArgs("-ac", "1")
                .addExtraArgs("-ar", "16000")
                .done();

        ffmpeg.run(ffmpegBuilder);
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
            log.debug("<extractFrameTimestamps> error, using fallback", e);
            
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
                log.error("<extractFrameTimestamps> fallback error", ex);
            }
        }

        return timestamps;
    }
}
