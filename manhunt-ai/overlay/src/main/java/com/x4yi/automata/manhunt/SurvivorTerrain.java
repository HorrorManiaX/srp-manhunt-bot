package com.x4yi.automata.manhunt;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.x4yi.automata.movement.CellClassifier;
/** Live, loaded terrain safety checks. Never touches unloaded chunks. */
final class SurvivorTerrain {
    private SurvivorTerrain(){}
    static boolean hazardous(World w,BlockPos p){
        if(!w.isBlockLoaded(p))return true;
        var s=w.getBlockState(p);var b=s.getBlock();var m=s.getMaterial();
        return m==Material.LAVA||m==Material.FIRE||b==Blocks.CACTUS||b==Blocks.MAGMA||b==Blocks.WEB;
    }
    static boolean stand(World w,BlockPos p){
        return p.getY()>0&&p.getY()<255&&w.getWorldBorder().contains(p)&&!hazardous(w,p)&&!hazardous(w,p.up())&&!hazardous(w,p.down())
            &&w.getBlockState(p).getMaterial()!=Material.WATER&&w.getBlockState(p.down()).getMaterial()!=Material.LEAVES
            &&CellClassifier.isPassable(w,p)&&CellClassifier.isPassable(w,p.up())&&CellClassifier.isSolidSupport(w,p.down());
    }
    static boolean safeStep(World w,double x,double y,double z,double dx,double dz){
        double length=Math.sqrt(dx*dx+dz*dz);
        if(length<.01)return true;
        double nx=-dz/length*.29,nz=dx/length*.29;
        for(double side:new double[]{0,1,-1})for(double t:new double[]{.5,1}){
            BlockPos p=new BlockPos(x+dx*t+nx*side,y,z+dz*t+nz*side);
            if(!stand(w,p))return false;
        }
        return true;
    }
    static int elevation(World w,BlockPos p){
        int total=0,valid=0;
        for(int[] offset:new int[][]{{8,0},{-8,0},{0,8},{0,-8},{8,8},{-8,8},{8,-8},{-8,-8}}){
            BlockPos q=p.add(offset[0],0,offset[1]);
            if(!w.isBlockLoaded(q))continue;
            // Count nearby drops without assuming all tall columns are accessible ground.
            int drop=0;for(int d=1;d<=12;d++){if(hazardous(w,q.down(d))||CellClassifier.isSolidSupport(w,q.down(d)))break;drop++;}
            total+=drop;valid++;
        }
        return valid>=4?total/valid:0;
    }
    static int safeExits(World w,BlockPos p){
        int n=0;
        for(var f:net.minecraft.util.EnumFacing.HORIZONTALS){
            boolean ok=true;for(int k=1;k<=3;k++)if(!stand(w,p.offset(f,k))){ok=false;break;}
            if(ok)n++;
        }
        return n;
    }
}
