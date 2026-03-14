package me.karboom.java.iSerf.llm.image;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单次调用的输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Output {
    public List<String> images;
}
