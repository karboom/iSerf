package me.karboom.java.iSerf.llm.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 单次调用的输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Output {
    public String videoUrl;
}
