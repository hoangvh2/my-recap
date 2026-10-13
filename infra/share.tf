# ---------------------------------------------------------------- sharing between people
# share:     the signed-in apps (web and Android) make / enter link codes, unlink, and choose who sees an item.
# members:   administrators (allowed_emails) let more people in from Settings, no redeploy.
# sharesync: runs on every write to users/{uid}/items/{itemId}; keeps the read-only copy others see in
#            shares/ up to date and pushes "X shared / changed / deleted" to them (FCM, else Pushover).
# Both callables check sign-in and the allowlist in code; they skip App Check because the sideloaded
# Android app cannot get a token (see infra/README.md, "Sharing and the Android app").

resource "google_project_iam_member" "runtime_fcm" {
  project = var.project_id
  role    = "roles/firebasecloudmessaging.admin" # send pushes to the phones and browsers people registered
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_cloudfunctions2_function" "share" {
  project  = var.project_id
  name     = "share"
  location = var.region

  build_config {
    runtime               = "nodejs22"
    entry_point           = "share"
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
    timeout_seconds                  = 60
    min_instance_count               = 0
    max_instance_count               = 3
    max_instance_request_concurrency = 16
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
    google_project_iam_member.runtime_fcm,
    google_secret_manager_secret_iam_member.runtime_reads_pushover,
    google_storage_bucket_iam_member.build_reads_source,
    google_firestore_database.default,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "share_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloudfunctions2_function.share.service_config[0].service
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloudfunctions2_function" "members" {
  project  = var.project_id
  name     = "members"
  location = var.region

  build_config {
    runtime               = "nodejs22"
    entry_point           = "members"
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
    environment_variables            = local.small_function_env
  }

  depends_on = [
    google_project_iam_member.build,
    google_project_iam_member.runtime,
    google_storage_bucket_iam_member.build_reads_source,
    google_firestore_database.default,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "members_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloudfunctions2_function.members.service_config[0].service
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# Eventarc delivers the Firestore change to sharesync as this identity; nobody else may call it.
resource "google_service_account" "trigger" {
  project      = var.project_id
  account_id   = "myrecap-trigger"
  display_name = "VHub Desk Firestore trigger"

  depends_on = [google_project_service.api]
}

resource "google_project_iam_member" "trigger_receives_events" {
  project = var.project_id
  role    = "roles/eventarc.eventReceiver"
  member  = "serviceAccount:${google_service_account.trigger.email}"
}

# Older projects: the Pub/Sub agent must be able to mint tokens for the push to the function.
resource "google_project_iam_member" "pubsub_token_creator" {
  project = var.project_id
  role    = "roles/iam.serviceAccountTokenCreator"
  member  = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"

  depends_on = [google_project_service.api]
}

resource "google_cloudfunctions2_function" "sharesync" {
  project  = var.project_id
  name     = "sharesync"
  location = var.region

  build_config {
    runtime               = "nodejs22"
    entry_point           = "sharesync"
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
    timeout_seconds                  = 60
    min_instance_count               = 0
    max_instance_count               = 5
    max_instance_request_concurrency = 16
    # Only Eventarc calls it (with the trigger identity's token); no allUsers binding.
    ingress_settings               = "ALLOW_INTERNAL_ONLY"
    all_traffic_on_latest_revision = true
    environment_variables          = local.notify_env

    secret_environment_variables {
      key        = "PUSHOVER_TOKEN"
      project_id = var.project_id
      secret     = google_secret_manager_secret.pushover.secret_id
      version    = google_secret_manager_secret_version.pushover.version
    }
  }

  event_trigger {
    trigger_region        = var.firestore_location
    event_type            = "google.cloud.firestore.document.v1.written"
    retry_policy          = "RETRY_POLICY_DO_NOT_RETRY" # a missed push is better than a repeated one
    service_account_email = google_service_account.trigger.email

    event_filters {
      attribute = "database"
      value     = google_firestore_database.default.name
    }
    event_filters {
      attribute = "document"
      value     = "users/{uid}/items/{itemId}"
      operator  = "match-path-pattern"
    }
  }

  depends_on = [
    google_project_iam_member.build,
    google_project_iam_member.runtime,
    google_project_iam_member.runtime_fcm,
    google_project_iam_member.trigger_receives_events,
    google_project_iam_member.pubsub_token_creator,
    google_secret_manager_secret_iam_member.runtime_reads_pushover,
    google_storage_bucket_iam_member.build_reads_source,
    google_firestore_database.default,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "sharesync_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloudfunctions2_function.sharesync.service_config[0].service
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.trigger.email}"
}

# Unused link codes delete themselves an hour after they expire (the function refuses them from expiry on).
resource "google_firestore_field" "sharecode_ttl" {
  project    = var.project_id
  database   = google_firestore_database.default.name
  collection = "shareCodes"
  field      = "expireAt"

  ttl_config {}
  index_config {}
}

# ---------------------------------------------------------------- the Android app (optional)
# Registering the app with its signing certificate's SHA-1 creates the Android OAuth client Google
# sign-in needs. The build reads the values from the `android_env` output (see app/build.gradle.kts).

resource "google_firebase_android_app" "android" {
  count           = length(var.android_sha1) > 0 ? 1 : 0
  provider        = google-beta
  project         = var.project_id
  display_name    = "VHub Desk Android"
  package_name    = "com.vh.myrecap"
  sha1_hashes     = [for h in var.android_sha1 : lower(replace(h, ":", ""))]
  deletion_policy = "DELETE"

  depends_on = [google_firebase_project.this]
}

data "google_firebase_android_app_config" "android" {
  count    = length(var.android_sha1) > 0 ? 1 : 0
  provider = google-beta
  project  = var.project_id
  app_id   = google_firebase_android_app.android[0].app_id
}

locals {
  android_config = length(var.android_sha1) > 0 ? jsondecode(base64decode(data.google_firebase_android_app_config.android[0].config_file_contents)) : null
}
