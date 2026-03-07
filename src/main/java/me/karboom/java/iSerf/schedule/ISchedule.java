package me.karboom.java.iSerf.schedule;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public interface ISchedule {

    void addPlan(Plan plan);
    void removePlan(String planId);
}
