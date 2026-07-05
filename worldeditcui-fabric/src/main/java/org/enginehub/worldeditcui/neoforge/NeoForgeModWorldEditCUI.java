/*
 * Copyright (c) 2011-2024 WorldEditCUI team and contributors
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.enginehub.worldeditcui.neoforge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import org.enginehub.worldeditcui.WorldEditCUI;
import org.enginehub.worldeditcui.config.CUIConfiguration;
import org.enginehub.worldeditcui.event.listeners.CUIListenerChannel;
import org.enginehub.worldeditcui.event.listeners.CUIListenerWorldRender;
import org.enginehub.worldeditcui.gui.CUIConfigPanel;
import org.enginehub.worldeditcui.protocol.CUIPacket;
import org.enginehub.worldeditcui.protocol.CUIPacketHandler;
import org.enginehub.worldeditcui.render.LegacyVanillaPipelineProvider;
import org.enginehub.worldeditcui.render.OptifinePipelineProvider;
import org.enginehub.worldeditcui.render.PipelineProvider;
import org.enginehub.worldeditcui.render.VanillaPipelineProvider;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.util.List;

@Mod(value = NeoForgeModWorldEditCUI.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeModWorldEditCUI {
    private static final int DELAYED_HELO_TICKS = 10;

    public static final String MOD_ID = "worldeditcui";

    private static final KeyMapping.Category KEYBIND_CATEGORY_WECUI
            = new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "general"));

    private static final List<PipelineProvider> RENDER_PIPELINES = List.of(
            new OptifinePipelineProvider(),
            new LegacyVanillaPipelineProvider(),
            new VanillaPipelineProvider()
    );

    private final KeyMapping keyBindToggleUI = key("toggle", GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping keyBindClearSel = key("clear", GLFW.GLFW_KEY_UNKNOWN);
    private final KeyMapping keyBindChunkBorder = key("chunk", GLFW.GLFW_KEY_UNKNOWN);

    private @Nullable WorldEditCUI controller;
    private @Nullable CUIListenerWorldRender worldRenderListener;
    private @Nullable CUIListenerChannel channelListener;
    private @Nullable Level lastWorld;
    private @Nullable LocalPlayer lastPlayer;

    private boolean visible = true;
    private int delayedHelo = 0;

    public NeoForgeModWorldEditCUI(IEventBus modEventBus, ModContainer modContainer) {
        if (Boolean.getBoolean("wecui.debug.mixinaudit")) {
            MixinEnvironment.getCurrentEnvironment().audit();
        }

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onJoinGame);
        NeoForge.EVENT_BUS.addListener(this::onPostRenderLevel);

        CUIPacketHandler.instance().registerClientboundHandler(this::onPluginMessage);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, (container, parent) ->
                new CUIConfigPanel(parent, this.getOrCreateController(Minecraft.getInstance()).getConfiguration()));
    }

    private static KeyMapping key(final String name, final int code) {
        return new KeyMapping("key." + MOD_ID + '.' + name, code, KEYBIND_CATEGORY_WECUI);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> this.getOrCreateController(Minecraft.getInstance()));
    }

    private void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        event.registerCategory(KEYBIND_CATEGORY_WECUI);
        event.register(this.keyBindToggleUI);
        event.register(this.keyBindClearSel);
        event.register(this.keyBindChunkBorder);
    }

    private void onTick(ClientTickEvent.Post event) {
        if (this.controller == null) {
            return;
        }
        var mc = Minecraft.getInstance();
        final CUIConfiguration config = this.controller.getConfiguration();
        final boolean inGame = mc.player != null;
        final boolean clock = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) > 0;

        if (inGame && mc.screen == null) {
            while (this.keyBindToggleUI.consumeClick()) {
                this.visible = !this.visible;
            }

            while (this.keyBindClearSel.consumeClick()) {
                if (mc.player != null) {
                    mc.player.connection.sendUnattendedCommand("/sel", null);
                }

                if (config.isClearAllOnKey()) {
                    this.controller.clearRegions();
                }
            }

            while (this.keyBindChunkBorder.consumeClick()) {
                this.controller.toggleChunkBorders();
            }
        }

        if (inGame && clock) {
            if (mc.level != this.lastWorld || mc.player != this.lastPlayer) {
                this.lastWorld = mc.level;
                this.lastPlayer = mc.player;
                
                this.controller.getDebugger().debug("World change detected, sending new handshake");
                this.controller.clear();
                this.helo();
                this.delayedHelo = DELAYED_HELO_TICKS;
                if (mc.player != null && config.isPromiscuous()) {
                    mc.player.connection.sendUnattendedCommand("we cui", null);
                }
            }

            if (this.delayedHelo > 0) {
                this.delayedHelo--;
                if (this.delayedHelo == 0) {
                    this.helo();
                }
            }
        }
    }

    private void onPluginMessage(final CUIPacket payload, final CUIPacketHandler.PacketContext ctx) {
        if (this.channelListener == null || this.controller == null) {
            return;
        }

        try {
            ctx.workExecutor().execute(() -> this.channelListener.onMessage(payload));
        } catch (final Exception ex) {
            this.controller.getDebugger().info("Error decoding payload from server", ex);
        }
    }

    private WorldEditCUI getOrCreateController(final Minecraft client) {
        if (this.controller != null) {
            return this.controller;
        }

        this.controller = new WorldEditCUI();
        this.controller.initialise(client);
        this.worldRenderListener = new CUIListenerWorldRender(this.controller, client, this.controller.getConfiguration(), RENDER_PIPELINES);
        this.channelListener = new CUIListenerChannel(this.controller);
        return this.controller;
    }

    private void onJoinGame(final ClientPlayerNetworkEvent.LoggingIn event) {
        if (this.controller == null) {
            return;
        }

        this.visible = true;
        this.controller.getDebugger().debug("Joined game, sending initial handshake");
        this.helo();
    }

    private void onPostRenderLevel(final RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (this.visible && this.worldRenderListener != null) {
            this.worldRenderListener.onRender(Minecraft.getInstance().getDeltaTracker().getRealtimeDeltaTicks());
        }
    }

    private void helo() {
        ClientPacketDistributor.sendToServer(new CUIPacket("v", CUIPacket.protocolVersion()));
    }

    public @Nullable WorldEditCUI getController() {
        return this.controller;
    }
    
}
