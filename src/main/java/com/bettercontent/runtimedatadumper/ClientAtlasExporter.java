package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.CommandDispatcher;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Client-only, live-rendered item/quest icon atlas exporter. */
public final class ClientAtlasExporter {
    static final int TILE = 64;
    static final int COLUMNS = 32;
    static final int PAGE_SIZE = TILE * COLUMNS;
    private static Job active;
    private static boolean autoStarted;

    private ClientAtlasExporter() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ClientAtlasExporter.class);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("runtimedata").then(Commands.literal("atlas").executes(ctx -> {
            if (active != null) {
                ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("Atlas export already running"));
                return 0;
            }
            ClientQuestFile quests = ClientQuestFile.INSTANCE;
            if (quests == null || quests.getAllChapters().isEmpty()) {
                ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("FTB Quests has not synchronized yet"));
                return 0;
            }
            try {
                active = Job.create(quests);
            } catch (Exception e) {
                ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("Atlas setup failed: " + e.getMessage()));
                RecipeGraphMod.LOGGER.error("Atlas setup failed", e);
                return 0;
            }
            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "Atlas export started: " + active.entries.size() + " icons"), false);
            return 1;
        })));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            if (active.renderNextPage()) {
                Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Atlas complete: " + active.finalDirectory), false);
                active = null;
            }
        } catch (Exception e) {
            RecipeGraphMod.LOGGER.error("Quest atlas export failed", e);
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("Atlas FAILED: " + e.getMessage()), false);
            active = null;
        }
    }

    @SubscribeEvent
    public static void autoStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || autoStarted || active != null
                || !Boolean.getBoolean("runtime_data_dumper.auto_atlas")) return;
        ClientQuestFile quests = ClientQuestFile.INSTANCE;
        if (quests == null || quests.getAllChapters().isEmpty()) return;
        autoStarted = true;
        try { active = Job.create(quests); }
        catch (Exception e) { RecipeGraphMod.LOGGER.error("Automatic atlas setup failed", e); }
    }

    record Entry(String key, String kind, Icon icon, ItemStack stack, String questId, String chapterId, JsonObject descriptor) {}

    static final class Job {
        final List<Entry> entries;
        final Path stagingDirectory;
        final Path finalDirectory;
        final String snapshotId;
        final JsonObject manifest;
        final JsonArray rows = new JsonArray();
        final JsonArray failures = new JsonArray();
        int offset;

        Job(List<Entry> entries, Path stagingDirectory, Path finalDirectory, String snapshotId, JsonObject manifest) {
            this.entries = entries; this.stagingDirectory = stagingDirectory; this.finalDirectory = finalDirectory;
            this.snapshotId = snapshotId; this.manifest = manifest;
        }

        static Job create(ClientQuestFile quests) throws Exception {
            List<Entry> entries = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                ItemStack stack = new ItemStack(item);
                entries.add(new Entry(id.toString(), "registry", ItemIcon.getItemIcon(stack), stack, "", "", new JsonObject()));
            }
            quests.forAllQuests(quest -> {
                JsonObject descriptor = new JsonObject();
                try { descriptor = quest.getIcon().getJson().getAsJsonObject(); }
                catch (Exception ignored) { descriptor.addProperty("icon", quest.getIcon().toString()); }
                entries.add(new Entry(String.format(Locale.ROOT, "%016X", quest.id), "quest", quest.getIcon(), ItemStack.EMPTY,
                        String.format(Locale.ROOT, "%016X", quest.id), String.format(Locale.ROOT, "%016X", quest.getChapter().id), descriptor));
            });
            entries.sort(Comparator.comparing(Entry::kind).thenComparing(Entry::key));
            String fingerprint = AtlasManifestSupport.fingerprint(entries.stream().map(e -> e.kind + "\0" + e.key).toList());
            String snapshot = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14) + "-" + fingerprint.substring(0, 12);
            Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("generated/runtime-atlases");
            Path staging = root.resolve(snapshot + ".tmp");
            Path target = root.resolve(snapshot);
            Files.createDirectories(staging.resolve("pages"));
            JsonObject manifest = baseManifest(snapshot, fingerprint, entries.size());
            return new Job(List.copyOf(entries), staging, target, snapshot, manifest);
        }

        boolean renderNextPage() throws Exception {
            int page = offset / (COLUMNS * COLUMNS);
            int end = Math.min(entries.size(), offset + COLUMNS * COLUMNS);
            Path pagePath = stagingDirectory.resolve("pages/page-%04d.png".formatted(page));
            renderPage(entries.subList(offset, end), page, pagePath);
            offset = end;
            RecipeGraphMod.LOGGER.info("Atlas page {} complete: {}/{} icons", page, offset, entries.size());
            if (offset < entries.size()) return false;
            manifest.add("entries", rows);
            manifest.add("failures", failures);
            manifest.addProperty("failure_count", failures.size());
            manifest.addProperty("complete", failures.isEmpty() && rows.size() == entries.size());
            manifest.addProperty("entry_count", rows.size());
            exportGraphResources();
            writeAtomic(stagingDirectory.resolve("atlas.json"), manifest);
            if (!failures.isEmpty()) throw new IOException(failures.size() + " atlas icons failed; staged evidence retained at " + stagingDirectory);
            Files.move(stagingDirectory, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
            return true;
        }

        void exportGraphResources() throws Exception {
            JsonArray resources = new JsonArray();
            Set<ResourceLocation> ids = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
            for (String shape : QuestShape.map().keySet()) for (String layer : List.of("shape", "background", "outline"))
                ids.add(new ResourceLocation("ftbquests", "textures/shapes/" + shape + "/" + layer + ".png"));
            ids.add(new ResourceLocation("ftbquests", "textures/gui/dependency.png"));
            ids.add(new ResourceLocation("ftbquests", "textures/gui/link.png"));
            for (ResourceLocation id : ids) {
                var resource = Minecraft.getInstance().getResourceManager().getResource(id)
                        .orElseThrow(() -> new IOException("missing graph resource " + id));
                Path path = stagingDirectory.resolve("resources").resolve(id.getNamespace()).resolve(id.getPath());
                Files.createDirectories(path.getParent());
                try (var in = resource.open()) { Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING); }
                JsonObject row = new JsonObject(); row.addProperty("id", id.toString());
                row.addProperty("path", stagingDirectory.relativize(path).toString()); row.addProperty("sha256", sha256(path)); resources.add(row);
            }
            manifest.add("graph_resources", resources);
        }

        void renderPage(List<Entry> pageEntries, int page, Path output) throws Exception {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget previous = mc.getMainRenderTarget();
            TextureTarget target = new TextureTarget(PAGE_SIZE, PAGE_SIZE, true, Minecraft.ON_OSX);
            RenderSystem.backupProjectionMatrix();
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            try {
                target.setClearColor(0, 0, 0, 0); target.clear(Minecraft.ON_OSX); target.bindWrite(true);
                RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, PAGE_SIZE, PAGE_SIZE, 0, 1000, 21000), com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
                modelView.setIdentity(); modelView.translate(0, 0, -11000); RenderSystem.applyModelViewMatrix();
                MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
                GuiGraphics graphics = new GuiGraphics(mc, buffers);
                for (int i = 0; i < pageEntries.size(); i++) {
                    Entry e = pageEntries.get(i); int col = i % COLUMNS, row = i / COLUMNS;
                    try {
                        graphics.pose().pushPose(); graphics.pose().translate(col * TILE, row * TILE, 0);
                        graphics.pose().scale(4, 4, 1); e.icon.drawStatic(graphics, 0, 0, 16, 16); graphics.pose().popPose();
                    } catch (Throwable t) {
                        JsonObject fail = new JsonObject(); fail.addProperty("key", e.key); fail.addProperty("kind", e.kind);
                        fail.addProperty("error", t.getClass().getName() + ": " + Objects.toString(t.getMessage(), "")); failures.add(fail);
                    }
                }
                graphics.flush(); buffers.endBatch();
                try (NativeImage image = Screenshot.takeScreenshot(target)) {
                    image.writeToFile(output);
                    for (int i = 0; i < pageEntries.size(); i++) addRow(pageEntries.get(i), page, i, image);
                }
            } finally {
                target.destroyBuffers(); previous.bindWrite(true); modelView.popPose(); RenderSystem.applyModelViewMatrix(); RenderSystem.restoreProjectionMatrix();
            }
        }

        void addRow(Entry e, int page, int index, NativeImage image) throws Exception {
            int col=index%COLUMNS,row=index/COLUMNS,x=col*TILE,y=row*TILE; boolean visible=false;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(int yy=y;yy<y+TILE;yy++)for(int xx=x;xx<x+TILE;xx++){int rgba=image.getPixelRGBA(xx,yy);digest.update((byte)rgba);digest.update((byte)(rgba>>>8));digest.update((byte)(rgba>>>16));digest.update((byte)(rgba>>>24));visible|=(rgba>>>24)!=0;}
            JsonObject o=new JsonObject();o.addProperty("key",e.key);o.addProperty("kind",e.kind);o.addProperty("page","pages/page-%04d.png".formatted(page));o.addProperty("x",x);o.addProperty("y",y);o.addProperty("width",TILE);o.addProperty("height",TILE);o.addProperty("rgba_sha256",HexFormat.of().formatHex(digest.digest()));o.addProperty("visible",visible);
            if(!e.stack.isEmpty()){CompoundTag tag=new CompoundTag();e.stack.save(tag);o.addProperty("stack_snbt",tag.toString());}
            if(!e.questId.isEmpty()){o.addProperty("quest_id",e.questId);o.addProperty("chapter_id",e.chapterId);o.add("icon",e.descriptor);}
            o.addProperty("status",visible?"rendered":"transparent_default");
            if(!visible&&e.kind.equals("quest")){JsonObject f=new JsonObject();f.addProperty("key",e.key);f.addProperty("kind",e.kind);f.addProperty("error","transparent_render");failures.add(f);} rows.add(o);
        }
    }

    static JsonObject baseManifest(String snapshot, String fingerprint, int count) {
        JsonObject m=new JsonObject();m.addProperty("schema","bc.quest_icon_atlas.v1");m.addProperty("snapshot_id",snapshot);m.addProperty("generated_at",Instant.now().toString());m.addProperty("minecraft_version",Minecraft.getInstance().getLaunchedVersion());m.addProperty("content_fingerprint",fingerprint);m.addProperty("tile_size",TILE);m.addProperty("page_size",PAGE_SIZE);m.addProperty("planned_entry_count",count);
        JsonArray mods=new JsonArray();ModList.get().getMods().stream().sorted(Comparator.comparing(x->x.getModId())).forEach(info->{JsonObject o=new JsonObject();o.addProperty("id",info.getModId());o.addProperty("version",info.getVersion().toString());mods.add(o);});m.add("mods",mods);
        JsonArray packs=new JsonArray();for(Pack p:Minecraft.getInstance().getResourcePackRepository().getSelectedPacks())packs.add(p.getId());m.add("resource_packs",packs);return m;
    }
    static void writeAtomic(Path path,JsonObject json)throws IOException{Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,json.toString()+"\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    static String sha256(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
