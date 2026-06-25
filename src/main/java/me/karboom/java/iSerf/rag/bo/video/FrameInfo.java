package me.karboom.java.iSerf.rag.bo.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrameInfo {
    public String id;
    public String videoId;
    public List<String> audioIds;

    public String startMs;
    public Integer endMs;

    public String fileName;

    public Object llm;
}
