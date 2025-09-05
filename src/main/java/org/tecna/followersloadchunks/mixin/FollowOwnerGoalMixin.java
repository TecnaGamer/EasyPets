package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.config.Config;

@Mixin(FollowOwnerGoal.class)
public class FollowOwnerGoalMixin {


    @Shadow
    @Final
    private EntityNavigation navigation;

    @Inject(method = "start", at = @At("HEAD"))
    public void start(CallbackInfo ci) {

        navigation.setMaxFollowRange(Config.getInstance().getMaxNavigationRange());
    }


}
