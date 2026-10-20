# Room, WorkManager and Compose ship their own consumer rules.
# Workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
