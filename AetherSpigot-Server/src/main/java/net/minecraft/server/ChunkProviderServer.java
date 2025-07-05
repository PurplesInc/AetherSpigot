package net.minecraft.server;

import it.unimi.dsi.fastutil.longs.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Server;
import org.bukkit.craftbukkit.chunkio.ChunkIOExecutor;
import org.bukkit.craftbukkit.util.LongHash;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.io.IOException;
import java.util.List;
import java.util.Random;
// CraftBukkit end

public class ChunkProviderServer implements IChunkProvider {

    private static final Logger b = LogManager.getLogger();
    public LongSet unloadQueue = new LongOpenHashSet(); // CraftBukkit - LongHashSet // TacoSpigot - LongHashSet -> HashArraySet // IonSpigot - LongOpenHashSet
    public Chunk emptyChunk;
    public IChunkProvider chunkProvider;
    public IChunkLoader chunkLoader; // KigPaper - private -> public
    public boolean forceChunkLoad = false; // CraftBukkit - true -> false
    // Paper start
    protected Chunk lastChunkByPos = null;
    public Long2ObjectMap<Chunk> chunks = new Long2ObjectOpenHashMap<Chunk>(8192, 0.5f) {
        @Override
        public Chunk get(long key) {
            if (lastChunkByPos != null && key == lastChunkByPos.chunkKey) {
                return lastChunkByPos;
            }
            return lastChunkByPos = super.get(key);
        }

        @Override
        public Chunk remove(long key) {
            if (lastChunkByPos != null && key == lastChunkByPos.chunkKey) {
                lastChunkByPos = null;
            }
            return super.remove(key);
        }
    }; // CraftBukkit
    // Paper end
    public WorldServer world;

    public ChunkProviderServer(WorldServer worldserver, IChunkLoader ichunkloader, IChunkProvider ichunkprovider) {
        this.emptyChunk = new EmptyChunk(worldserver, Integer.MIN_VALUE, Integer.MIN_VALUE); // Migot
        this.world = worldserver;
        this.chunkLoader = ichunkloader;
        this.chunkProvider = ichunkprovider;
    }

    @Override
    public boolean isChunkLoaded(int i, int j) {
        return this.chunks.containsKey(LongHash.toLong(i, j)); // CraftBukkit
    }

    // CraftBukkit start - Change return type to Collection and return the values of our chunk map
    public java.util.Collection a() {
        // return this.chunkList;
        return this.chunks.values();
        // CraftBukkit end
    }

    // MineHQ start
    public void queueUnload(int x, int z) {
        queueUnload(x, z, false);
    }

    public void queueUnload(int i, int j, boolean checked) {
        if (!checked && this.world.getPlayerChunkMap().isChunkInUse(i, j)) return;
        // MineHQ end
        long key = LongHash.toLong(i, j); // IonSpigot - Only create key once
        // PaperSpigot start - Asynchronous lighting updates
        Chunk chunk = chunks.get(key); // IonSpigot
        if (chunk != null && chunk.world.paperSpigotConfig.useAsyncLighting && (chunk.pendingLightUpdates.get() > 0 || chunk.world.getTime() - chunk.lightUpdateTime < 20)) {
            return;
        }
        // PaperSpigot end
        // PaperSpigot start - Don't unload chunk if it contains an entity that loads chunks
        if (chunk != null) {
            for (List<Entity> entities : chunk.entitySlices) {
                for (Entity entity : entities) {
                    if (entity.loadChunks) {
                        return;
                    }
                }
            }
        }
        // PaperSpigot end
        if (this.world.worldProvider.e()) {
            if (!this.world.c(i, j)) {
                // CraftBukkit start
                this.unloadQueue.add(key);  // TacoSpigot - directly invoke LongHash

                Chunk c = chunks.get(key);
                if (c != null) {
                    c.mustSave = true;
                }
                // CraftBukkit end
            }
        } else {
            // CraftBukkit start
            this.unloadQueue.add(key); // TacoSpigot - directly invoke LongHash

            Chunk c = chunks.get(key);
            if (c != null) {
                c.mustSave = true;
            }
            // CraftBukkit end
        }

    }

