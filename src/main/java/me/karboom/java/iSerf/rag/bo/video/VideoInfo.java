package me.karboom.java.iSerf.rag.bo.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoInfo {
    public String id;

    public LocalDateTime startTime;

    public Integer fps;
}
