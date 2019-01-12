package net.runelite.client.plugins.hydra;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Actor;
import net.runelite.api.HeadIcon;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldArea;

public class AlchemicalHydra
{
    static final int MAX_ATTACK_RANGE = 10; // Needs <= 10 tiles to reach target
    static final int ATTACK_RATE = 6; // 5 ticks between each attack
    static final int ATTACKS_PER_SWITCH = 3; // 3 unsuccessful attacks per style switch

    static final int PROJECTILE_MAGIC_SPEED = 8; // Travels 8 tiles per tick
    static final int PROJECTILE_RANGED_SPEED = 6; // Travels 6 tiles per tick
    static final int PROJECTILE_MAGIC_DELAY = 12; // Requires an extra 12 tiles
    static final int PROJECTILE_RANGED_DELAY = 9; // Requires an extra 9 tiles

    public static final AttackStyle[] ALL_REGULAR_ATTACK_STYLES =
            {
                    //AttackStyle.MELEE,
                    AttackStyle.RANGED,
                    AttackStyle.MAGIC
            };

    enum AttackStyle
    {
        MELEE,
        MAGIC,
        RANGED
    }

    @Getter
    private NPC npc;

    @Getter
    @Setter
    private List<AttackStyle> nextPosibleAttackStyles;

    @Getter
    @Setter
    private int attacksUntilSwitch;

    @Getter
    @Setter
    private int nextAttackTick;

    @Getter
    @Setter
    private int lastTickAnimation;

    @Getter
    @Setter
    private WorldArea lastWorldArea;

    @Getter
    @Setter
    private boolean initiatedCombat;

    @Getter
    @Setter
    private Actor lastTickInteracting;

    @Getter
    @Setter
    private boolean takenDamageRecently;

    @Getter
    @Setter
    private int recentProjectileId;

    @Getter
    @Setter
    private boolean changedPrayerThisTick;

    @Getter
    @Setter
    private boolean changedAttackStyleThisTick;

    @Getter
    @Setter
    private boolean changedAttackStyleLastTick;

    @Getter
    @Setter
    private HeadIcon lastTickOverheadIcon;

    @Getter
    @Setter
    private int disabledMeleeMovementForTicks;

    public AlchemicalHydra(NPC npc)
    {
        this.npc = npc;
        this.nextPosibleAttackStyles = Arrays.asList(ALL_REGULAR_ATTACK_STYLES);
        this.nextAttackTick = -100;
        this.attacksUntilSwitch = ATTACKS_PER_SWITCH;
        this.recentProjectileId = -1;
    }

    public HeadIcon getOverheadIcon()
    {
        NPCComposition composition = this.npc.getComposition();
        if (composition != null)
        {
            return composition.getOverheadIcon();
        }
        return null;
    }
}