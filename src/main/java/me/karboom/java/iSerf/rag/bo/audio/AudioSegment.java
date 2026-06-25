package me.karboom.java.iSerf.rag.bo.audio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioSegment {
    public Path path;
    public Integer startMs;
    public Integer endMs;
}
