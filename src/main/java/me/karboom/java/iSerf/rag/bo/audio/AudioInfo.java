package me.karboom.java.iSerf.rag.bo.audio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioInfo {
    public String id;
    public String type;
    public String text;
    public Integer startMs;
    public Integer endMs;
    public List<String> frameIds;
}
