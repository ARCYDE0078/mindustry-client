package qol.core;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType.Trigger;

import java.lang.reflect.Field;

/**
 * Workaround for a latent crash in foo's client's modified {@code arc.Events}. Its enum
 * {@code fire()} caches the listener array but RE-READS {@code listeners.size} every iteration
 * (vanilla arc caches both); if any code adds a listener for the currently-firing event from inside
 * one of its listeners while the backing array is exactly full, the Seq swaps in a bigger array and
 * bumps size - and the loop then reads the STALE cached array at the new size:
 * {@code ArrayIndexOutOfBoundsException: Index N out of bounds for length N} in
 * {@code arc.Events.fire}, crashing the whole game. Which mod happens to trip it is luck: the crash
 * needs the total listener count to sit exactly on a Seq growth boundary (16, 28, 49, ...), so any
 * mod (this one included) merely ADDING enough load-time listeners can shift someone else's runtime
 * registration onto the boundary.
 * <p>
 * The guard keeps every event's listener Seq at least {@link #HEADROOM} slots ahead of its size, so
 * a runtime add never lands on a full array and the stale-cache path never executes. Re-checked
 * periodically to cover listeners (and whole new event types) registered after client load.
 * Growing a Seq from inside an update listener is itself safe with the fork's fire loop: resizing
 * replaces the backing array but doesn't change size, and the in-flight loop keeps iterating its
 * cached - old, but still complete - array.
 * <p>
 * Reaches the private {@code Events.events} map reflectively; if the field ever changes shape in a
 * future client build, the guard just logs and disables itself instead of breaking anything.
 */
public class EventsOverflowGuard{
    static final int HEADROOM = 64;
    static final float RECHECK_INTERVAL_TICKS = 300f;

    static Field eventsField;
    static float timer = 0f;
    static boolean installed = false;

    /** Идемпотентно: ставится из Main.init (всегда, независимо от qol), повторный вызов ничего не делает. */
    public static void install(){
        if(installed) return;
        installed = true;
        try{
            eventsField = Events.class.getDeclaredField("events");
            eventsField.setAccessible(true);
            ensureAll();
        }catch(Throwable t){
            eventsField = null;
            Log.warn("[QoL Suite] Events listener map not reachable, overflow guard disabled: @", t);
            return;
        }
        Events.run(Trigger.update, EventsOverflowGuard::tick);
    }

    static void tick(){
        timer += Time.delta;
        if(timer < RECHECK_INTERVAL_TICKS) return;
        timer = 0f;
        ensureAll();
    }

    @SuppressWarnings("unchecked")
    static void ensureAll() throws RuntimeException{
        if(eventsField == null) return;
        try{
            ObjectMap<Object, Seq<?>> map = (ObjectMap<Object, Seq<?>>)eventsField.get(null);
            for(Seq<?> listeners : map.values()){
                listeners.ensureCapacity(HEADROOM);
            }
        }catch(Throwable t){
            eventsField = null;
            Log.warn("[QoL Suite] Events overflow guard failed, disabling: @", t);
        }
    }
}
