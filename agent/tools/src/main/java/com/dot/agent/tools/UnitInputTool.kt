package com.dot.agent.tools

/**
 * Marker for a tool that takes no input, expressed as DotTool<Unit, O>.
 *
 * The registry dispatches on this instead of casting blindly to
 * DotTool<Map<String, String?>, _>. Without it, a Unit-input tool receives an
 * empty map and throws ClassCastException on the synthetic bridge — surfacing as
 * a tool_error rather than as the tool actually running.
 */
interface UnitInputTool<O> : DotTool<Unit, O> {

    suspend fun executeNow(): O = execute(Unit)
}
