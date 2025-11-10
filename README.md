# EasyPets 🐾

**Never lose your pets again!**  
EasyPets is a lightweight, server-side Fabric mod that makes pets smarter, faster, and easier to manage.

---

## ✨ Features at a Glance
- 🚀 **Smart Chunk Loading** – Keeps pets’ chunks active so they never get stuck in unloaded areas  
- 🧭 **Pet Tracking & Recovery** – Locate and recover pets with simple commands  
- 🐾 **Dynamic Movement** – Pets run faster to catch up and match your speed naturally  
- ❤️ **Natural Regeneration** – Pets slowly heal over time (like horses)  
- ⚙️ **Powerful Config** – Fully tweakable settings for every feature  
- 🔗 **[IndyPets](https://modrinth.com/mod/indypets) Integration** – Works seamlessly with IndyPets and other pet mods  

---

## 🔍 Details

### 🚀 Smart Chunk Loading
- Loads chunks only for pets that are following you  
- Prevents them from getting stuck in unloaded areas  
- Optimized for minimal server impact (similar to Ender Pearl mechanics)  

### 🧭 Pet Tracking & Recovery
- **`/petlocator`** – Shows coordinates of all your pets across all dimensions  
- **`/petrecovery`** – Scans & reloads pets stuck in unloaded chunks, and automatically runs when you install this mod on an existing world  
- Supports pets in vehicles and integrates with [IndyPets](https://modrinth.com/mod/indypets)  

<details>
<summary>📸 /petlocator Example</summary>

![/petlocator command output showing pets’ coordinates](https://cdn.modrinth.com/data/MDUufqSh/images/35f91041f89a698058d1e755e8f8fba3375eb952.webp)

</details>

---

### 🐾 Dynamic Pet Movement
- Pets automatically adjust speed based on how fast you’re moving  
- Sprinting pets catch up quickly without constant teleporting  
- Fully configurable multipliers & distances  
- You can also adjust the distance before pets teliport back to you

<details>
<summary>📸 Dynamic Speed Comparison</summary>

![Vanilla vs EasyPets following behavior comparison](https://cdn.modrinth.com/data/MDUufqSh/images/2400e5826e962e64d85f47e9a08a49f34d5e1941.webp)

</details>

---

### ❤️ Natural Regeneration
- Pets passively regenerate health over time (like horses)  
- Configurable delay, rate, and maximum percentage  
- Keeps balance while reducing tedious healing  

---

### ⚙️ Powerful Config
Every feature can be toggled or fine-tuned with `/petconfig <setting> <value>`  
<details>
<summary>⚙️ Show Config Options</summary>

#### Core Features
- `enableChunkLoading` - Enable/disable the entire chunk loading system
- `teleportDistance` - Distance in blocks before pets try to teleport to owner (default: 48)
- `maxChunkDistance` - Radius of chunks to keep loaded around each pet (default: 2)
- `navigationScanningRange` - Maximum pathfinding range in blocks before pets teleport (default: 64)
- `autoRecoverOnFirstJoin` - Automatically run pet recovery when joining world for first time

#### Dynamic Pet Running
- `enableDynamicRunning` - Enable/disable dynamic pet speed adjustment system
- `runningTargetDistance` - Distance where pets start running faster to catch up (default: 6.0)
- `maxRunningMultiplier` - Maximum speed boost when pets are far behind (default: 1.6x)
- `playerMovementThreshold` - Minimum player movement to trigger speed changes (default: 0.1)

#### Natural Regeneration
- `enableNaturalRegen` - Enable/disable automatic health regeneration for pets
- `regenDelayTicks` - Delay in ticks before regen starts after taking damage (default: 300)
- `regenAmountPerSecond` - Amount of health regenerated per second (default: 0.05)
- `regenMaxHealthPercent` - Maximum health percentage to regenerate to (default: 1.0 = 100%)

#### Save & Debug Options
- `saveOnLocate` - Trigger world save when `/petlocator` is used for accuracy
- `saveOnRecovery` - Trigger world save before `/petrecovery` runs for better results
- `enableDebugLogging` - Enable detailed console logging for troubleshooting

</details>

---

### 🔗 IndyPets & Mod Support
- Won’t load chunks for independent pets  
- Works seamlessly with [IndyPets](https://modrinth.com/mod/indypets) out of the box  
- Compatible with most other pet-related mods  

---

## 🐛 Support
Found a bug or have a suggestion?  
👉 [Report it on GitHub Issues](https://github.com/TecnaGamer/easypets/issues)  
💬 [Join the Discord](https://discord.gg/TanrrCmRCa) to chat, get support, or share ideas  
🌙 [Test the latest builds](https://github.com/TecnaGamer/EasyPets/actions/workflows/build.yml)

---
