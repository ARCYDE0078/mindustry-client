package mindustrytool.features.chat.global;

import java.time.Instant;
import java.util.Optional;

import arc.Core;
import arc.Events;
import arc.scene.ui.Dialog;
import arc.util.Log;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;
import mindustrytool.Utils;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;
import mindustrytool.features.auth.dto.LoginEvent;
import mindustrytool.features.chat.global.dto.ChatMessage;
import mindustrytool.features.chat.global.events.ChatMessageReceive;
import mindustrytool.features.chat.global.ui.ChatOverlay;

import mindustrytool.features.chat.global.dto.ChannelDto;

public class ChatFeature implements Feature {
    private ChatOverlay overlay;

    BaseDialog settingDialog;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.chat")
                .description("@feature.chat.description")
                .icon(Icon.chat)
                .enabledByDefault(true)
                .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        Events.on(ChatMessageReceive.class, event -> {
            ChatStore store = ChatStore.getInstance();
            String currentChannelId = store.getCurrentChannelId();

            for (ChatMessage msg : event.messages) {
                String channelId = msg.channelId;

                store.addMessages(channelId, Seq.with(msg));

                ChannelDto channel = store.getChannels().find(c -> c.id.equals(channelId));
                if (channel != null) {
                    channel.lastMessageId = msg.id;
                }

                try {
                    if ((ChatConfig.collapsed() || !channelId.equals(currentChannelId))
                            && ChatConfig.lastRead().isBefore(Instant.parse(msg.createdAt))) {
                        store.addUnread(channelId, 1);
                    } else if (!ChatConfig.collapsed() && channelId.equals(currentChannelId)) {
                        store.setLastReadMessageId(channelId, msg.id);
                    }
                } catch (Exception e) {
                    Log.err(e);
                }
            }
        });

        Events.on(LoginEvent.class, e -> {
            ChatService.getInstance().disconnectStream();
            ChatService.getInstance().connectStream();
        });

        Utils.onAppExit(ChatService.getInstance()::disconnectStream);

        ChatService.getInstance().init();
    }

    @Override
    public void onEnable() {
        //один оверлей на всю сессию: каждый new ChatOverlay() вешал свои Events/клавиши/подписки, которые никогда не снимались
        if (overlay == null) {
            overlay = new ChatOverlay();
        }

        ChatService.getInstance().setActive(true);
        ChatService.getInstance().disconnectStream();
        ChatService.getInstance().connectStream();

        if (Vars.ui.menuGroup != null) {
            Core.app.post(() -> {
                if (overlay.parent == null) Core.scene.add(overlay);
            });
        }
    }

    @Override
    public void onDisable() {
        ChatService.getInstance().setActive(false);
        ChatService.getInstance().disconnectStream();

        if (overlay != null) {
            overlay.remove();
        }
    }

    @Override
    public Optional<Dialog> setting() {
        if (settingDialog == null) {
            settingDialog = new ChatSettingsDialog(this);
        }
        return Optional.of(settingDialog);
    }

    public ChatOverlay getOverlay() {
        return overlay;
    }

    public void rebuildOverlay() {
        if (overlay != null) {
            overlay.rebuild();
        }
    }
}
