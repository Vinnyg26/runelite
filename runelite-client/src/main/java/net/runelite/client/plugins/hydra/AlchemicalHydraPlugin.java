/*
 * Copyright (c) 2018, Woox <https://github.com/wooxsolo>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.hydra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HeadIcon;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.ProjectileID;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.plugins.demonicgorilla.MemorizedPlayer;

@PluginDescriptor(
        name = "Alchemical Hydra",
        description = "Count boss attacks and display the next attack styles",
        tags = {"combat", "overlay", "pve", "pvm"}
)
public class AlchemicalHydraPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AlchemicalHydraOverlay overlay;

    @Inject
    private ClientThread clientThread;

    @Getter
    private Map<NPC, AlchemicalHydra> hydraMap;

    private List<WorldPoint> recentBoulders;

    private List<PendingHydraAttack> pendingAttacks;

    private Map<Player, MemorizedPlayer> memorizedPlayers;

    public static final int HYDRA_GREEN = 8615;
    public static final int HYDRA_BLUE = 8619;
    public static final int HYDRA_RED = 8620;
    public static final int HYDRA_BLACK = 8621;
    public static final int HYDRA_G2B = 8616;
    public static final int HYDRA_B2R = 8617;
    public static final int HYDRA_R2B = 8618;

    public static final int HYDRA_GREEN_RANGE = 8235;
    public static final int HYDRA_GREEN_MAGE = 8236;
    public static final int HYDRA_BLUE_RANGE = 8242;
    public static final int HYDRA_BLUE_MAGE = 8243;
    public static final int HYDRA_RED_RANGE = 8249;
    public static final int HYDRA_RED_MAGE = 8250;
    public static final int HYDRA_BLACK_RANGE = 8256;
    public static final int HYDRA_BLACK_MAGE = 8255;

    public static final int HYDRA_RANGE_PROJ = 1663;
    public static final int HYDRA_MAGE_PROJ = 1662;

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        hydraMap = new HashMap<>();
        recentBoulders = new ArrayList<>();
        pendingAttacks = new ArrayList<>();
        memorizedPlayers = new HashMap<>();
        clientThread.invoke(this::reset); // Updates the list of hydraMap and players
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        hydraMap = null;
        recentBoulders = null;
        pendingAttacks = null;
        memorizedPlayers = null;
    }

    private void clear()
    {
        recentBoulders.clear();
        pendingAttacks.clear();
        memorizedPlayers.clear();
        hydraMap.clear();
    }

    private void reset()
    {
        recentBoulders.clear();
        pendingAttacks.clear();
        resetHydra();
        resetPlayers();
    }

    private void resetHydra()
    {
        hydraMap.clear();
        for (NPC npc : client.getNpcs())
        {
            if (isNpcHydra(npc.getId()))
            {
                hydraMap.put(npc, new AlchemicalHydra(npc));
            }
        }
    }

    private void resetPlayers()
    {
        memorizedPlayers.clear();
        for (Player player : client.getPlayers())
        {
            memorizedPlayers.put(player, new MemorizedPlayer(player));
        }
    }

    public static boolean isNpcHydra(int npcId)
    {
        return npcId == HYDRA_GREEN ||  //Green
                npcId == HYDRA_G2B || //Anim green to blue
                npcId == HYDRA_BLUE || //Blue
                npcId == HYDRA_B2R || //Anim blue to red
                npcId == HYDRA_RED || //Red
                npcId == HYDRA_R2B || // Anim red to black
                npcId == HYDRA_BLACK;   //Black
    }

    private void checkGorillaAttackStyleSwitch(AlchemicalHydra gorilla,
                                               final AlchemicalHydra.AttackStyle... protectedStyles)
    {
        if (gorilla.getAttacksUntilSwitch() <= 0 ||
                gorilla.getNextPosibleAttackStyles().size() == 0)
        {
            gorilla.setNextPosibleAttackStyles(Arrays
                    .stream(AlchemicalHydra.ALL_REGULAR_ATTACK_STYLES)
                    .filter(x -> Arrays.stream(protectedStyles).noneMatch(y -> x == y))
                    .collect(Collectors.toList()));
            gorilla.setAttacksUntilSwitch(AlchemicalHydra.ATTACKS_PER_SWITCH);
            gorilla.setChangedAttackStyleThisTick(true);
        }
    }

    private AlchemicalHydra.AttackStyle getProtectedStyle(Player player)
    {
        HeadIcon headIcon = player.getOverheadIcon();
        if (headIcon == null)
        {
            return null;
        }
        switch (headIcon)
        {
            case RANGED:
                return AlchemicalHydra.AttackStyle.RANGED;
            case MAGIC:
                return AlchemicalHydra.AttackStyle.MAGIC;
            default:
                return null;
        }
    }

    private void onGorillaAttack(AlchemicalHydra hydra, final AlchemicalHydra.AttackStyle attackStyle)
    {
        hydra.setInitiatedCombat(true);

        Player target = (Player)hydra.getNpc().getInteracting();

        AlchemicalHydra.AttackStyle protectedStyle = null;
        if (target != null)
        {
            protectedStyle = getProtectedStyle(target);
        }
        boolean correctPrayer =
                target == null || // If player is out of memory, assume prayer was correct
                        attackStyle == protectedStyle;

//        if (attackStyle == AlchemicalHydra.AttackStyle.BOULDER)
//        {
//            // The gorilla can't throw boulders when it's meleeing
//            hydra.setNextPosibleAttackStyles(hydra
//                    .getNextPosibleAttackStyles()
//                    .stream()
//                    .filter(x -> x != DemonicGorilla.AttackStyle.MELEE)
//                    .collect(Collectors.toList()));
//        }
//        else
//        {
            if (correctPrayer)
            {
                hydra.setAttacksUntilSwitch(hydra.getAttacksUntilSwitch() - 1);
            }
            else
            {
                // We're not sure if the attack will hit a 0 or not,
                // so we don't know if we should decrease the counter or not,
                // so we keep track of the attack here until the damage splat
                // has appeared on the player.

                int damagesOnTick = client.getTickCount();
                if (attackStyle == AlchemicalHydra.AttackStyle.MAGIC)
                {
                    MemorizedPlayer mp = memorizedPlayers.get(target);
                    WorldArea lastPlayerArea = mp.getLastWorldArea();
                    if (lastPlayerArea != null)
                    {
                        int dist = hydra.getNpc().getWorldArea().distanceTo(lastPlayerArea);
                        damagesOnTick += (dist + AlchemicalHydra.PROJECTILE_MAGIC_DELAY) /
                                AlchemicalHydra.PROJECTILE_MAGIC_SPEED;
                    }
                }
                else if (attackStyle == AlchemicalHydra.AttackStyle.RANGED)
                {
                    MemorizedPlayer mp = memorizedPlayers.get(target);
                    WorldArea lastPlayerArea = mp.getLastWorldArea();
                    if (lastPlayerArea != null)
                    {
                        int dist = hydra.getNpc().getWorldArea().distanceTo(lastPlayerArea);
                        damagesOnTick += (dist + AlchemicalHydra.PROJECTILE_RANGED_DELAY) /
                                AlchemicalHydra.PROJECTILE_RANGED_SPEED;
                    }
                }
                pendingAttacks.add(new PendingHydraAttack(hydra, attackStyle, target, damagesOnTick));
            }

            hydra.setNextPosibleAttackStyles(hydra
                    .getNextPosibleAttackStyles()
                    .stream()
                    .filter(x -> x == attackStyle)
                    .collect(Collectors.toList()));

            if (hydra.getNextPosibleAttackStyles().size() == 0)
            {
                // Sometimes the gorilla can switch attack style before it's supposed to
                // if someone was fighting it earlier and then left, so we just
                // reset the counter in that case.

                hydra.setNextPosibleAttackStyles(Arrays
                        .stream(AlchemicalHydra.ALL_REGULAR_ATTACK_STYLES)
                        .filter(x -> x == attackStyle)
                        .collect(Collectors.toList()));
                hydra.setAttacksUntilSwitch(AlchemicalHydra.ATTACKS_PER_SWITCH -
                        (correctPrayer ? 1 : 0));
            }
//        }

        checkGorillaAttackStyleSwitch(hydra, protectedStyle);

        int tickCounter = client.getTickCount();
        hydra.setNextAttackTick(tickCounter + AlchemicalHydra.ATTACK_RATE);
    }

    private void checkGorillaAttacks()
    {
        int tickCounter = client.getTickCount();
        for (AlchemicalHydra hydra : hydraMap.values())
        {
            Player interacting = (Player)hydra.getNpc().getInteracting();
            MemorizedPlayer mp = memorizedPlayers.get(interacting);

            if (hydra.getLastTickInteracting() != null && interacting == null)
            {
                hydra.setInitiatedCombat(false);
            }
            else if (mp != null && mp.getLastWorldArea() != null &&
                    !hydra.isInitiatedCombat() &&
                    tickCounter < hydra.getNextAttackTick() &&
                    hydra.getNpc().getWorldArea().isInMeleeDistance(mp.getLastWorldArea()))
            {
                hydra.setInitiatedCombat(true);
                hydra.setNextAttackTick(tickCounter + 1);
            }

            int animationId = hydra.getNpc().getAnimation();

//            if (hydra.isTakenDamageRecently() &&
//                    tickCounter >= hydra.getNextAttackTick() + 4)
//            {
//                // The gorilla was flinched, so its next attack gets delayed
//                hydra.setNextAttackTick(tickCounter + AlchemicalHydra.ATTACK_RATE / 2);
//                hydra.setInitiatedCombat(true);
//
//                if (mp != null && mp.getLastWorldArea() != null &&
//                        !hydra.getNpc().getWorldArea().isInMeleeDistance(mp.getLastWorldArea()) &&
//                        !hydra.getNpc().getWorldArea().intersectsWith(mp.getLastWorldArea()))
//                {
//                    // Gorillas stop meleeing when they get flinched
//                    // and the target isn't in melee distance
//                    hydra.setNextPosibleAttackStyles(hydra
//                            .getNextPosibleAttackStyles()
//                            .stream()
//                            .filter(x -> x != AlchemicalHydra.AttackStyle.MELEE)
//                            .collect(Collectors.toList()));
//                    checkGorillaAttackStyleSwitch(hydra, AlchemicalHydra.AttackStyle.MELEE,
//                            getProtectedStyle(interacting));
//                }
//            }
//            else
            if (animationId != hydra.getLastTickAnimation())
            {
                if (animationId == HYDRA_GREEN_MAGE || animationId == HYDRA_BLUE_MAGE || animationId == HYDRA_RED_MAGE || animationId == HYDRA_BLACK_MAGE || hydra.getRecentProjectileId() == HYDRA_MAGE_PROJ)
                {
                    onGorillaAttack(hydra, AlchemicalHydra.AttackStyle.MAGIC);
                }
                else if (animationId == HYDRA_GREEN_RANGE || animationId == HYDRA_BLUE_RANGE || animationId == HYDRA_RED_RANGE || animationId == HYDRA_BLACK_RANGE || hydra.getRecentProjectileId() == HYDRA_RANGE_PROJ)
                {
                    onGorillaAttack(hydra, AlchemicalHydra.AttackStyle.RANGED);
                }
//                else if (animationId == AnimationID.DEMONIC_GORILLA_AOE_ATTACK && interacting != null)
//                {
//                    // Note that AoE animation is the same as prayer switch animation
//                    // so we need to check if the prayer was switched or not.
//                    // It also does this animation when it spawns, so
//                    // we need the interacting != null check.
//
//                    if (gorilla.getOverheadIcon() == gorilla.getLastTickOverheadIcon())
//                    {
//                        // Confirmed, the gorilla used the AoE attack
//                        onGorillaAttack(gorilla, DemonicGorilla.AttackStyle.BOULDER);
//                    }
//                    else
//                    {
//                        if (tickCounter >= gorilla.getNextAttackTick())
//                        {
//                            gorilla.setChangedPrayerThisTick(true);
//
//                            // This part is more complicated because the gorilla may have
//                            // used an attack, but the prayer switch animation takes
//                            // priority over normal attack animations.
//
//                            int projectileId = gorilla.getRecentProjectileId();
//                            if (projectileId == ProjectileID.DEMONIC_GORILLA_MAGIC)
//                            {
//                                onGorillaAttack(gorilla, DemonicGorilla.AttackStyle.MAGIC);
//                            }
//                            else if (projectileId == ProjectileID.DEMONIC_GORILLA_RANGED)
//                            {
//                                onGorillaAttack(gorilla, DemonicGorilla.AttackStyle.RANGED);
//                            }
//                            else if (mp != null)
//                            {
//                                WorldArea lastPlayerArea = mp.getLastWorldArea();
//                                if (lastPlayerArea != null &&
//                                        interacting != null && recentBoulders.stream()
//                                        .anyMatch(x -> x.distanceTo(lastPlayerArea) == 0))
//                                {
//                                    // A boulder started falling on the hydraMap target,
//                                    // so we assume it was the gorilla who shot it
//                                    onGorillaAttack(gorilla, DemonicGorilla.AttackStyle.BOULDER);
//                                }
//                                else if (mp.getRecentHitsplats().size() > 0)
//                                {
//                                    // It wasn't any of the three other attacks,
//                                    // but the player took damage, so we assume
//                                    // it's a melee attack
//                                    onGorillaAttack(gorilla, DemonicGorilla.AttackStyle.MELEE);
//                                }
//                            }
//                        }
//
//                        // The next attack tick is always delayed if the
//                        // gorilla switched prayer
//                        gorilla.setNextAttackTick(tickCounter + DemonicGorilla.ATTACK_RATE);
//                        gorilla.setChangedPrayerThisTick(true);
//                    }
//                }
            }

            if (hydra.getDisabledMeleeMovementForTicks() > 0)
            {
                hydra.setDisabledMeleeMovementForTicks(hydra.getDisabledMeleeMovementForTicks() - 1);
            }
            else if (hydra.isInitiatedCombat() &&
                    hydra.getNpc().getInteracting() != null &&
                    !hydra.isChangedAttackStyleThisTick() &&
                    hydra.getNextPosibleAttackStyles().size() >= 2 &&
                    hydra.getNextPosibleAttackStyles().stream()
                            .anyMatch(x -> x == AlchemicalHydra.AttackStyle.MELEE))
            {
                // If melee is a possibility, we can check if the gorilla
                // is or isn't moving toward the player to determine if
                // it is actually attempting to melee or not.
                // We only run this check if the gorilla is in combat
                // because otherwise it attempts to travel to melee
                // distance before attacking its target.

                if (mp != null && mp.getLastWorldArea() != null && hydra.getLastWorldArea() != null)
                {
                    WorldArea predictedNewArea = hydra.getLastWorldArea().calculateNextTravellingPoint(
                            client, mp.getLastWorldArea(), true, x ->
                            {
                                // Gorillas can't normally walk through other hydraMap
                                // or other players
                                final WorldArea area1 = new WorldArea(x, 1, 1);
                                return area1 != null &&
                                        hydraMap.values().stream().noneMatch(y ->
                                        {
                                            if (y == hydra)
                                            {
                                                return false;
                                            }
                                            final WorldArea area2 =
                                                    y.getNpc().getIndex() < hydra.getNpc().getIndex() ?
                                                            y.getNpc().getWorldArea() : y.getLastWorldArea();
                                            return area2 != null && area1.intersectsWith(area2);
                                        }) &&
                                        memorizedPlayers.values().stream().noneMatch(y ->
                                        {
                                            final WorldArea area2 = y.getLastWorldArea();
                                            return area2 != null && area1.intersectsWith(area2);
                                        });

                                // There is a special case where if a player walked through
                                // a gorilla, or a player walked through another player,
                                // the tiles that were walked through becomes
                                // walkable, but I didn't feel like it's necessary to handle
                                // that special case as it should rarely happen.
                            });
                    if (predictedNewArea != null)
                    {
                        int distance = hydra.getNpc().getWorldArea().distanceTo(mp.getLastWorldArea());
                        WorldPoint predictedMovement = predictedNewArea.toWorldPoint();
                        if (distance <= AlchemicalHydra.MAX_ATTACK_RANGE &&
                                mp != null &&
                                mp.getLastWorldArea().hasLineOfSightTo(client, hydra.getLastWorldArea()))
                        {
                            if (predictedMovement.distanceTo(hydra.getLastWorldArea().toWorldPoint()) != 0)
                            {
                                if (predictedMovement.distanceTo(hydra.getNpc().getWorldLocation()) == 0)
                                {
                                    hydra.setNextPosibleAttackStyles(hydra
                                            .getNextPosibleAttackStyles()
                                            .stream()
                                            .filter(x -> x == AlchemicalHydra.AttackStyle.MELEE)
                                            .collect(Collectors.toList()));
                                }
                                else
                                {
                                    hydra.setNextPosibleAttackStyles(hydra
                                            .getNextPosibleAttackStyles()
                                            .stream()
                                            .filter(x -> x != AlchemicalHydra.AttackStyle.MELEE)
                                            .collect(Collectors.toList()));
                                }
                            }
                            else if (tickCounter >= hydra.getNextAttackTick() &&
                                    hydra.getRecentProjectileId() == -1 &&
                                    recentBoulders.stream().noneMatch(x -> x.distanceTo(mp.getLastWorldArea()) == 0))
                            {
                                hydra.setNextPosibleAttackStyles(hydra
                                        .getNextPosibleAttackStyles()
                                        .stream()
                                        .filter(x -> x == AlchemicalHydra.AttackStyle.MELEE)
                                        .collect(Collectors.toList()));
                            }
                        }
                    }
                }
            }

            if (hydra.isTakenDamageRecently())
            {
                hydra.setInitiatedCombat(true);
            }

            if (hydra.getOverheadIcon() != hydra.getLastTickOverheadIcon())
            {
                if (hydra.isChangedAttackStyleLastTick() ||
                        hydra.isChangedAttackStyleThisTick())
                {
                    // Apparently if it changes attack style and changes
                    // prayer on the same tick or 1 tick apart, it won't
                    // be able to move for the next 2 ticks if it attempts
                    // to melee
                    hydra.setDisabledMeleeMovementForTicks(2);
                }
                else
                {
                    // If it didn't change attack style lately,
                    // it's only for the next 1 tick
                    hydra.setDisabledMeleeMovementForTicks(1);
                }
            }
            hydra.setLastTickAnimation(hydra.getNpc().getAnimation());
            hydra.setLastWorldArea(hydra.getNpc().getWorldArea());
            hydra.setLastTickInteracting(hydra.getNpc().getInteracting());
            hydra.setTakenDamageRecently(false);
            hydra.setChangedPrayerThisTick(false);
            hydra.setChangedAttackStyleLastTick(hydra.isChangedAttackStyleThisTick());
            hydra.setChangedAttackStyleThisTick(false);
            hydra.setLastTickOverheadIcon(hydra.getOverheadIcon());
            hydra.setRecentProjectileId(-1);
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event)
    {
        Projectile projectile = event.getProjectile();
        int projectileId = projectile.getId();
        if (projectileId != ProjectileID.DEMONIC_GORILLA_RANGED &&
                projectileId != ProjectileID.DEMONIC_GORILLA_MAGIC &&
                projectileId != ProjectileID.DEMONIC_GORILLA_BOULDER)
        {
            return;
        }

        // The event fires once before the projectile starts moving,
        // and we only want to check each projectile once
        if (client.getGameCycle() >= projectile.getStartMovementCycle())
        {
            return;
        }

        if (projectileId == ProjectileID.DEMONIC_GORILLA_BOULDER)
        {
            recentBoulders.add(WorldPoint.fromLocal(client, event.getPosition()));
        }
        else if (projectileId == ProjectileID.DEMONIC_GORILLA_MAGIC ||
                projectileId == ProjectileID.DEMONIC_GORILLA_RANGED)
        {
            WorldPoint projectileSourcePosition = WorldPoint.fromLocal(
                    client, projectile.getX1(), projectile.getY1(), client.getPlane());
            for (AlchemicalHydra gorilla : hydraMap.values())
            {
                if (gorilla.getNpc().getWorldLocation().distanceTo(projectileSourcePosition) == 0)
                {
                    gorilla.setRecentProjectileId(projectile.getId());
                }
            }
        }
    }

    private void checkPendingAttacks()
    {
        Iterator<PendingHydraAttack> it = pendingAttacks.iterator();
        int tickCounter = client.getTickCount();
        while (it.hasNext())
        {
            PendingHydraAttack attack = it.next();
            if (tickCounter >= attack.getFinishesOnTick())
            {
                boolean shouldDecreaseCounter = true;
                AlchemicalHydra gorilla = attack.getAttacker();
                MemorizedPlayer target = memorizedPlayers.get(attack.getTarget());
                if (target == null)
                {
                    // Player went out of memory, so assume the hit was a 0
                    shouldDecreaseCounter = true;
                }
                else if (target.getRecentHitsplats().size() == 0)
                {
                    // No hitsplats was applied. This may happen in some cases
                    // where the player was out of memory while the
                    // projectile was travelling. So we assume the hit was a 0.
                    shouldDecreaseCounter = true;
                }
                else if (target.getRecentHitsplats().stream()
                        .anyMatch(x -> x.getHitsplatType() == Hitsplat.HitsplatType.BLOCK))
                {
                    // A blue hitsplat appeared, so we assume the gorilla hit a 0
                    shouldDecreaseCounter = true;
                }

                if (shouldDecreaseCounter)
                {
                    gorilla.setAttacksUntilSwitch(gorilla.getAttacksUntilSwitch() - 1);
                    checkGorillaAttackStyleSwitch(gorilla);
                }

                it.remove();
            }
        }
    }

    private void updatePlayers()
    {
        for (MemorizedPlayer mp : memorizedPlayers.values())
        {
            mp.setLastWorldArea(mp.getPlayer().getWorldArea());
            mp.getRecentHitsplats().clear();
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        if (hydraMap.isEmpty())
        {
            return;
        }

        if (event.getActor() instanceof Player)
        {
            Player player = (Player)event.getActor();
            MemorizedPlayer mp = memorizedPlayers.get(player);
            if (mp != null)
            {
                mp.getRecentHitsplats().add(event.getHitsplat());
            }
        }
        else if (event.getActor() instanceof NPC)
        {
            AlchemicalHydra gorilla = hydraMap.get(event.getActor());
            Hitsplat.HitsplatType hitsplatType = event.getHitsplat().getHitsplatType();
            if (gorilla != null && (hitsplatType == Hitsplat.HitsplatType.BLOCK ||
                    hitsplatType == Hitsplat.HitsplatType.DAMAGE))
            {
                gorilla.setTakenDamageRecently(true);
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        if (gs == GameState.LOGGING_IN ||
                gs == GameState.CONNECTION_LOST ||
                gs == GameState.HOPPING)
        {
            reset();
        }
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        if (hydraMap.isEmpty())
        {
            return;
        }

        Player player = event.getPlayer();
        memorizedPlayers.put(player, new MemorizedPlayer(player));
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned event)
    {
        if (hydraMap.isEmpty())
        {
            return;
        }

        memorizedPlayers.remove(event.getPlayer());
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
        NPC npc = event.getNpc();
        if (isNpcHydra(npc.getId()))
        {
            if (hydraMap.isEmpty())
            {
                // Players are not kept track of when there are no hydraMap in
                // memory, so we need to add the players that were already in memory.
                resetPlayers();
            }

            hydraMap.put(npc, new AlchemicalHydra(npc));
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        if (hydraMap.remove(event.getNpc()) != null && hydraMap.isEmpty())
        {
            clear();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        checkGorillaAttacks();
        checkPendingAttacks();
        updatePlayers();
        recentBoulders.clear();
    }
}