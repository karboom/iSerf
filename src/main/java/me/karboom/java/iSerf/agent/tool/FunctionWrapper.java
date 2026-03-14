package me.karboom.java.iSerf.agent.tool;

import java.util.Map;

@FunctionalInterface
public interface FunctionWrapper {
    String run(Context ctx, Map<String, Object> params);
}
