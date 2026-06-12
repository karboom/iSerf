package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 TeamLifecycle 实现，Team 工厂方法
 */
public class TestTeamLifecycle extends TeamLifecycle {

    @Override
    public Team createTeam(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public List<Team> listTeam(ContainerServer.Context ctx, ObjectNode params) {
        return new ArrayList<>();
    }

    @Override
    public Team removeTeam(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Team editTeam(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public Team detailTeam(ContainerServer.Context ctx, ObjectNode params) {
        return null;
    }

    @Override
    public ObjectNode serializeTeam(Team team) {
        return JSONUtil.convert(team);
    }
}
