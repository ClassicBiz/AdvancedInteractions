package com.luminacity.advancedinteractions.client;

import com.luminacity.advancedinteractions.network.ConfirmPackets;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.util.TextFmt;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** The "are you sure?" popup for a hologram element with a confirmation message. */
public class ConfirmClickScreen extends Screen {
    private final int token;
    private final Component text;
    private List<FormattedCharSequence> lines = List.of();
    private boolean answered;

    private ConfirmClickScreen(int token, String title, String text) {
        super(TextFmt.component(title == null || title.isBlank() ? "Are you sure?" : title));
        this.token = token;
        this.text = TextFmt.component(text == null ? "" : text.replace("\\n", "\n"));
    }

    public static void open(int token, String title, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new ConfirmClickScreen(token, title, text));
    }

    @Override
    protected void init() {
        lines = font.split(text, Math.min(300, width - 40));
        int y = height / 2 + lines.size() * 5 + 10;
        addRenderableWidget(new Button(width / 2 - 104, y, 100, 20, Component.literal("Yes"), b -> answer(true)));
        addRenderableWidget(new Button(width / 2 + 4, y, 100, 20, Component.literal("No"), b -> answer(false)));
    }

    private void answer(boolean yes) {
        if (!answered) {
            answered = true;
            NetworkHandler.CHANNEL.sendToServer(new ConfirmPackets.Answer(token, yes));
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (!answered) { answered = true; NetworkHandler.CHANNEL.sendToServer(new ConfirmPackets.Answer(token, false)); }
        super.onClose();
    }

    @Override
    public void render(PoseStack pose, int mx, int my, float pt) {
        renderBackground(pose);
        int top = height / 2 - lines.size() * 5 - 20;
        drawCenteredString(pose, font, title, width / 2, top, 0xFFFFFF);
        for (int i = 0; i < lines.size(); i++) {
            font.drawShadow(pose, lines.get(i), (width - font.width(lines.get(i))) / 2f, top + 16 + i * 10, 0xFFFFFF);
        }
        super.render(pose, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
