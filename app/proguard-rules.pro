# Add project specific ProGuard rules here.
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class com.sukoon.autoprint.** { *; }
