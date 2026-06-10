package me.karboom.java.iSerf.billing;

import java.util.List;

public interface ILedger {
    void record(Cost cost);

    /**
     * 查询指定目标的计费记录
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @return 匹配的计费记录列表
     */
    List<Cost> query(String targetType, String targetId);

    /**
     * 根据用户查询所有计费记录
     * @param userId 用户ID
     * @return 该用户的所有计费记录列表
     */
    List<Cost> queryByUser(String userId);
}
