package me.karboom.java.iSerf.llm.image;


import me.karboom.java.iSerf.llm.video.TaskInfo;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

public class Dashscope implements IImage {
    @Override
    public String generate(Input input) {
        return "";
    }

    @Override
    public TaskInfo getTaskInfo(String taskId) {
        return null;
    }
}
