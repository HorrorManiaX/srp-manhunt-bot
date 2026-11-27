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
 'com.x4yi.automata.manhunt.SurvivorService.INSTANCE.clear(net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());\n        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(com.x4yi.automata.manhunt.SurvivorService.INSTANCE);\n        PathfindingExecutor.shutdown();')
]
for before, after in changes:
    if s.count(before)!=1:
        raise SystemExit('Unexpected upstream lifecycle: '+before)
    s=s.replace(before,after,1)
mod.write_text(s,encoding='utf-8')
