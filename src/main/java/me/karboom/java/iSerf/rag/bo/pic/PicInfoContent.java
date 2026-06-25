package me.karboom.java.iSerf.rag.bo.pic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PicInfoContent {
    public String title;
    public String text;
    public String summary;
}
