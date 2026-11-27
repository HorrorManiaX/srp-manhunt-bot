package com.x4yi.automata.manhunt;

/** Pure decision layer. No world access, allocation-heavy scans or external API calls. */
public final class SurvivorBrain {
    public enum Action { RETREAT, EVADE, RANGED, MELEE, APPROACH, EAT, BUILD, SCAVENGE, PATROL }
    public record Situation(float health, float maxHealth, boolean hungry, boolean food, boolean enemy,
                            boolean visible, boolean gun, boolean melee, double distanceSq,
                            double minRangeSq, boolean explosive, boolean environmentalDanger,
                            boolean build, boolean loot) {}
    private SurvivorBrain() {}
    public static Action choose(Situation s) {
        if (s.environmentalDanger()) return Action.EVADE;
        if (s.enemy()) {
            if (s.health() <= Math.max(4, s.maxHealth() * .35)
                || (s.explosive() && s.distanceSq() < Math.max(36, s.minRangeSq()))
                || (!s.gun() && !s.melee())) return Action.RETREAT;
            if (s.gun() && s.visible() && s.distanceSq() <= 576) return Action.RANGED;
            if (s.melee() && s.visible() && s.distanceSq() <= 8) return Action.MELEE;
            return Action.APPROACH;
        }
        if (s.hungry() && s.food()) return Action.EAT;
        if (s.build()) return Action.BUILD;
        if (s.loot()) return Action.SCAVENGE;
        return Action.PATROL;
    }
    /** Stagger idle perception, accelerate during combat or after a world event. */
    public static int scanInterval(boolean alert, boolean stressed) {
        return stressed ? (alert ? 8 : 24) : (alert ? 4 : 10);
    }
}
