# Room, Hilt, WorkManager and Compose ship their own consumer rules.
# Keep enum names because they are persisted as strings in Room and DataStore.
-keepclassmembers enum com.batterymaster.ai.** { *; }
