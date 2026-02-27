package me.karboom.java.iSerf.util;

import cn.hutool.core.util.IdUtil;

public class DataUtil {
    static public String getFlakeId() {
        var id =  IdUtil.getSnowflakeNextId();

        return String.valueOf(id);
    }
}
