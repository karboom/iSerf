package me.karboom.java.iSerf.rag.bo.pic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PicInfoTitle {
    public String title;
    public String fatherTitle;
}
