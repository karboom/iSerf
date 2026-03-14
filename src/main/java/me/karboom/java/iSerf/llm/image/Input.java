package me.karboom.java.iSerf.llm.image;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Input {
    public String prompt;

    /**
     * 内容图片
     */
    public List<String> contentImages;
}
