@id("contractor_forbid_secret")
forbid(principal is Jans::User, action, resource is Jans::SecretDocument)
when { principal.role.contains("Contractor") };
