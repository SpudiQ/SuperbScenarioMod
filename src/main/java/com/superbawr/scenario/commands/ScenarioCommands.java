package com.superbawr.scenario.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.superbawr.scenario.SuperbScenario;
import com.superbawr.scenario.manager.Role;
import com.superbawr.scenario.manager.Team;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class ScenarioCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("scenario").requires(s -> s.hasPermission(2))
            .then(Commands.literal("start")
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                    .executes(c -> startScenario(c, IntegerArgumentType.getInteger(c, "minutes")))
                )
            )
            .then(Commands.literal("stop")
                .executes(ScenarioCommands::stopScenario)
            )
            .then(Commands.literal("base")
                .then(Commands.literal("us")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> setBase(c, Team.US))
                    )
                )
                .then(Commands.literal("ru")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> setBase(c, Team.RU))
                    )
                )
            )
            .then(Commands.literal("zone")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(ScenarioCommands::setZone)
                    )
                )
            )
            .then(Commands.literal("lobby")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ScenarioCommands::setLobby)
                )
            )
            .then(Commands.literal("intel")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ScenarioCommands::setIntel)
                )
            )
            .then(Commands.literal("class")
                .then(Commands.literal("save")
                    .then(Commands.argument("team", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(ScenarioCommands::saveClass)
                        )
                    )
                )
            )
            .then(Commands.literal("set_team")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("team", StringArgumentType.word())
                        .then(Commands.argument("role", StringArgumentType.word())
                            .executes(ScenarioCommands::setTeamAndRole)
                        )
                    )
                )
            )
            .then(Commands.literal("snapshot_radius")
                .then(Commands.argument("radius", IntegerArgumentType.integer(10, 1000))
                    .executes(ScenarioCommands::setSnapshotRadius)
                )
            )
            .then(Commands.literal("mission")
                .then(Commands.literal("title")
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ScenarioCommands::setMissionTitle)
                    )
                )
                .then(Commands.literal("date")
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ScenarioCommands::setMissionDate)
                    )
                )
            )
        );
    }

    private static int startScenario(CommandContext<CommandSourceStack> ctx, int minutes) {
        SuperbScenario.manager.startRound(minutes);
        ctx.getSource().sendSuccess(() -> Component.literal("Scenario starting in 10 seconds! Round time: " + minutes + "m"), true);
        return 1;
    }

    private static int stopScenario(CommandContext<CommandSourceStack> ctx) {
        SuperbScenario.manager.stopRound();
        ctx.getSource().sendSuccess(() -> Component.literal("Scenario forcefully stopped."), true);
        return 1;
    }

    private static int setBase(CommandContext<CommandSourceStack> ctx, Team team) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        SuperbScenario.manager.setBase(team, pos);
        SuperbScenario.manager.savePositions();
        ctx.getSource().sendSuccess(() -> Component.literal("Set " + team.name() + " base to: " + pos.toShortString()), true);
        return 1;
    }

    private static int setZone(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        int r = IntegerArgumentType.getInteger(ctx, "radius");
        SuperbScenario.manager.setZone(pos, r);
        SuperbScenario.manager.savePositions();
        ctx.getSource().sendSuccess(() -> Component.literal("Set capture zone to: " + pos.toShortString() + " with radius " + r), true);
        return 1;
    }

    private static int setIntel(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        SuperbScenario.manager.setIntelPos(pos);
        SuperbScenario.manager.savePositions();
        ctx.getSource().sendSuccess(() -> Component.literal("Set intel location to: " + pos.toShortString()), true);
        return 1;
    }

    private static int setLobby(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        SuperbScenario.manager.setLobbyPos(pos);
        SuperbScenario.manager.savePositions();
        ctx.getSource().sendSuccess(() -> Component.literal("Set lobby location to: " + pos.toShortString()), true);
        return 1;
    }

    private static int saveClass(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String teamStr = StringArgumentType.getString(ctx, "team").toUpperCase();
        String name = StringArgumentType.getString(ctx, "name");
        
        Team t = Team.NONE;
        if (teamStr.equals("US")) t = Team.US;
        else if (teamStr.equals("RU")) t = Team.RU;
        
        if (t == Team.NONE) {
            ctx.getSource().sendSuccess(() -> Component.literal("Invalid team. Use US or RU."), false);
            return 0;
        }

        ListTag invList = new ListTag();
        player.getInventory().save(invList);
        
        SuperbScenario.manager.saveClass(t, name, invList);
        String msg = "Saved current inventory as class " + name + " for team " + t.name();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int setTeamAndRole(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String teamStr = StringArgumentType.getString(ctx, "team").toUpperCase();
        String roleStr = StringArgumentType.getString(ctx, "role").toLowerCase();
        
        Team t = Team.NONE;
        if (teamStr.equals("US")) t = Team.US;
        else if (teamStr.equals("RU")) t = Team.RU;
        
        Role r = Role.fromId(roleStr);
        
        SuperbScenario.manager.setTeam(player.getUUID(), t);
        SuperbScenario.manager.setRole(player.getUUID(), r);
        
        String msg = "Assigned " + player.getName().getString() + " to " + t.name() + " as " + r.getId();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int setSnapshotRadius(CommandContext<CommandSourceStack> ctx) {
        int r = IntegerArgumentType.getInteger(ctx, "radius");
        SuperbScenario.manager.setSnapshotRadius(r);
        ctx.getSource().sendSuccess(() -> Component.literal("Snapshot radius set to " + r + " blocks."), true);
        return 1;
    }

    private static int setMissionTitle(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        SuperbScenario.manager.setMissionTitle(text);
        ctx.getSource().sendSuccess(() -> Component.literal("Mission title set to: " + text), true);
        return 1;
    }

    private static int setMissionDate(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        SuperbScenario.manager.setMissionDate(text);
        ctx.getSource().sendSuccess(() -> Component.literal("Mission date set to: " + text), true);
        return 1;
    }
}
