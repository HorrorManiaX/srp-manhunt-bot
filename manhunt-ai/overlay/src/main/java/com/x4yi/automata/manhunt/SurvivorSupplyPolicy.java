package com.x4yi.automata.manhunt;

/** Transfers only true surplus, up to one small delivery per coordination pass. */
public final class SurvivorSupplyPolicy {
    public enum Resource { FOOD, PLANKS, COBBLESTONE, COAL, IRON }
    private SurvivorSupplyPolicy(){}
    public static int reserve(Resource r, SurvivorProgression.Role role){
        return switch(r){
            case FOOD -> role==SurvivorProgression.Role.MEDIC?8:6;
            case PLANKS -> role==SurvivorProgression.Role.BUILDER?16:8;
            case COBBLESTONE -> role==SurvivorProgression.Role.BUILDER?32:12;
            case COAL -> role==SurvivorProgression.Role.ENGINEER?8:4;
            case IRON -> role==SurvivorProgression.Role.ENGINEER?6:3;
        };
    }
    public static int desired(Resource r,SurvivorProgression.Role role){
        return switch(r){
            case FOOD -> 4;
            case PLANKS -> role==SurvivorProgression.Role.BUILDER?12:4;
            case COBBLESTONE -> role==SurvivorProgression.Role.BUILDER?24:8;
            case COAL -> role==SurvivorProgression.Role.ENGINEER?4:1;
            case IRON -> role==SurvivorProgression.Role.ENGINEER?3:0;
        };
    }
    public static int transfer(Resource resource, SurvivorProgression.Role from, SurvivorProgression.Role to,int stock,int recipient){
        int surplus=Math.max(0,stock-reserve(resource,from));
        int deficit=Math.max(0,desired(resource,to)-recipient);
        return Math.min(Math.min(surplus,deficit),8);
    }
}
