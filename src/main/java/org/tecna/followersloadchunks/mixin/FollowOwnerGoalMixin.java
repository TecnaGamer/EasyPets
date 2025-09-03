package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.config.FollowersLoadChunksConfig;
import org.tecna.followersloadchunks.pathfinding.PathfindingContext;
import org.tecna.followersloadchunks.pathfinding.TerrainAnalysis;

@Mixin(FollowOwnerGoal.class)
public abstract class FollowOwnerGoalMixin {
    @Shadow @Final private TameableEntity tameable;
    @Shadow @Final private EntityNavigation navigation;

    // Store original pathfinding penalties to restore them later
    private float originalLavaPenalty = -1.0F;
    private float originalFirePenalty = 8.0F;
    private float originalDamageFirePenalty = 16.0F;
    private float originalWaterPenalty = 8.0F;
    private float originalWaterBorderPenalty = 8.0F;
    private boolean penaltiesStored = false;

    // Pathfinding intelligence tracking
    private long pathfindingStartTime = 0;
    private BlockPos lastOwnerPosition = BlockPos.ORIGIN;
    private int ownerStationaryTicks = 0;
    private static final int OWNER_STATIONARY_THRESHOLD = 100; // 5 seconds
    private static final int MAX_STATIONARY_TICKS = 1200; // 60 seconds

