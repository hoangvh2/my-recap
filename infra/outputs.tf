output "url" {
  description = "Open this on the iPhone, then Share > Add to Home Screen."
  value       = "https://${local.host}"
}

output "web_env" {
  description = "Build-time settings of the web app (identifiers, not secrets). Consumed by deploy.mjs."
  value = {
    VITE_FIREBASE_API_KEY     = data.google_firebase_web_app_config.web.api_key
    VITE_FIREBASE_PROJECT_ID  = var.project_id
    VITE_FIREBASE_APP_ID      = google_firebase_web_app.web.app_id
    VITE_FIREBASE_AUTH_DOMAIN = local.host
    VITE_APPCHECK_SITE_KEY    = google_recaptcha_enterprise_key.web.name
    VITE_FUNCTIONS_REGION     = var.region
    VITE_FCM_VAPID_KEY        = var.fcm_vapid_key == null ? "" : var.fcm_vapid_key
  }
}

output "allowed_emails" {
  value = local.emails
}

output "auth_console" {
  description = "One manual step: enable the Google provider here."
  value       = "https://console.firebase.google.com/project/${var.project_id}/authentication/providers"
}

output "notifications" {
  description = "Phone notifications: on when pushover_app_token is set."
  value       = !local.pushover_ready ? "off: set pushover_app_token in terraform.tfvars and deploy again" : "on: every minute; each person enters their Pushover User Key in Settings"
}

output "android_env" {
  description = "Build settings of the Android app (identifiers, not secrets): export them before ./gradlew assembleRelease, or set them as GitHub Actions variables. FIREBASE_WEB_CLIENT_ID is copied by hand (see infra/README.md)."
  value = local.android_config == null ? null : {
    FIREBASE_PROJECT_ID = var.project_id
    FIREBASE_APP_ID     = google_firebase_android_app.android[0].app_id
    FIREBASE_API_KEY    = local.android_config.client[0].api_key[0].current_key
    FIREBASE_SENDER_ID  = local.android_config.project_info.project_number
    FUNCTIONS_REGION    = var.region
  }
}
