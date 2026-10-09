# org.json and the platform classes used by :core are part of the Android framework.
# WorkManager workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
