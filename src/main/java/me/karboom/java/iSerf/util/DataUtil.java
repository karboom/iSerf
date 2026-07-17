package me.karboom.java.iSerf.util;

import cn.hutool.core.util.IdUtil;

public class DataUtil {
    static public String getFlakeId() {
        var id =  IdUtil.getSnowflakeNextId();

        return String.valueOf(id);
    }

    /**
     * 清理 JSON 内容，去除 markdown 代码块标记等多余字符
     */
    static public String cleanJsonContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 去除开头的 ```json 或 ```
        content = content.replaceAll("^```\\w*\\s*\\n?", "");
        // 去除结尾的 ```
        content = content.replaceAll("\\n?```\\s*$", "");
        // 去除首尾空白
        return content.trim();
    }
}