    @Inject(method = "start", at = @At("HEAD"))
    private void followersloadchunks$onStart(CallbackInfo ci) {
        FollowersLoadChunksConfig config = FollowersLoadChunksConfig.getInstance();

        if (config.isAdvancedPathfindingEnabled()) {
            setDynamicNavigationRange(config);
            storeOriginalPenalties();
            applyIntelligentPathfinding(config);

            pathfindingStartTime = this.tameable.getWorld().getTime();
            if (this.tameable.getOwner() != null) {
                lastOwnerPosition = this.tameable.getOwner().getBlockPos();
            }
            ownerStationaryTicks = 0;

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Advanced pet pathfinding enabled for " +
                        this.tameable.getType().getTranslationKey());
            }
        } else {
            setDynamicNavigationRange(config);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void followersloadchunks$onTick(CallbackInfo ci) {
        FollowersLoadChunksConfig config = FollowersLoadChunksConfig.getInstance();

        if (!config.isAdvancedPathfindingEnabled()) {
            return;
        }

        if (config.isOwnerActivityDetectionEnabled()) {
            updateOwnerActivityTracking();
        }

        checkPathfindingTimeout(config);
        adjustPathfindingDynamically(config);
    }

    @Inject(method = "stop", at = @At("TAIL"))
    private void followersloadchunks$onStop(CallbackInfo ci) {
        FollowersLoadChunksConfig config = FollowersLoadChunksConfig.getInstance();

        if (config.isAdvancedPathfindingEnabled()) {
            restoreOriginalPenalties();
            pathfindingStartTime = 0;
            ownerStationaryTicks = 0;
        }
    }

    private void setDynamicNavigationRange(FollowersLoadChunksConfig config) {
        double teleportDistanceBlocks = config.getPetTeleportDistance() * 16.0;
        float navigationRange = (float) (teleportDistanceBlocks * config.getNavigationRangeMultiplier());

        navigationRange = Math.max(config.getMinNavigationRange(),
                Math.min(config.getMaxNavigationRange(), navigationRange));

        this.navigation.setMaxFollowRange(navigationRange);

        if (config.isDebugLoggingEnabled()) {
            System.out.println("[FollowersLoadChunks] Set navigation range to " + navigationRange + " blocks");
        }
    }

    private void storeOriginalPenalties() {
        if (!penaltiesStored) {
            originalLavaPenalty = this.tameable.getPathfindingPenalty(PathNodeType.LAVA);
            originalFirePenalty = this.tameable.getPathfindingPenalty(PathNodeType.DANGER_FIRE);
            originalDamageFirePenalty = this.tameable.getPathfindingPenalty(PathNodeType.DAMAGE_FIRE);
            originalWaterPenalty = this.tameable.getPathfindingPenalty(PathNodeType.WATER);
            originalWaterBorderPenalty = this.tameable.getPathfindingPenalty(PathNodeType.WATER_BORDER);
            penaltiesStored = true;
        }
    }

    private void restoreOriginalPenalties() {
        if (penaltiesStored) {
            this.tameable.setPathfindingPenalty(PathNodeType.LAVA, originalLavaPenalty);
            this.tameable.setPathfindingPenalty(PathNodeType.DANGER_FIRE, originalFirePenalty);
            this.tameable.setPathfindingPenalty(PathNodeType.DAMAGE_FIRE, originalDamageFirePenalty);
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, originalWaterPenalty);
            this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, originalWaterBorderPenalty);
        }
    }

    private void applyIntelligentPathfinding(FollowersLoadChunksConfig config) {
        if (this.tameable.getOwner() == null) return;

        // FIRST: Apply safety settings (make lava/fire impassable)
        applySafetySettings(config);

        // SECOND: Check for emergency situations
        if (isPetInDanger()) {
            applyEmergencyEscapeLogic(config);
            return;
        }

        // THIRD: Check if direct path is dangerous
        if (wouldDirectPathGoThroughDanger()) {
            this.navigation.stop();

            if (!tryFindSafePositionNearOwner(config)) {
                pathfindingStartTime = this.tameable.getWorld().getTime() - config.getPathfindingTimeoutTicks();
            }

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Path would go through danger, stopping pathfinding");
            }
            return;
        }

        // FOURTH: Normal pathfinding (only if safe)
        PathfindingContext context = analyzePathfindingContext(config);

        if (config.isBiomeAwarePathfindingEnabled()) {
            applyBiomeAwarePathfinding(context);
        }

        applyDistanceBasedPathfinding(context, config);
    }

    private void applySafetySettings(FollowersLoadChunksConfig config) {
        if (!config.isPathfindingSafetyEnabled()) return;

        // Make lava and fire completely impassable (-1.0F = impassable)
        if (config.shouldAlwaysAvoidLava()) {
            this.tameable.setPathfindingPenalty(PathNodeType.LAVA, -1.0F);
            this.tameable.setPathfindingPenalty(PathNodeType.DAMAGE_FIRE, -1.0F);
            this.tameable.setPathfindingPenalty(PathNodeType.DANGER_FIRE, -1.0F);
        }

        if (config.shouldAlwaysAvoidFire()) {
            this.tameable.setPathfindingPenalty(PathNodeType.DANGER_FIRE, -1.0F);
            this.tameable.setPathfindingPenalty(PathNodeType.DAMAGE_FIRE, -1.0F);
        }

        this.tameable.setPathfindingPenalty(PathNodeType.DANGER_OTHER, 500.0F);
    }

    private PathfindingContext analyzePathfindingContext(FollowersLoadChunksConfig config) {
        BlockPos petPos = this.tameable.getBlockPos();
        BlockPos ownerPos = this.tameable.getOwner().getBlockPos();
        double distanceToOwner = this.tameable.squaredDistanceTo(this.tameable.getOwner());

        double teleportDistanceSquared = config.getPetTeleportDistanceSquared();
        boolean isNearOwner = distanceToOwner < teleportDistanceSquared * 0.25;
        boolean isFarFromOwner = distanceToOwner > teleportDistanceSquared * 4;

        int heightDifference = Math.abs(ownerPos.getY() - petPos.getY());
        boolean significantHeightDifference = heightDifference > 5;
        boolean extremeHeightDifference = heightDifference > 15;

        Biome petBiome = this.tameable.getWorld().getBiome(petPos).value();
        Biome ownerBiome = this.tameable.getWorld().getBiome(ownerPos).value();

        TerrainAnalysis terrainAnalysis = TerrainAnalysis.UNKNOWN;
        if (config.isTerrainAnalysisEnabled()) {
            terrainAnalysis = analyzeTerrainBetweenPetAndOwner(petPos, ownerPos);
        }

        return new PathfindingContext(petPos, ownerPos, distanceToOwner, isNearOwner, isFarFromOwner,
                significantHeightDifference, extremeHeightDifference,
                petBiome, ownerBiome, terrainAnalysis);
    }

    private TerrainAnalysis analyzeTerrainBetweenPetAndOwner(BlockPos petPos, BlockPos ownerPos) {
        int dx = ownerPos.getX() - petPos.getX();
        int dy = ownerPos.getY() - petPos.getY();
        int dz = ownerPos.getZ() - petPos.getZ();

        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps == 0) return TerrainAnalysis.CLEAR;

        int lavaBlocks = 0;
        int waterBlocks = 0;
        int solidBlocks = 0;
        int airBlocks = 0;

        for (int i = 1; i < steps; i += Math.max(1, steps / 10)) {
            double progress = (double) i / steps;
            BlockPos samplePos = new BlockPos(
                    (int) (petPos.getX() + dx * progress),
                    (int) (petPos.getY() + dy * progress),
                    (int) (petPos.getZ() + dz * progress)
            );

            if (this.tameable.getWorld().getBlockState(samplePos).isLiquid()) {
                if (this.tameable.getWorld().getBlockState(samplePos).getBlock().toString().contains("lava")) {
                    lavaBlocks++;
                } else {
                    waterBlocks++;
                }
            } else if (this.tameable.getWorld().getBlockState(samplePos).isAir()) {
                airBlocks++;
            } else {
                solidBlocks++;
            }
        }

        if (lavaBlocks > 0) return TerrainAnalysis.DANGEROUS_LIQUIDS;
        if (waterBlocks > solidBlocks && waterBlocks > airBlocks) return TerrainAnalysis.MOSTLY_WATER;
        if (solidBlocks > airBlocks * 2) return TerrainAnalysis.DENSE_TERRAIN;
        if (airBlocks > solidBlocks * 2) return TerrainAnalysis.OPEN_AIR;

        return TerrainAnalysis.MIXED;
    }

    private void applyBiomeAwarePathfinding(PathfindingContext context) {
        if (isOceanBiome(context.petBiome) || isOceanBiome(context.ownerBiome)) {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 2.0F);
            this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, 1.0F);
        }
        else if (isColdBiome(context.petBiome)) {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 12.0F);
        }
        else if (isSwampBiome(context.petBiome)) {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 4.0F);
        }
    }

    private void applyDistanceBasedPathfinding(PathfindingContext context, FollowersLoadChunksConfig config) {
        if (context.isNearOwner) {
            makePathfindingMoreAggressive(config);
        } else if (context.isFarFromOwner || context.extremeHeightDifference) {
            makePathfindingMoreConservative(config);
        } else {
            makePathfindingBalanced(config);
        }

        if (config.isTerrainAnalysisEnabled()) {
            applyTerrainSpecificAdjustments(context.terrainAnalysis, config);
        }
    }

    private void applyTerrainSpecificAdjustments(TerrainAnalysis terrain, FollowersLoadChunksConfig config) {
        switch (terrain) {
            case MOSTLY_WATER:
                if (!isPetDrowning()) {
                    this.tameable.setPathfindingPenalty(PathNodeType.WATER, 3.0F);
                }
                break;
            case DANGEROUS_LIQUIDS:
            case DENSE_TERRAIN:
            case OPEN_AIR:
            case MIXED:
            case CLEAR:
            case UNKNOWN:
            default:
                break;
        }
    }

    private void makePathfindingMoreAggressive(FollowersLoadChunksConfig config) {
        if (!isPetDrowning()) {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 0.0F);
        }
    }

    private void makePathfindingMoreConservative(FollowersLoadChunksConfig config) {
        this.tameable.setPathfindingPenalty(PathNodeType.WATER, 16.0F);
        this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, 12.0F);
    }

    private void makePathfindingBalanced(FollowersLoadChunksConfig config) {
        if (!isPetDrowning()) {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 6.0F);
        } else {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 20.0F);
        }
        this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, 4.0F);
    }

    private void applyEmergencyEscapeLogic(FollowersLoadChunksConfig config) {
        if (this.tameable.isInLava()) {
            this.navigation.stop();
            pathfindingStartTime = this.tameable.getWorld().getTime() - (config.getPathfindingTimeoutTicks() * 2);

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] EMERGENCY: Pet in lava, stopping pathfinding and encouraging teleport");
            }
            return;
        }

        if (this.tameable.isOnFire()) {
            if (isNearSafeWater()) {
                this.tameable.setPathfindingPenalty(PathNodeType.WATER, -2.0F);
                this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, 0.0F);
            } else {
                this.navigation.stop();
                pathfindingStartTime = this.tameable.getWorld().getTime() - config.getPathfindingTimeoutTicks();
            }
        }

        if (config.shouldPrioritizeAirBreathing() && isPetDrowning()) {
            this.tameable.setPathfindingPenalty(PathNodeType.WATER, 200.0F);
            this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, 50.0F);

            if (this.tameable.getAir() < this.tameable.getMaxAir() * 0.1) {
                this.navigation.stop();
            }
        }

        if (isPetSurroundedByDanger()) {
            this.navigation.stop();

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Pet surrounded by danger, stopping pathfinding for safety");
            }
        }
    }

    private void updateOwnerActivityTracking() {
        if (this.tameable.getOwner() == null) return;

        BlockPos currentOwnerPos = this.tameable.getOwner().getBlockPos();

        if (currentOwnerPos.equals(lastOwnerPosition)) {
            ownerStationaryTicks++;
        } else {
            ownerStationaryTicks = 0;
            lastOwnerPosition = currentOwnerPos;
        }

        if (ownerStationaryTicks > MAX_STATIONARY_TICKS) {
            ownerStationaryTicks = MAX_STATIONARY_TICKS;
        }
    }

    private void checkPathfindingTimeout(FollowersLoadChunksConfig config) {
        long currentTime = this.tameable.getWorld().getTime();
        long pathfindingDuration = currentTime - pathfindingStartTime;

        if (pathfindingDuration > config.getPathfindingTimeoutTicks()) {
            encourageTeleportation(config);

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Pathfinding timeout reached for pet, encouraging teleportation");
            }
        }
    }

    private void adjustPathfindingDynamically(FollowersLoadChunksConfig config) {
        if (!config.isOwnerActivityDetectionEnabled()) return;

        if (ownerStationaryTicks > OWNER_STATIONARY_THRESHOLD) {
            makePathfindingMoreConservative(config);
        }

        if (isOwnerMovingQuickly()) {
            makePathfindingMoreAggressive(config);
        }
    }

    private void encourageTeleportation(FollowersLoadChunksConfig config) {
        this.tameable.setPathfindingPenalty(PathNodeType.WATER, 32.0F);
        this.tameable.setPathfindingPenalty(PathNodeType.WATER_BORDER, 24.0F);
    }

    private boolean isPetInDanger() {
        return this.tameable.isInLava() ||
                this.tameable.isOnFire() ||
                isPetDrowning() ||
                this.tameable.getHealth() < this.tameable.getMaxHealth() * 0.3f;
    }

    private boolean isPetDrowning() {
        return this.tameable.isSubmergedInWater() &&
                this.tameable.getAir() < this.tameable.getMaxAir() * 0.3;
    }

    private boolean isOwnerMovingQuickly() {
        if (!(this.tameable.getOwner() instanceof ServerPlayerEntity owner)) {
            return false;
        }

        return owner.getAbilities().flying ||
                owner.hasVehicle() ||
                owner.getVelocity().lengthSquared() > 0.5;
    }

    // === NEW METHODS FOR DANGER DETECTION ===

    private boolean isBlockDangerous(BlockPos pos) {
        try {
            String blockName = this.tameable.getWorld().getBlockState(pos).getBlock().toString().toLowerCase();
            return blockName.contains("lava") ||
                    blockName.contains("fire") ||
                    blockName.contains("magma") ||
                    blockName.contains("campfire");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean wouldDirectPathGoThroughDanger() {
        if (this.tameable.getOwner() == null) return false;

        BlockPos petPos = this.tameable.getBlockPos();
        BlockPos ownerPos = this.tameable.getOwner().getBlockPos();

        int steps = (int) Math.ceil(petPos.getSquaredDistance(ownerPos));
        if (steps > 50) return false;

        for (int i = 1; i < steps; i++) {
            double progress = (double) i / steps;
            BlockPos checkPos = new BlockPos(
                    (int) (petPos.getX() + (ownerPos.getX() - petPos.getX()) * progress),
                    (int) (petPos.getY() + (ownerPos.getY() - petPos.getY()) * progress),
                    (int) (petPos.getZ() + (ownerPos.getZ() - petPos.getZ()) * progress)
            );

            if (isBlockDangerous(checkPos) || isBlockDangerous(checkPos.up())) {
                return true;
            }
        }

        return false;
    }

    private boolean isPetSurroundedByDanger() {
        BlockPos petPos = this.tameable.getBlockPos();
        int dangerousBlocks = 0;
        int totalChecked = 0;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 1; y++) {
                    if (x == 0 && z == 0 && y == 0) continue;

                    BlockPos checkPos = petPos.add(x, y, z);
                    if (isBlockDangerous(checkPos)) {
                        dangerousBlocks++;
                    }
                    totalChecked++;
                }
            }
        }

        return dangerousBlocks > (totalChecked * 0.7);
    }

    private boolean isNearSafeWater() {
        BlockPos petPos = this.tameable.getBlockPos();

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos checkPos = petPos.add(x, y, z);

                    if (this.tameable.getWorld().getBlockState(checkPos).isLiquid() &&
                            !this.tameable.getWorld().getBlockState(checkPos).getBlock().toString().contains("lava")) {

                        boolean waterIsSafe = true;
                        for (int wx = -1; wx <= 1; wx++) {
                            for (int wz = -1; wz <= 1; wz++) {
                                BlockPos waterCheckPos = checkPos.add(wx, 0, wz);
                                if (isBlockDangerous(waterCheckPos)) {
                                    waterIsSafe = false;
                                    break;
                                }
                            }
                            if (!waterIsSafe) break;
                        }

                        if (waterIsSafe) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean tryFindSafePositionNearOwner(FollowersLoadChunksConfig config) {
        if (this.tameable.getOwner() == null) return false;

        BlockPos ownerPos = this.tameable.getOwner().getBlockPos();
        BlockPos petPos = this.tameable.getBlockPos();

        for (int radius = 3; radius <= 8; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue;

                    BlockPos targetPos = ownerPos.add(x, 0, z);

                    if (isPositionSafe(targetPos) && !wouldPathToPositionGoThroughDanger(petPos, targetPos)) {
                        this.navigation.startMovingTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);

                        if (config.isDebugLoggingEnabled()) {
                            System.out.println("[FollowersLoadChunks] Found safe position near owner at " + targetPos);
                        }
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isPositionSafe(BlockPos pos) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 2; y++) {
                    BlockPos checkPos = pos.add(x, y, z);
                    if (isBlockDangerous(checkPos)) {
                        return false;
                    }
                }
            }
        }

        BlockPos groundPos = pos.down();
        if (this.tameable.getWorld().getBlockState(groundPos).isAir()) {
            return false;
        }

        if (!this.tameable.getWorld().getBlockState(pos).isAir() &&
                !this.tameable.getWorld().getBlockState(pos).isLiquid()) {
            return false;
        }

        return true;
    }

    private boolean wouldPathToPositionGoThroughDanger(BlockPos start, BlockPos end) {
        int steps = (int) Math.ceil(start.getSquaredDistance(end));
        if (steps > 30) return true;

        int consecutiveDangerousBlocks = 0;
        int totalDangerousBlocks = 0;
        int maxConsecutiveDangerous = 0;

        for (int i = 1; i < steps; i++) {
            double progress = (double) i / steps;
            BlockPos checkPos = new BlockPos(
                    (int) (start.getX() + (end.getX() - start.getX()) * progress),
                    (int) (start.getY() + (end.getY() - start.getY()) * progress),
                    (int) (start.getZ() + (end.getZ() - start.getZ()) * progress)
            );

            // Check current position and surrounding area for comprehensive danger assessment
            boolean isDangerous = isBlockDangerous(checkPos) ||
                    isBlockDangerous(checkPos.up()) ||
                    isBlockDangerous(checkPos.north()) ||
                    isBlockDangerous(checkPos.south()) ||
                    isBlockDangerous(checkPos.east()) ||
                    isBlockDangerous(checkPos.west());

            if (isDangerous) {
                consecutiveDangerousBlocks++;
                totalDangerousBlocks++;
                maxConsecutiveDangerous = Math.max(maxConsecutiveDangerous, consecutiveDangerousBlocks);
            } else {
                consecutiveDangerousBlocks = 0;
            }
        }

        // Allow small gaps (1-2 consecutive dangerous blocks) and limited total danger
        if (maxConsecutiveDangerous <= 2 && totalDangerousBlocks <= 3) {
            return false; // Allow traversal of small dangerous areas
        }

        // Block paths with large dangerous areas
        return maxConsecutiveDangerous > 3 || totalDangerousBlocks > 5;
    }

    // Biome classification helper methods
    private boolean isOceanBiome(Biome biome) {
        try {
            net.minecraft.registry.Registry<Biome> biomeRegistry = this.tameable.getWorld().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            return biome.equals(biomeRegistry.get(BiomeKeys.OCEAN)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.DEEP_OCEAN)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.WARM_OCEAN)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.COLD_OCEAN));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDesertBiome(Biome biome) {
        try {
            net.minecraft.registry.Registry<Biome> biomeRegistry = this.tameable.getWorld().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            return biome.equals(biomeRegistry.get(BiomeKeys.DESERT)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.BADLANDS));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isColdBiome(Biome biome) {
        try {
            net.minecraft.registry.Registry<Biome> biomeRegistry = this.tameable.getWorld().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            return biome.equals(biomeRegistry.get(BiomeKeys.SNOWY_PLAINS)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.ICE_SPIKES)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.FROZEN_OCEAN));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSwampBiome(Biome biome) {
        try {
            net.minecraft.registry.Registry<Biome> biomeRegistry = this.tameable.getWorld().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            return biome.equals(biomeRegistry.get(BiomeKeys.SWAMP)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.MANGROVE_SWAMP));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMountainBiome(Biome biome) {
        try {
            net.minecraft.registry.Registry<Biome> biomeRegistry = this.tameable.getWorld().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            return biome.equals(biomeRegistry.get(BiomeKeys.STONY_PEAKS)) ||
                    biome.equals(biomeRegistry.get(BiomeKeys.JAGGED_PEAKS));
        } catch (Exception e) {
            return false;
        }
    }
}