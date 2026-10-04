package me.leoko.advancedban.velocity.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlayerInputListenerVelocityTest {
    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> null);
    }
    private void execute(EventTask task) {
        assertNotNull(task);
        assertTrue(task.requiresAsync());
        task.execute(new Continuation() {
            public void resume() { }
            public void resumeWithException(Throwable failure) { throw new AssertionError(failure); }
        });
    }
    @Test void mutedPlayerIsDeniedEvenWithoutPaperAgent() {
        PlayerChatEvent event = new PlayerChatEvent(player(), "blocked");
        execute(new PlayerInputListenerVelocity(p -> true).onChat(event));
        assertFalse(event.getResult().isAllowed());
    }
    @Test void unmutedPlayerIsNotModified() {
        PlayerChatEvent event = new PlayerChatEvent(player(), "hello");
        execute(new PlayerInputListenerVelocity(p -> false).onChat(event));
        assertSame(PlayerChatEvent.ChatResult.allowed(), event.getResult());
    }
    @Test void existingDenialDoesNotProduceDuplicateWarning() {
        AtomicInteger calls = new AtomicInteger();
        PlayerChatEvent event = new PlayerChatEvent(player(), "blocked");
        event.setResult(PlayerChatEvent.ChatResult.denied());
        assertNull(new PlayerInputListenerVelocity(p -> { calls.incrementAndGet(); return true; }).onChat(event));
        assertEquals(0, calls.get());
        assertFalse(event.getResult().isAllowed());
    }
    @Test void gateRunsBeforeSignedVelocityFinalTransportListener() throws Exception {
        assertTrue(PlayerInputListenerVelocity.class.getMethod("onChat", PlayerChatEvent.class)
                .getAnnotation(Subscribe.class).priority() > Short.MIN_VALUE);
    }
}
