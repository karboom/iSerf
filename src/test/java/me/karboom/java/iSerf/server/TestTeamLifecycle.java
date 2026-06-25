package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.server.bo.Context;
import me.karboom.java.iSerf.server.lifecycle.ITeamLifecycle;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 TeamLifecycle 实现，Team 工厂方法
 */
public class TestTeamLifecycle implements ITeamLifecycle {

    @Override
    public Team createTeam(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public List<Team> listTeam(Context ctx, ObjectNode params) {
        return new ArrayList<>();
    }

    @Override
    public Team removeTeam(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Team editTeam(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Team detailTeam(Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public ObjectNode serializeTeam(Team team) {
        return JSONUtil.convert(team);
    }
}