    public void b() {

        for (Chunk chunk : this.chunks.values()) {
            this.queueUnload(chunk.locX, chunk.locZ);
        }

    }

    // CraftBukkit start - Add async variant, provide compatibility
    public Chunk getChunkIfLoaded(int x, int z) {
        return chunks.get(LongHash.toLong(x, z));
    }

    public Chunk getChunkAt(int i, int j) {
        return getChunkAt(i, j, null);
    }

    public Chunk getChunkAt(int i, int j, Runnable runnable) {
        long key = LongHash.toLong(i, j); // IonSpigot - Only create key once
        Chunk chunk = chunks.get(key); // IonSpigot
        ChunkRegionLoader loader = null;

        if (this.chunkLoader instanceof ChunkRegionLoader) {
            loader = (ChunkRegionLoader) this.chunkLoader;

        }
        // We can only use the queue for already generated chunks
        if (chunk == null && loader != null && loader.chunkExists(world, i, j)) {
            if (runnable != null) {
                ChunkIOExecutor.queueChunkLoad(world, loader, this, i, j, runnable);
                return null;
            } else {
                chunk = ChunkIOExecutor.syncChunkLoad(world, loader, this, i, j);
            }
        } else if (chunk == null) {
            chunk = originalGetChunkAt(i, j);
        }

        unloadQueue.remove(key); // SportPaper
        // If we didn't load the chunk async and have a callback run it now
        if (runnable != null) {
            runnable.run();
        }

        return chunk;
    }

    public Chunk originalGetChunkAt(int i, int j) {
        long key = LongHash.toLong(i, j); // IonSpigot - Only create key once
        Chunk chunk = this.chunks.get(key);
        boolean newChunk = false;
        // CraftBukkit end

        if (chunk == null) {
            world.timings.syncChunkLoadTimer.startTiming(); // Spigot
            chunk = this.loadChunk(i, j);
            if (chunk == null) {
                if (this.chunkProvider == null) {
                    chunk = this.emptyChunk;
                } else {
                    try {
                        chunk = this.chunkProvider.getOrCreateChunk(i, j);
                    } catch (Throwable throwable) {
                        CrashReport crashreport = CrashReport.a(throwable, "Exception generating new chunk");
                        CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Chunk to be generated");

                        crashreportsystemdetails.a("Location", String.format("%d,%d", i, j));
                        crashreportsystemdetails.a("Position hash", key); // CraftBukkit - Use LongHash
                        crashreportsystemdetails.a("Generator", this.chunkProvider.getName());
                        throw new ReportedException(crashreport);
                    }
                }
                newChunk = true; // CraftBukkit
            }

            this.chunks.put(key, chunk);

            chunk.addEntities();

            // CraftBukkit start
            Server server = world.getServer();
            if (server != null) {
                /*
                 * If it's a new world, the first few chunks are generated inside
                 * the World constructor. We can't reliably alter that, so we have
                 * no way of creating a CraftWorld/CraftServer at that point.
                 */
                server.getPluginManager().callEvent(new org.bukkit.event.world.ChunkLoadEvent(chunk.bukkitChunk, newChunk));
            }

            // Update neighbor counts
            for (int x = -2; x < 3; x++) {
                for (int z = -2; z < 3; z++) {
                    if (x == 0 && z == 0) {
                        continue;
                    }

                    Chunk neighbor = this.getChunkIfLoaded(chunk.locX + x, chunk.locZ + z);
                    if (neighbor != null) {
                        neighbor.setNeighborLoaded(-x, -z);
                        chunk.setNeighborLoaded(x, z);
                    }
                }
            }
            // CraftBukkit end
            chunk.loadNearby(this, this, i, j);
            world.timings.syncChunkLoadTimer.stopTiming(); // Spigot
        }

        this.unloadQueue.remove(key); // SportPaper
        return chunk;
    }

    // IonSpigot start - Optimise Chunk Getting
    private Chunk cachedChunk = null;

