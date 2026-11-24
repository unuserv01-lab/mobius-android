# MOBIUS hackathon build: minification is disabled, so no custom rules are required.
# If you enable isMinifyEnabled for release, keep the service entry points:
-keep class com.mobius.accessibility.MobiusAccessibilityService { *; }
-keep class com.mobius.notification.NotificationListener { *; }
-keep class com.mobius.bubble.BubbleService { *; }
