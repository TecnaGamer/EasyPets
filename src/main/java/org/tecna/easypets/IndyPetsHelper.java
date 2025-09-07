package org.tecna.easypets;

import net.minecraft.entity.passive.TameableEntity;
import net.fabricmc.loader.api.FabricLoader;

public class IndyPetsHelper {

    private static Boolean indyPetsLoaded = null;

    /**
     * Check if IndyPets mod is loaded
     */
    public static boolean isIndyPetsLoaded() {
        if (indyPetsLoaded == null) {
            indyPetsLoaded = FabricLoader.getInstance().isModLoaded("indypets");
        }
        return indyPetsLoaded;
    }

    /**
     * Check if a pet is independent (IndyPets integration)
     * This method uses reflection to safely check for IndyPets functionality
     */
    public static boolean isPetIndependent(TameableEntity pet) {
        // If IndyPets isn't loaded, no pets can be independent
        if (!isIndyPetsLoaded()) {
            return false;
        }

        try {
            Class<?> petClass = pet.getClass();

            // Method 1: Try to find a public isAllowedToFollow method
            try {
                java.lang.reflect.Method method = petClass.getMethod("isAllowedToFollow");
                Object result = method.invoke(pet);
                if (result instanceof Boolean) {
                    return !(Boolean) result; // Independent = not allowed to follow
                }
            } catch (Exception e) {
                // Method doesn't exist, try next approach
            }

            // Method 2: Try to find allowedToFollow field
            try {
                java.lang.reflect.Field field = petClass.getDeclaredField("allowedToFollow");
                field.setAccessible(true);
                Object value = field.get(pet);
                if (value instanceof Boolean) {
                    return !(Boolean) value; // Independent = not allowed to follow
                }
            } catch (Exception e) {
                // Field doesn't exist, try next approach
            }

            // Method 3: Try to find IndyPets-specific methods through superclasses
            Class<?> currentClass = petClass;
            while (currentClass != null && !currentClass.equals(Object.class)) {
                try {
                    java.lang.reflect.Method[] methods = currentClass.getDeclaredMethods();
                    for (java.lang.reflect.Method method : methods) {
                        String methodName = method.getName().toLowerCase();
                        if (methodName.contains("allowedtofollow") || methodName.contains("independent")) {
                            if (method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                                method.setAccessible(true);
                                Object result = method.invoke(pet);
                                if (result instanceof Boolean) {
                                    if (methodName.contains("independent")) {
                                        return (Boolean) result;
                                    } else if (methodName.contains("allowedtofollow")) {
                                        return !(Boolean) result;  // not allowed to follow = independent
                                    }
                                    return false;                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Continue searching
                }
                currentClass = currentClass.getSuperclass();
            }

            // If all methods fail, assume not independent
            return false;

        } catch (Exception e) {
            // If any error occurs, assume not independent to be safe
            return false;
        }
    }

    /**
     * Extract IndyPets home position from NBT
     */
    public static int[] getIndyPetsHomePos(net.minecraft.nbt.NbtCompound entity) {
        // Only read IndyPets NBT data if the mod is actually loaded
        if (!isIndyPetsLoaded()) {
            return null;
        }

        if (entity.contains("IndyPets$HomePos")) {
            java.util.Optional<int[]> homePosOpt = entity.getIntArray("IndyPets$HomePos");
            if (homePosOpt.isPresent() && homePosOpt.get().length >= 3) {
                return homePosOpt.get();
            }
        }
        return null;
    }

    /**
     * Check if NBT indicates pet is independent
     */
    public static boolean isIndependentFromNBT(net.minecraft.nbt.NbtCompound entity) {
        // Only check IndyPets NBT data if the mod is actually loaded
        if (!isIndyPetsLoaded()) {
            return false;
        }

        if (entity.contains("AllowedToFollow")) {
            byte allowedToFollowByte = entity.getByte("AllowedToFollow", (byte)1); // Default to 1 (true)
            return allowedToFollowByte == 0; // 0b means independent
        }
        return false;
    }
}