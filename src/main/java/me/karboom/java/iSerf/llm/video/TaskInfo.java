package me.karboom.java.iSerf.llm.video;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskInfo {
    public String id;
    public String status;

    public Output result;
}
