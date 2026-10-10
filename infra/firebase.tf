resource "google_firebase_project" "this" {
  provider = google-beta
  project  = var.project_id

  depends_on = [google_project_service.api]
}

resource "google_firebase_web_app" "web" {
  provider        = google-beta
  project         = var.project_id
  display_name    = "My Recap Thu ky"
  deletion_policy = "DELETE"

  depends_on = [google_firebase_project.this]
}

data "google_firebase_web_app_config" "web" {
  provider   = google-beta
  project    = var.project_id
  web_app_id = google_firebase_web_app.web.app_id
}

locals {
  # The app is served from, and signs in against, this one host (see web/src/main.tsx): the custom
  # domain when there is one, otherwise the default Firebase Hosting host.
  default_host = "${var.project_id}.firebaseapp.com"
  host         = coalesce(var.custom_domain, local.default_host)
}
