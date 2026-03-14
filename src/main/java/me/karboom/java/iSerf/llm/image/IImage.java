package me.karboom.java.iSerf.llm.image;

import me.karboom.java.iSerf.llm.video.TaskInfo;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;


/**
 * 每次调用的input即时决定，params初始化类的时候决定
 */
public interface IImage {
    /**
     * 获取视频生成任务ID
     */
    String generate(Input input);

    /**
     * 查询任务状态
     */
    TaskInfo getTaskInfo(String taskId);
}