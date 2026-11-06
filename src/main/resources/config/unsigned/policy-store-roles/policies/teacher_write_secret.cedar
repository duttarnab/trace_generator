@id("teacher_write_secret")
permit(principal is Jans::User, action == Jans::Action::"Write", resource is Jans::SecretDocument)
when { principal.role.contains("Teacher") };
