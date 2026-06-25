package me.karboom.java.iSerf.rag.bo.audio;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioAnalysisResult {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Segment {
        @JsonPropertyDescription("音频类型，如：对话、音乐、噪音、静音等")
        @JsonProperty(required = true)
        public String type;

        @JsonPropertyDescription("内容描述")
        @JsonProperty(required = true)
        public String text;
    }

    @JsonPropertyDescription("音频分析结果列表，每个元素代表一个音频片段")
    @JsonProperty(required = true)
    public List<Segment> elements;
}
