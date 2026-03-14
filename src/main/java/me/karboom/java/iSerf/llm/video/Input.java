package me.karboom.java.iSerf.llm.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Input {
    public String prompt;

    /**
     * 首帧图片
     */
    public String firstFrame;

    /**
     * 尾帧图片
     */
    public String lastFrame;

    /**
     * 原始视频
     */
    public String sourceVideo;

    /**
     * 音频
     */
    public String audio;
}
