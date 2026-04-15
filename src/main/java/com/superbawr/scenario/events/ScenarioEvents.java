package com.superbawr.scenario.events;

import com.superbawr.scenario.SuperbScenario;
import com.superbawr.scenario.manager.GameState;
import com.superbawr.scenario.manager.Role;
import com.superbawr.scenario.manager.SavedClass;
import com.superbawr.scenario.manager.Team;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;

import java.util.UUID;

public class ScenarioEvents {

    private int tickCounter = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        var m = SuperbScenario.manager;
        if (m.getServer() == null) return;
        GameState state = m.getState();

        if (state == GameState.STARTING || state == GameState.IN_PROGRESS) {
            tickCounter++;
            if (tickCounter % 20 == 0) { // Every second
                if (state == GameState.STARTING) {
                    m.decrementCountdown();
                    int c = m.getCountdown();

                    // COD-style cinematic intro sequence
                    if (c == 10) {
                        // Black screen with slow fade
                        for (ServerPlayer p : m.getServer().getPlayerList().getPlayers()) {
                            // Set timing: fade-in 40 ticks, stay 200 ticks, fade-out 20 ticks
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(60, 140, 20));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                                Component.literal(" ")
                            ));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                                Component.literal("§8Loading operation...")
                            ));
                        }
                    } else if (c == 7) {
                        for (ServerPlayer p : m.getServer().getPlayerList().getPlayers()) {
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 80, 20));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                                Component.literal("§l§f" + m.getMissionTitle())
                            ));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                                Component.literal("§7" + m.getMissionDate())
                            ));
                        }
                    } else if (c == 3) {
                        for (ServerPlayer p : m.getServer().getPlayerList().getPlayers()) {
                            Team t = m.getTeam(p.getUUID());
                            Role r = m.getRole(p.getUUID());
                            String teamColor = (t == Team.US) ? "§9" : "§c";
                            String teamName = (t == Team.US) ? "UNITED STATES" : "RUSSIA";
                            String roleName = (r != null) ? r.getId().toUpperCase() : "SOLDIER";

                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 60, 20));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                                Component.literal(teamColor + "§l" + teamName)
                            ));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                                Component.literal("§f" + p.getName().getString() + " §7| §e" + roleName)
                            ));
                        }
                    } else if (c == 1) {
                        for (ServerPlayer p : m.getServer().getPlayerList().getPlayers()) {
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 40, 40));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                                Component.literal("§a§lGO")
                            ));
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                                Component.literal("§fComplete the mission, soldier.")
                            ));
                        }
                    } else if (c == 0) {
                        startGame();
                    }
                } else if (state == GameState.IN_PROGRESS) {
                    m.decrementRemainingTime();
                    int rt = m.getRemainingTimeSeconds();
                    if (rt <= 0) {
                        endGame();
                        return;
                    }

                    // Zone logic
                    checkZoneControl();
                }
            }
        }
    }

    private void startGame() {
        var m = SuperbScenario.manager;
        m.setState(GameState.IN_PROGRESS);
        broadcast("§aThe Round has started! Good luck.");

        // Apply starting state to all players
        for (ServerPlayer player : m.getServer().getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Team team = m.getTeam(id);
            if (team == Team.NONE) continue;

            Role role = m.getRole(id);
            
            // Set lives and gamemode
            m.setLives(id, 3);
            player.setGameMode(GameType.ADVENTURE);
            player.getInventory().clearContent();
            
            // Reset health and food
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);

            // Give class
            if (role != null) {
                SavedClass sc = m.getSavedClass(team, role.getId());
                if (sc != null) {
                    sc.applyTo(player);
                } else {
                    player.sendSystemMessage(Component.literal("§cClass " + role.getId() + " is not configured for " + team.name() + "!"));
                }
                
                // Give compass to leader (points to intel)
                if (role == Role.LEADER && m.getIntelPos() != null) {
                    ItemStack compass = new ItemStack(Items.COMPASS);
                    CompoundTag tag = compass.getOrCreateTag();
                    tag.put("LodestonePos", NbtUtils.writeBlockPos(m.getIntelPos()));
                    tag.putString("LodestoneDimension", Level.OVERWORLD.location().toString());
                    tag.putBoolean("LodestoneTracked", false); // Do not need actual lodestone
                    player.getInventory().add(compass);
                }
            }

            // Teleport
            BlockPos base = m.getBase(team);
            if (base != null) {
                player.teleportTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            }
        }
    }

    private void checkZoneControl() {
        var m = SuperbScenario.manager;
        BlockPos center = m.getZoneCenter();
        if (center == null) return;
        int radius = m.getZoneRadius();

        int usPlayers = 0;
        int ruPlayers = 0;

        for (ServerPlayer player : m.getServer().getPlayerList().getPlayers()) {
            if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) continue;
            Team t = m.getTeam(player.getUUID());
            if (t == Team.NONE) continue;

            double dist = player.distanceToSqr(center.getX(), center.getY(), center.getZ());
            if (dist <= radius * radius) {
                // If within horizontal/spherical distance. Wait, distanceToSqr uses x,y,z. It's spherical.
                if (t == Team.US) usPlayers++;
                else if (t == Team.RU) ruPlayers++;
            }
        }

        if (usPlayers > 0 && ruPlayers == 0) {
            m.addPoints(Team.US, 1);
        } else if (ruPlayers > 0 && usPlayers == 0) {
            m.addPoints(Team.RU, 1);
        }
    }

    private void endGame() {
        var m = SuperbScenario.manager;
        m.setState(GameState.FINISHED);
        int us = m.getPoints(Team.US);
        int ru = m.getPoints(Team.RU);

        String winner = "DRAW";
        if (us > ru) winner = "US WINS";
        else if (ru > us) winner = "RU WINS";
        
        broadcast("§cROUND OVER! §fScore - US: " + us + " | RU: " + ru + ". §aResult: " + winner);

        // Restore world blocks
        m.restoreWorld();
        
        for (ServerPlayer player : m.getServer().getPlayerList().getPlayers()) {
            player.setGameMode(GameType.ADVENTURE);
            BlockPos lobby = m.getLobbyPos();
            if (lobby != null) {
                player.teleportTo(lobby.getX() + 0.5, lobby.getY(), lobby.getZ() + 0.5);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        var m = SuperbScenario.manager;
        if (m.getState() == GameState.IN_PROGRESS) {
            if (event.getEntity() instanceof ServerPlayer victim) {
                Team victimTeam = m.getTeam(victim.getUUID());
                if (victimTeam != Team.NONE) {
                    m.decrementLives(victim.getUUID());
                    int lives = m.getLives(victim.getUUID());
                    victim.sendSystemMessage(Component.literal("§cYou died! Remaining lives: " + lives));
                }

                if (event.getSource().getEntity() instanceof ServerPlayer killer && killer != victim) {
                    Team killerTeam = m.getTeam(killer.getUUID());
                    if (killerTeam != Team.NONE && killerTeam != victimTeam) {
                        m.addPoints(killerTeam, 5); // 5 points per kill
                        killer.sendSystemMessage(Component.literal("§a+5 Points for enemy kill!"));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var m = SuperbScenario.manager;
            if (m.getState() == GameState.IN_PROGRESS) {
                Team t = m.getTeam(player.getUUID());
                if (t == Team.NONE) return;

                int lives = m.getLives(player.getUUID());
                if (lives <= 0) {
                    player.setGameMode(GameType.SPECTATOR);
                    player.sendSystemMessage(Component.literal("§cYou have no more lives."));
                } else {
                    // Re-give equipment
                    Role role = m.getRole(player.getUUID());
                    if (role != null) {
                        SavedClass sc = m.getSavedClass(t, role.getId());
                        if (sc != null) {
                            sc.applyTo(player);
                        }
                        if (role == Role.LEADER && m.getIntelPos() != null) {
                            ItemStack compass = new ItemStack(Items.COMPASS);
                            CompoundTag tag = compass.getOrCreateTag();
                            tag.put("LodestonePos", NbtUtils.writeBlockPos(m.getIntelPos()));
                            tag.putString("LodestoneDimension", Level.OVERWORLD.location().toString());
                            tag.putBoolean("LodestoneTracked", false);
                            player.getInventory().add(compass);
                        }
                    }

                    // Teleport to leader or base
                    ServerPlayer leader = m.getTeamLeader(t);
                    if (leader != null && leader != player && leader.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                        // Spawn near leader
                        BlockPos lp = leader.blockPosition();
                        player.teleportTo(lp.getX() + (Math.random() * 4 - 2), lp.getY(), lp.getZ() + (Math.random() * 4 - 2));
                    } else {
                        BlockPos base = m.getBase(t);
                        if (base != null) {
                            player.teleportTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
                        }
                    }
                }
            }
        }
    }

    private void broadcast(String message) {
        var m = SuperbScenario.manager;
        if (m.getServer() != null) {
            m.getServer().getPlayerList().broadcastSystemMessage(Component.literal(message), false);
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Send standard disable codes for Minimaps (VoxelMap, Xaero, JourneyMap)
            try {
                // VoxelMap generic radar disable
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeByte(42); 
                buf.writeBoolean(false);
                player.connection.send(new ClientboundCustomPayloadPacket(new ResourceLocation("voxelmap", "settings"), buf));
            } catch (Exception e) {}

            try {
                // JourneyMap generic radar disable (empty standard JSON)
                FriendlyByteBuf bufJm = new FriendlyByteBuf(Unpooled.buffer());
                bufJm.writeByteArray("{\"radar\":false}".getBytes());
                player.connection.send(new ClientboundCustomPayloadPacket(new ResourceLocation("journeymap", "admin"), bufJm));
            } catch (Exception e) {}

            // Send legacy MOTD color codes that older Xaero's / Voxelmap use to disable features
            // §3§0§2§4§2§0§e§0§8 - Disable radar
            // §3§0§2§5§2§0§e§0§8 - Disable cave maps
            player.sendSystemMessage(Component.literal("§3§0§2§4§2§0§e§0§8"));
            player.sendSystemMessage(Component.literal("§3§0§2§5§2§0§e§0§8"));
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        var m = SuperbScenario.manager;
        if (m.getState() == GameState.IN_PROGRESS && event.getEntity() instanceof ServerPlayer player) {
            if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                net.minecraft.world.item.ItemStack item = event.getItemStack();
                if (!item.isEmpty()) {
                    ResourceLocation regName = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item.getItem());
                    // Apply fix to ALL non-vanilla items to ensure mod ID changes (e.g. sbw) don't break it
                    if (regName != null && !regName.getNamespace().equals("minecraft")) {
                        net.minecraft.world.level.block.state.BlockState targetState = event.getLevel().getBlockState(event.getPos());
                        ResourceLocation blockReg = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(targetState.getBlock());
                        if (blockReg != null) {
                            // Dynamically inject the "CanPlaceOn" tag for the exact block the player is looking at!
                            net.minecraft.nbt.CompoundTag tag = item.getOrCreateTag();
                            net.minecraft.nbt.ListTag canPlace = new net.minecraft.nbt.ListTag();
                            canPlace.add(net.minecraft.nbt.StringTag.valueOf(blockReg.toString()));
                            tag.put("CanPlaceOn", canPlace);
                        }
                    }
                }
            }
        }
    }
}
