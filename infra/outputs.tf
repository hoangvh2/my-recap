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
  }
}

output "allowed_emails" {
  value = local.emails
}

output "auth_console" {
  description = "One manual step: enable the Google provider here."
  value       = "https://console.firebase.google.com/project/${var.project_id}/authentication/providers"
}
