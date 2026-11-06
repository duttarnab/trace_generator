@id("hr_read_secret")
permit(principal is Jans::User, action == Jans::Action::"Read", resource is Jans::SecretDocument)
when { principal.role.contains("HR") };