    @Override
    public Chunk getOrCreateChunk(int i, int j) {
        Chunk chunk = cachedChunk; // We have to do this for thread safety
        if (chunk != null && chunk.locX == i && chunk.locZ == j && chunk.o()) {
            return chunk;
        }
        // CraftBukkit start
        chunk = this.chunks.get(LongHash.toLong(i, j));

        if (chunk == null) {
            if (!this.world.ad() && !this.forceChunkLoad) {
                return this.emptyChunk;
            }

            chunk = this.getChunkAt(i, j);
        }

        cachedChunk = chunk;

        // AetherSpigot start
        /*
        if (chunk == emptyChunk) return chunk;
        if (i != chunk.locX || j != chunk.locZ) {
            b.error("Chunk (" + chunk.locX + ", " + chunk.locZ + ") stored at  (" + i + ", " + j + ") in world '" + world.getWorld().getName() + "'");
            b.error(chunk.getClass().getName());
            Throwable ex = new Throwable();
            ex.fillInStackTrace();
            ex.printStackTrace();
        } */
        // AetherSpigot end

        return chunk;
        // CraftBukkit end
    }

    public Chunk loadChunk(int i, int j) {
        if (this.chunkLoader == null) {
            return null;
        } else {
            try {
                Chunk chunk = this.chunkLoader.a(this.world, i, j);

                if (chunk != null) {
                    chunk.setLastSaved(this.world.getTime());
                    if (this.chunkProvider != null) {
                        world.timings.syncChunkLoadStructuresTimer.startTiming(); // Spigot
                        this.chunkProvider.recreateStructures(chunk, i, j);
                        world.timings.syncChunkLoadStructuresTimer.stopTiming(); // Spigot
                    }
                }

                return chunk;
            } catch (Exception exception) {
                ChunkProviderServer.b.error("Couldn\'t load chunk", exception);
                return null;
            }
        }
    }

    public void saveChunkNOP(Chunk chunk) {
        if (canSave() && this.chunkLoader != null) {
            try {
                this.chunkLoader.b(this.world, chunk);
            } catch (Exception exception) {
                ChunkProviderServer.b.error("Couldn\'t save entities", exception);
            }

        }
    }

    public void saveChunk(Chunk chunk) {
        if (canSave() && this.chunkLoader != null) {
            try {
                chunk.setLastSaved(this.world.getTime());
                this.chunkLoader.a(this.world, chunk);
            } catch (IOException ioexception) {
                ChunkProviderServer.b.error("Couldn\'t save chunk", ioexception);
            } catch (ExceptionWorldConflict exceptionworldconflict) {
                ChunkProviderServer.b.error("Couldn\'t save chunk; already in use by another instance of Minecraft?", exceptionworldconflict);
            }

        }
    }

    @Override
    public void getChunkAt(IChunkProvider ichunkprovider, int i, int j) {
        Chunk chunk = this.getOrCreateChunk(i, j);

        if (!chunk.isDone()) {
            chunk.n();
            if (this.chunkProvider != null) {
                this.chunkProvider.getChunkAt(ichunkprovider, i, j);

                // CraftBukkit start
                BlockSand.instaFall = true;
                Random random = new Random();
                random.setSeed(world.getSeed());
                long xRand = random.nextLong() / 2L * 2L + 1L;
                long zRand = random.nextLong() / 2L * 2L + 1L;
                random.setSeed((long) i * xRand + (long) j * zRand ^ world.getSeed());

                org.bukkit.World world = this.world.getWorld();
                if (world != null) {
                    this.world.populating = true;
                    try {
                        for (org.bukkit.generator.BlockPopulator populator : world.getPopulators()) {
                            populator.populate(world, random, chunk.bukkitChunk);
                        }
                    } finally {
                        this.world.populating = false;
                    }
                }
                BlockSand.instaFall = false;
                this.world.getServer().getPluginManager().callEvent(new org.bukkit.event.world.ChunkPopulateEvent(chunk.bukkitChunk));
                // CraftBukkit end

                chunk.e();
            }
        }

    }

