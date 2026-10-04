package me.leoko.advancedban.bukkit.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PaperChatListenerTest {
    private AsyncChatEvent event() {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> null);
        return new AsyncChatEvent(true, player, Collections.emptySet(), ChatRenderer.defaultRenderer(),
                Component.text("hello"), Component.text("hello"), null);
    }
    @Test void mutedAndDegradedAgentChatIsCancelled() {
        AsyncChatEvent event = event();
        new PaperChatListener(() -> false, player -> true).onChat(event);
        assertTrue(event.isCancelled());
    }
    @Test void unmutedChatIsPreserved() {
        AsyncChatEvent event = event();
        new PaperChatListener(() -> false, player -> false).onChat(event);
        assertFalse(event.isCancelled());
        assertEquals(Component.text("hello"), event.message());
    }
    @Test void signedVelocityCancellationIsNeverUndoneOrWarnedTwice() {
        AtomicInteger calls = new AtomicInteger();
        AsyncChatEvent event = event();
        event.setCancelled(true);
        new PaperChatListener(() -> false, player -> { calls.incrementAndGet(); return false; }).onChat(event);
        assertTrue(event.isCancelled());
        assertEquals(0, calls.get());
    }
    @Test void activeChatSyncerOwnsItsPreSendDecision() {
        AsyncChatEvent event = event();
        new PaperChatListener(() -> true, player -> { fail("duplicate mute gate"); return true; }).onChat(event);
        assertFalse(event.isCancelled());
    }
    @Test void enforcementPrecedesChatSyncerNormalPriorityRouting() throws Exception {
        EventHandler handler = PaperChatListener.class.getMethod("onChat", AsyncChatEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.LOW, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }
}
