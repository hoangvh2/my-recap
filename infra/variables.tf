variable "project_id" {
  description = "Existing Google Cloud project with billing enabled (the one your Gemini usage already bills to)."
  type        = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid Google Cloud project ID."
  }
}

variable "allowed_emails" {
  description = "Google accounts allowed to sign in and use the app. Hard-coded into the Firestore rules and the function at apply time."
  type        = list(string)
  validation {
    condition     = length(var.allowed_emails) > 0 && alltrue([for e in var.allowed_emails : can(regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$", trimspace(e)))])
    error_message = "allowed_emails needs at least one address, and every entry must be a plain email address."
  }
}

variable "gemini_api_key" {
  description = "Leave null (recommended) to let Terraform create a Gemini key restricted to the Generative Language API, in this billing-enabled project. Or paste an existing key."
  type        = string
  default     = null
  sensitive   = true
}

variable "gemini_model" {
  description = "Model used for transcription and extraction."
  type        = string
  default     = "gemini-3.5-flash-lite"
}

variable "region" {
  description = "Region of the capture function. Must match the web app (default in the code: asia-southeast1)."
  type        = string
  default     = "asia-southeast1"
}

variable "firestore_location" {
  description = "Firestore location. Cannot be changed after the database exists."
  type        = string
  default     = "asia-southeast1"
}

variable "enable_pitr" {
  description = "Point-in-time recovery for Firestore (7 days). Protects against accidental deletes."
  type        = bool
  default     = true
}

variable "enforce_app_check" {
  description = "Reject Firestore requests that carry no valid App Check token. The function always enforces it."
  type        = bool
  default     = true
}

variable "billing_account_id" {
  description = "Optional. With monthly_budget it creates a budget that emails the billing admins at 50/90/100%."
  type        = string
  default     = null
}

variable "monthly_budget" {
  description = "Monthly budget in the billing account's currency (whole units)."
  type        = number
  default     = null
}

variable "budget_currency" {
  description = "Must equal the billing account's currency, e.g. USD or VND."
  type        = string
  default     = "USD"
}
