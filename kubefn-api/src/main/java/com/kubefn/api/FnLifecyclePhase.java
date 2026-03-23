package com.kubefn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a function to run during a specific organism lifecycle phase.
 * Functions annotated with this are invoked by the runtime at the
 * designated phase, ordered by the order field (lower runs first).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FnLifecyclePhase {

    /** The lifecycle phase during which this function should execute. */
    Phase phase();

    /** Execution order within the phase. Lower values run earlier. */
    int order() default 0;

    /** Lifecycle phases of a KubeFn organism. */
    enum Phase {
        /** Organism is initializing — run setup logic. */
        INIT,
        /** Organism is ready to serve traffic. */
        READY,
        /** Organism is about to stop — run pre-shutdown cleanup. */
        PRE_STOP,
        /** Organism is shutting down — run final teardown. */
        SHUTDOWN
    }
}
