package me.karboom.java.iSerf.server;

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
    public Team createTeam(Server.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public List<Team> listTeam(Server.Context ctx, ObjectNode params) {
        return new ArrayList<>();
    }

    @Override
    public Team removeTeam(Server.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Team editTeam(Server.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Team detailTeam(Server.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public ObjectNode serializeTeam(Team team) {
        return JSONUtil.convert(team);
    }
}
