# ---------------------------------------------------------------- notifications (Pushover)
# notifysettings: the signed-in app saves settings / sends a test (public, authenticated in code).
# notifytick: private; Cloud Scheduler calls it every minute with an OIDC token to send due reminders.
# Without a Pushover application token, everything is created except the schedule, and the app says
# "not configured" when someone tries to switch notifications on.

locals {
  app_url        = "https://${local.host}"
  pushover_ready = nonsensitive(var.pushover_app_token != null) # whether it is set is not secret
  notify_env     = merge(local.small_function_env, { APP_URL = local.app_url })
}

resource "google_secret_manager_secret" "pushover" {
  project   = var.project_id
  secret_id = "PUSHOVER_TOKEN"

  replication {
    auto {}
  }

  depends_on = [google_project_service.api]
}

resource "google_secret_manager_secret_version" "pushover" {
  secret      = google_secret_manager_secret.pushover.id
  secret_data = local.pushover_ready ? var.pushover_app_token : "unset"
}

resource "google_secret_manager_secret_iam_member" "runtime_reads_pushover" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.pushover.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_cloudfunctions2_function" "notifysettings" {
  project  = var.project_id
  name     = "notifysettings"
  location = var.region

  build_config {
    runtime               = "nodejs22"
    entry_point           = "notifysettings"
    service_account       = google_service_account.build.id
    environment_variables = { GOOGLE_NODE_RUN_SCRIPTS = "" }
    source {
      storage_source {
        bucket = google_storage_bucket.source.name
        object = google_storage_bucket_object.functions.name
      }
    }
  }

  service_config {
    service_account_email            = google_service_account.runtime.email
    available_memory                 = "256Mi"
    available_cpu                    = "1"
    timeout_seconds                  = 30
    min_instance_count               = 0
    max_instance_count               = 2
    max_instance_request_concurrency = 8
    ingress_settings                 = "ALLOW_ALL"
    all_traffic_on_latest_revision   = true
    environment_variables            = local.notify_env

    secret_environment_variables {
      key        = "PUSHOVER_TOKEN"
      project_id = var.project_id
      secret     = google_secret_manager_secret.pushover.secret_id
      version    = google_secret_manager_secret_version.pushover.version
    }
  }

  depends_on = [
    google_project_iam_member.build,
    google_project_iam_member.runtime,
    google_secret_manager_secret_iam_member.runtime_reads_pushover,
    google_storage_bucket_iam_member.build_reads_source,
    google_firestore_database.default,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "notifysettings_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloudfunctions2_function.notifysettings.service_config[0].service
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloudfunctions2_function" "notifytick" {
  project  = var.project_id
  name     = "notifytick"
  location = var.region

  build_config {
    runtime               = "nodejs22"
    entry_point           = "notifytick"
    service_account       = google_service_account.build.id
    environment_variables = { GOOGLE_NODE_RUN_SCRIPTS = "" }
    source {
      storage_source {
        bucket = google_storage_bucket.source.name
        object = google_storage_bucket_object.functions.name
      }
    }
  }

  service_config {
    service_account_email            = google_service_account.runtime.email
    available_memory                 = "256Mi"
    available_cpu                    = "1"
    timeout_seconds                  = 120
    min_instance_count               = 0
    max_instance_count               = 1
    max_instance_request_concurrency = 1
    # Only Cloud Scheduler (with its OIDC token) may call it; no allUsers binding.
    ingress_settings               = "ALLOW_ALL"
    all_traffic_on_latest_revision = true
    environment_variables          = local.notify_env

    secret_environment_variables {
      key        = "PUSHOVER_TOKEN"
      project_id = var.project_id
      secret     = google_secret_manager_secret.pushover.secret_id
      version    = google_secret_manager_secret_version.pushover.version
    }
  }

  depends_on = [
    google_project_iam_member.build,
    google_project_iam_member.runtime,
    google_secret_manager_secret_iam_member.runtime_reads_pushover,
    google_storage_bucket_iam_member.build_reads_source,
    google_firestore_database.default,
  ]
}

resource "google_service_account" "scheduler" {
  project      = var.project_id
  account_id   = "myrecap-scheduler"
  display_name = "My Recap reminder schedule"

  depends_on = [google_project_service.api]
}

resource "google_cloud_run_v2_service_iam_member" "notifytick_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloudfunctions2_function.notifytick.service_config[0].service
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.scheduler.email}"
}

resource "google_cloud_scheduler_job" "notifytick" {
  count     = local.pushover_ready ? 1 : 0
  project   = var.project_id
  region    = var.region
  name      = "myrecap-notify-every-minute"
  schedule  = "* * * * *"
  time_zone = "Etc/UTC"
  # A missed minute is caught up by the next run (each reminder has a 10-minute window), so no retries.
  attempt_deadline = "120s"
  retry_config {
    retry_count = 0
  }

  http_target {
    http_method = "POST"
    uri         = google_cloudfunctions2_function.notifytick.service_config[0].uri
    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = google_cloudfunctions2_function.notifytick.service_config[0].uri
    }
  }

  depends_on = [google_cloud_run_v2_service_iam_member.notifytick_invoker]
}

# The log of sent notifications deletes itself after two weeks.
resource "google_firestore_field" "notifylog_ttl" {
  project    = var.project_id
  database   = google_firestore_database.default.name
  collection = "notifylog"
  field      = "expireAt"

  ttl_config {}
  index_config {}
}
