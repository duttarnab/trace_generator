@id("support_forbid_secret_write")
forbid(principal is Jans::User, action == Jans::Action::"Write", resource is Jans::SecretDocument)
when { principal.role.contains("Support") };
