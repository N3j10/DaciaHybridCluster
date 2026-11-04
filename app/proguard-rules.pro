# Konfiguracja jest zapisywana jako JSON przez Gson (ConfigManager) – nazwy pól muszą przetrwać R8.
-keep class com.dacia.hybridcluster.model.UiConfig { *; }
-keepclassmembers enum com.dacia.hybridcluster.model.** { *; }