    @Override
    public boolean a(IChunkProvider ichunkprovider, Chunk chunk, int i, int j) {
        if (this.chunkProvider != null && this.chunkProvider.a(ichunkprovider, chunk, i, j)) {
            Chunk chunk1 = this.getOrCreateChunk(i, j);

            chunk1.e();
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean saveChunks(boolean flag, IProgressUpdate iprogressupdate) {
        int i = 0;

        // CraftBukkit start
        for (Chunk chunk : this.chunks.values()) {
            // CraftBukkit end

            if (flag) {
                this.saveChunkNOP(chunk);
            }

            if (chunk.a(flag)) {
                this.saveChunk(chunk);
                chunk.f(false);
                ++i;
                if (i == 24 && !flag) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void c() {
        if (this.chunkLoader != null) {
            this.chunkLoader.b();
        }

    }

    // SportPaper start
    public void unloadAllChunks() {
        for (Chunk chunk : chunks.values()) {
            unloadChunk(chunk);
        }
    }

    public void unloadChunk(Chunk chunk) {
        unloadChunk(chunk, false);
    }

    private void unloadChunk(Chunk chunk, boolean auto) {
        Server server = this.world.getServer();
        ChunkUnloadEvent event = new ChunkUnloadEvent(chunk.bukkitChunk);
        server.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {

            chunk.removeEntities();
            this.saveChunk(chunk);
            this.saveChunkNOP(chunk);
            this.chunks.remove(chunk.chunkKey); // CraftBukkit
            if (!auto && this.unloadQueue.contains(chunk.chunkKey)) {
                this.unloadQueue.remove(chunk.chunkKey);
            }

            // Update neighbor counts
            for (int x = -2; x < 3; x++) {
                for (int z = -2; z < 3; z++) {
                    if (x == 0 && z == 0) {
                        continue;
                    }

                    Chunk neighbor = this.getChunkIfLoaded(chunk.locX + x, chunk.locZ + z);
                    if (neighbor != null) {
                        neighbor.setNeighborUnloaded(-x, -z);
                        chunk.setNeighborUnloaded(x, z);
                    }
                }
            }
        }
    }
    // SportPaper end

    public boolean unloadChunks(boolean force) {
        if (canSave() || force) {
            // CraftBukkit start
            Server server = this.world.getServer();
            // SportPaper start
            LongIterator iterator = unloadQueue.iterator();
            for (int i = 0; i < 100 && iterator.hasNext(); ++i) {
                long chunkcoordinates = iterator.nextLong();
                iterator.remove();
                // SportPaper end
                Chunk chunk = this.chunks.get(chunkcoordinates);
                if (chunk == null) continue;
                unloadChunk(chunk, true); // SportPaper - Move to own method
            }
            // CraftBukkit end

            if (this.chunkLoader != null) {
                this.chunkLoader.a();
            }
        }

        return this.chunkProvider.unloadChunks();
    }

    @Override
    public boolean unloadChunks() {
        return unloadChunks(false);
    }

    @Override
    public boolean canSave() {
        return !this.world.savingDisabled;
    }

    @Override
    public String getName() {
        // CraftBukkit - this.chunks.count() -> .size()
        return "ServerChunkCache: " + this.chunks.size() + " Drop: " + this.unloadQueue.size();
    }

    @Override
    public List<BiomeBase.BiomeMeta> getMobsFor(EnumCreatureType enumcreaturetype, BlockPosition blockposition) {
        return this.chunkProvider.getMobsFor(enumcreaturetype, blockposition);
    }

    @Override
    public BlockPosition findNearestMapFeature(World world, String s, BlockPosition blockposition) {
        return this.chunkProvider.findNearestMapFeature(world, s, blockposition);
    }

    @Override
    public int getLoadedChunks() {
        // CraftBukkit - this.chunks.count() -> this.chunks.size()
        return this.chunks.size();
    }

    @Override
    public void recreateStructures(Chunk chunk, int i, int j) {
    }

    @Override
    public Chunk getChunkAt(BlockPosition blockposition) {
        return this.getOrCreateChunk(blockposition.getX() >> 4, blockposition.getZ() >> 4);
    }
}
