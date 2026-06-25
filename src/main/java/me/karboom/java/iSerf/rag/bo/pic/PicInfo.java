package me.karboom.java.iSerf.rag.bo.pic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PicInfo {
    public Boolean isToc;

    public List<PicInfoTitle> titles;

    public List<PicInfoContent> contents;
}
