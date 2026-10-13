# org.json and the platform classes used by :core are part of the Android framework.
# WorkManager workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# Credential Manager finds its Google Play services provider by reflection (Google sign-in for sharing).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
