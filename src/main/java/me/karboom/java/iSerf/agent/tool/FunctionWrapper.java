package me.karboom.java.iSerf.agent.tool;

import java.util.Map;

@FunctionalInterface
public interface FunctionWrapper<T> {
    CallResult run(Context ctx, T params);
}
