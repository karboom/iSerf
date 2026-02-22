package me.karboom.java.iSerf.llm.embedding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Input {
    public String text;
    public String image;
    public String video;
}
