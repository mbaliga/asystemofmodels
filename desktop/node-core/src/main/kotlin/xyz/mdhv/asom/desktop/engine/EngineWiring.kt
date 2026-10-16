package xyz.mdhv.asom.desktop.engine

import xyz.mdhv.asom.inference.LocalEngine
import xyz.mdhv.asom.inference.NoopEngine

/**
 * The node's engine. This wave has exactly one: [NoopEngine], the engine that is honest about not existing. The v2 JNI
 * engine surface (DL4) replaces [engine] then; until then `local-only` fails with `501 LOCAL_ENGINE_ABSENT` and status
 * reports `hasLocalEngine: false`. Any engine claiming to exist is refused here so nothing can serve by accident.
 */
class EngineWiring(val engine: LocalEngine = NoopEngine) {
    init {
        require(!engine.hasLocalEngine) { "no local engine exists before DL4; only NoopEngine may be wired" }
    }

    val backend: String get() = "none"
}
