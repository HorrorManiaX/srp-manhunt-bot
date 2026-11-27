#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

overlay=Path(__file__).resolve().parent / 'overlay'
target=Path(sys.argv[1]).resolve()
if not (target / 'build.gradle').is_file():
    raise SystemExit('Expected upstream Automata checkout')
shutil.copytree(overlay, target, dirs_exist_ok=True)
mod=target/'src/main/java/com/x4yi/automata/AutomataMod.java'
s=mod.read_text(encoding='utf-8')
changes=[
('event.registerServerCommand(new CommandAutomata());',
 'event.registerServerCommand(new CommandAutomata());\n        event.registerServerCommand(com.x4yi.automata.manhunt.SurvivorService.INSTANCE);\n        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(com.x4yi.automata.manhunt.SurvivorService.INSTANCE);'),
('PathfindingExecutor.shutdown();',
 'com.x4yi.automata.manhunt.SurvivorService.INSTANCE.shutdown(net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());\n        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(com.x4yi.automata.manhunt.SurvivorService.INSTANCE);\n        PathfindingExecutor.shutdown();')
]
for before, after in changes:
    if s.count(before)!=1:
        raise SystemExit('Unexpected upstream lifecycle: '+before)
    s=s.replace(before,after,1)
mod.write_text(s,encoding='utf-8')

# Keep the upstream default behavior intact; enable ladder edges only for Manhunt's explicit settings.
def patch(relative, changes):
    f=target/'src/main/java/com/x4yi/automata'/relative
    source=f.read_text(encoding='utf-8')
    for before,after in changes:
        if source.count(before)!=1:
            raise SystemExit('Unexpected upstream navigation anchor: '+relative+' '+before[:90])
        source=source.replace(before,after,1)
    f.write_text(source,encoding='utf-8')

patch('navigation/PathfindingSettings.java', [
    ('long maxDurationNanos) {','long maxDurationNanos, boolean allowClimbing) {'),
    ('    public static PathfindingSettings defaults(','''    public PathfindingSettings(boolean allowBreakThrough,boolean allowSprintJump,boolean allowScaffolding,int maxExpandedNodes,int maxFallDistance,int availableScaffoldBlocks,long maxDurationNanos) {
        this(allowBreakThrough,allowSprintJump,allowScaffolding,maxExpandedNodes,maxFallDistance,availableScaffoldBlocks,maxDurationNanos,false);
    }

    public static PathfindingSettings defaults(''')
])
patch('navigation/BlockCellType.java', [('    private BlockCellType()', '    public static final byte LADDER = 8;\n\n    private BlockCellType()')])
patch('navigation/PathNodeAction.java', [('    private PathNodeAction()', '    public static final byte CLIMB = 9;\n\n    private PathNodeAction()')])
patch('movement/CellClassifier.java', [
    ('    public static byte classify(World world, BlockPos pos, IBlockState state) {','    public static byte classify(World world, BlockPos pos, IBlockState state) {\n        if (state.getBlock() instanceof net.minecraft.block.BlockLadder) return BlockCellType.LADDER;'),
    ('return cell == BlockCellType.AIR || cell == BlockCellType.WATER;', 'return cell == BlockCellType.AIR || cell == BlockCellType.WATER || cell == BlockCellType.LADDER;')
])
patch('navigation/INavigationWorldView.java', [
    ('cell == BlockCellType.DOOR_CLOSED;', 'cell == BlockCellType.DOOR_CLOSED || cell == BlockCellType.LADDER;'),
    ('    default boolean isClosedDoor(', '    default boolean isLadder(int x,int y,int z) { return getCellType(x,y,z)==BlockCellType.LADDER; }\n\n    default boolean isClosedDoor(')
])
patch('navigation/AutomatonPathfinder.java',[
    ('        if (world.isWater(cx, cy, cz)) {','''        if(settings.allowClimbing() && world.isLadder(cx,cy,cz)){
            if(world.isLadder(cx,cy+1,cz) && hasBodyClearance(world,cx,cy+1,cz))
                offerNode(world,buf,parentKey,cx,cy+1,cz,currentG+1.6F,PathNodeAction.CLIMB,goalX,goalY,goalZ);
            if(world.isLadder(cx,cy-1,cz) && hasBodyClearance(world,cx,cy-1,cz))
                offerNode(world,buf,parentKey,cx,cy-1,cz,currentG+1.3F,PathNodeAction.CLIMB,goalX,goalY,goalZ);
        }
        if (world.isWater(cx, cy, cz)) {'''),
    ('if (canStandAt(world, nx, cy, nz)) {','if (canStandAt(world, nx, cy, nz) || settings.allowClimbing() && world.isLadder(nx,cy,nz) && hasBodyClearance(world,nx,cy,nz)) {'),
    ('private static final float COST_EDGE_HAZARD_PENALTY = 0.4F;', 'private static final float COST_EDGE_HAZARD_PENALTY = 2.5F;'),
    ('private static final float COST_WATER_PENALTY = 5.5F;', 'private static final float COST_WATER_PENALTY = 8.0F;'),
    ('        if (buf.activePenalties != null && buf.activePenalties.get(PackedPos.pack(x, y, z)) > 0.0F) {','        if (edgeHazardPenalty(world,x,y,z)>0.0F) return false;\n        if (buf.activePenalties != null && buf.activePenalties.get(PackedPos.pack(x, y, z)) > 0.0F) {')
])

# Manhunt agents deliberately use only conservative one-block gaps, not diagonal long parkour.
patch('navigation/AutomatonPathfinder.java', [
    ('int maxGap = hasRunup ? 4 : 2;', 'int maxGap = settings.allowClimbing() ? 1 : hasRunup ? 4 : 2;'),
    ('if (settings.allowSprintJump() && world.isPassable(cx, cy + 2, cz)) {\n            tryDiagonalParkourJumps', 'if (settings.allowSprintJump() && !settings.allowClimbing() && world.isPassable(cx, cy + 2, cz)) {\n            tryDiagonalParkourJumps')
])
