# DOT release rules.
# Room, Kotlin serialization and Compose ship their own consumer rules; these are
# for DOT's own reflective entry points only.

# Domain models are serialized by name in the memory store and by class in
# Room's type converters — keep their shape.
-keep class com.dot.core.model.** { *; }

# Tool names are the wire contract between the router and the registry.
-keepclassmembers class com.dot.agent.tools.** {
    <init>(...);
    <fields>;
}
